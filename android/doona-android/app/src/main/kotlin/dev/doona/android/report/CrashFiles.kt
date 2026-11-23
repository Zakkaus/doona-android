package dev.doona.android.report

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile

/**
 * The crash files the core and the app share, in the core's `dataDir/crash` (core-api.md, `diagnostics`). A file is
 * `<n>.txt`, `n` a millisecond time of 13 digits ([MIN_NUMBER], [MAX_NUMBER]) above every number a crash file had:
 * above the files present and above the mark the core saves in `.core.mark` when a clear removes them. A file holds at
 * most [MAX_BYTES], and the directory keeps the newest [MAX_FILES]. The core writes its own (`crash.rs`); the app writes
 * one from its uncaught-exception handler ([CrashCapture]), with [clock] for the name.
 *
 * Both write by one protocol, holding the directory's lock (`.lock`, `crash_dir.rs`): remove every part an ended write
 * left, take the number, write `<n>.part-app` and sync it, remove the oldest files to leave room, then rename the part
 * `<n>.txt`. So no empty or partial file has a crash file's name, and no name is taken twice. A rename and not a hard
 * link: SELinux forbids apps to make one (AOSP `app_neverallows.te`). The app takes the lock with `FileChannel.lock`, a
 * traditional fcntl lock; the core takes an open file description lock, which conflicts with it within one process too.
 * The app's lock belongs to the process, so its threads take turns on [PROCESS] first: the JVM refuses a second lock on
 * the file in one process, and closing any descriptor of the file drops the one held.
 */
class CrashFiles(locate: () -> File, private val clock: () -> Long = System::currentTimeMillis) {
    constructor(dir: File, clock: () -> Long = System::currentTimeMillis) : this({ dir }, clock)

    /** The directory, found on first use: the app's directories are made when first asked for, which is disk work. */
    val dir: File by lazy(locate)

    /** For the tests: called between the steps of a write. One that throws ends the write there, as a kill would. */
    internal var step: (Step) -> Unit = {}

    internal enum class Step { Written, Trimmed }

    /** The crash files present, oldest first. */
    fun list(): List<File> = dir.listFiles().orEmpty()
        .mapNotNull { file -> number(file.name)?.takeIf { file.isFile }?.let { it to file } }
        .sortedBy { it.first }
        .map { it.second }

    /** The highest number a crash file present has; 0 with none. */
    fun newest(): Long = list().lastOrNull()?.let { number(it.name) } ?: 0

    /** The crash file [name] as it is now, at most [MAX_BYTES] of it; null when it is no crash file's name or is gone. */
    fun read(name: String): ByteArray? {
        if (number(name) == null) return null
        return try {
            File(dir, name).inputStream().use { input ->
                val buffer = ByteArray(MAX_BYTES)
                var filled = 0
                while (filled < MAX_BYTES) {
                    val read = input.read(buffer, filled, MAX_BYTES - filled)
                    if (read < 0) break
                    filled += read
                }
                buffer.copyOf(filled)
            }
        } catch (_: IOException) {
            null
        }
    }

    /**
     * Saves [text] as a new crash file and returns its name, or null when it could not: the disk refused, an old file
     * would not go, or no number is left. Waits for the directory's lock, as long as the core holds it.
     */
    fun write(text: String): String? = try {
        locked { publish(capped(text)) }
    } catch (_: IOException) {
        null
    }

    /** Removes the parts writes that never ended left, as when the system ended a process during one. Reads the disk. */
    fun sweep() {
        try {
            locked {}
        } catch (_: IOException) {
            // Tried again by the next write.
        }
    }

    private fun publish(bytes: ByteArray): String? {
        val present = list()
        val newest = present.lastOrNull()?.let { number(it.name) } ?: 0
        val at = maxOf(maxOf(savedMark(), newest) + 1, clock(), MIN_NUMBER)
        if (at > MAX_NUMBER) return null
        val part = File(dir, "$at$PART")
        try {
            FileOutputStream(part).use {
                it.write(bytes)
                it.fd.sync()
            }
            step(Step.Written)
            trim(present)
            step(Step.Trimmed)
            if (!part.renameTo(File(dir, "$at.txt"))) throw IOException("not renamed")
        } catch (e: IOException) {
            part.delete()
            throw e
        }
        return "$at.txt"
    }

    /** Removes the oldest of [present] until one more leaves [MAX_FILES]. One that will not go stops the write: it would leave six. */
    private fun trim(present: List<File>) {
        for (old in present.take((present.size + 1 - MAX_FILES).coerceAtLeast(0))) {
            if (!old.delete() && old.exists()) throw IOException("not removed")
        }
    }

    /** Runs [block] holding the directory's lock, after removing every part an ended write left: no write is running. */
    private fun <T> locked(block: () -> T): T = synchronized(PROCESS) {
        dir.mkdirs()
        RandomAccessFile(File(dir, LOCK), "rw").use { lock ->
            lock.channel.lock()
            dir.listFiles { file -> isLeftOver(file.name) }.orEmpty().forEach { it.delete() }
            block()
        }
    }

    /** The mark the core saved with its last clear; 0 when there is none or it cannot be read. */
    private fun savedMark(): Long = try {
        File(dir, MARK).readText().trim().toLongOrNull()?.takeIf { it in 0..MAX_NUMBER } ?: 0
    } catch (_: IOException) {
        0
    }

    companion object {
        const val MAX_FILES = 5
        const val MAX_BYTES = 64 * 1024

        /** A 13-digit millisecond time, until the year 2286; the core takes no larger number. */
        const val MAX_NUMBER = 9_999_999_999_999L

        /** The smallest 13-digit number, for a clock set before September 2001. */
        const val MIN_NUMBER = 1_000_000_000_000L

        /** The app's file in progress is `<n>.part-app`, the core's `<n>.part-core`. */
        private const val PART = ".part-app"
        private const val LOCK = ".lock"
        private const val MARK = ".core.mark"
        private const val DIGITS = 13

        /** Taken before the directory's lock, by every thread of the process. */
        private val PROCESS = Any()

        /** A part of a write that never ended: this version's, or an earlier one's (`.app.tmp`, `.core-<pid>.tmp` and the like). */
        private fun isLeftOver(name: String) =
            ".part-" in name || ((name.startsWith(".app") || name.startsWith(".core")) && name.endsWith(".tmp"))

        /** The number a crash file's name holds, or null when [name] is no crash file's: `<digits>.txt`, at most [MAX_NUMBER]. */
        fun number(name: String): Long? {
            val digits = name.removeSuffix(".txt")
            if (digits == name || digits.isEmpty() || digits.length > DIGITS || !digits.all { it in '0'..'9' }) return null
            return digits.toLong().takeIf { it <= MAX_NUMBER }
        }

        /** [text] as UTF-8, cut to [MAX_BYTES] at the start of a character. */
        internal fun capped(text: String): ByteArray {
            val bytes = text.toByteArray()
            if (bytes.size <= MAX_BYTES) return bytes
            var end = MAX_BYTES
            // A byte 10xxxxxx continues a character; cut before the byte that starts it.
            while (end > 0 && bytes[end].toInt() and CONTINUATION_MASK == CONTINUATION) end--
            return bytes.copyOf(end)
        }

        private const val CONTINUATION_MASK = 0xC0
        private const val CONTINUATION = 0x80
    }
}
