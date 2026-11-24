package dev.doona.android.report

import android.util.Log
import dev.doona.android.report.AppEvents.CAPACITY
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.NoSuchFileException

internal class AppEventRing(file: (() -> File)? = null, io: CoroutineDispatcher = Dispatchers.IO, initial: List<AppEvent> = emptyList()) {
    private val lock = Any()
    private val events = ArrayDeque<AppEvent>(CAPACITY).apply { addAll(initial) }
    private var recalled = 0
    private val loaded = CompletableDeferred<Unit>()
    private val changed = Channel<Unit>(Channel.CONFLATED)
    private val writer = file?.let { path ->
        CoroutineScope(io).launch(start = CoroutineStart.LAZY) {
            val store = AppEventFile(path())
            val past = store.load()
            synchronized(lock) {
                // Records made while loading are newer, regardless of wall-clock changes.
                val first = (past.size - (CAPACITY - events.size)).coerceAtLeast(0)
                for (i in past.lastIndex downTo first) events.addFirst(past[i])
                recalled = past.size - first
            }
            loaded.complete(Unit)
            for (change in changed) store.save(snapshot())
        }
    }

    init {
        writer?.invokeOnCompletion { loaded.complete(Unit) }
        if (initial.isNotEmpty()) scheduleWrite()
    }

    fun add(event: AppEvent) {
        synchronized(lock) {
            if (events.size == CAPACITY && recalled > 0) recalled--
            events.appendBounded(event)
        }
        scheduleWrite()
    }

    private fun scheduleWrite() {
        if (writer != null) {
            changed.trySend(Unit)
            writer.start()
        }
    }

    suspend fun loadedSnapshot(): List<AppEvent> {
        if (writer != null) {
            writer.start()
            loaded.await()
        }
        return snapshot()
    }

    fun snapshot(): List<AppEvent> = synchronized(lock) { events.toList() }

    fun startupEvents(): List<AppEvent> = if (writer == null) snapshot() else emptyList()

    fun breadcrumbs(limit: Int): List<AppEvent> = synchronized(lock) {
        val actions = ArrayList<AppEvent>(limit)
        for (index in events.lastIndex downTo recalled) {
            val event = events[index]
            if (!actionText.matches(event.text)) continue
            actions.add(event)
            if (actions.size == limit) break
        }
        actions.reverse()
        actions
    }

    fun close() {
        writer?.cancel()
    }
}

private fun ArrayDeque<AppEvent>.appendBounded(event: AppEvent) {
    if (size == CAPACITY) removeFirst()
    addLast(event)
}

private class AppEventFile(private val file: File) {
    fun load(): List<AppEvent> {
        val text = try {
            Files.readAllBytes(file.toPath()).decodeToString()
        } catch (_: NoSuchFileException) {
            return emptyList()
        } catch (_: IOException) {
            Log.w(TAG, "app-events: read failed")
            return emptyList()
        }
        val events = ArrayDeque<AppEvent>(CAPACITY)
        // A newline commits a row; even a parseable tail without it may have lost bytes.
        text.substring(0, text.lastIndexOf('\n') + 1).splitToSequence('\n').forEach { line ->
            val fields = line.split('\t', limit = 3)
            if (fields.size != 3) return@forEach
            val time = fields[0].toLongOrNull() ?: return@forEach
            val level = AppEventLevel.entries.firstOrNull { it.name == fields[1] } ?: return@forEach
            val message = fields[2]
            if ('\u0000' !in message) events.appendBounded(AppEvent(time, level, message))
        }
        return events
    }

    fun save(events: List<AppEvent>) {
        val text = buildString {
            for (event in events) {
                append(event.atEpochMs).append('\t').append(event.level.name).append('\t')
                append(safeAppEventText(event.text)).append('\n')
            }
        }
        try {
            file.replaceSynced(text.encodeToByteArray())
        } catch (_: IOException) {
            Log.w(TAG, "app-events: write failed")
        }
    }

    private companion object {
        const val TAG = "doona.report"
    }
}
