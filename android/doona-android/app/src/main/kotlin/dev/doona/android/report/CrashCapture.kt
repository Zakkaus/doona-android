package dev.doona.android.report

import android.os.Looper
import android.os.Process
import java.util.concurrent.atomic.AtomicReference
import kotlin.system.exitProcess

/**
 * The app's uncaught-exception handler: it saves a crash file ([crashText]) among the core's ([CrashFiles]), then hands
 * the exception to the handler it replaced, which ends the process as before.
 */
object CrashCapture {
    /** Installs the handler; [version] is the app's version line, as a crash file states it. Call once, from the application. */
    fun install(files: CrashFiles, version: String) {
        val current = Thread.getDefaultUncaughtExceptionHandler()
        // An application created again in the same process, as in JVM tests, replaces its handler instead of stacking one.
        val previous = if (current is CrashHandler) current.previous else current
        Thread.setDefaultUncaughtExceptionHandler(CrashHandler(files, version, previous) { it === Looper.getMainLooper().thread })
    }
}

/**
 * Saves the crash, then calls [previous]; nothing it does on the way can keep [previous] from being called. It reads the
 * exception's class only, which takes no lock. Its frames are read on another thread, waited for at most [FRAMES_WAIT_MS]:
 * reading an exception's stack or cause takes the exception's lock, and a thread that holds it may be waiting for this
 * one to end, so reading here could keep the crash from ever being handed on.
 */
internal class CrashHandler(
    private val files: CrashFiles,
    private val version: String,
    val previous: Thread.UncaughtExceptionHandler?,
    private val isMain: (Thread) -> Boolean,
) : Thread.UncaughtExceptionHandler {
    override fun uncaughtException(thread: Thread, error: Throwable) {
        try {
            val frames = ownFramesWithin(error, FRAMES_WAIT_MS)
            files.write(crashText(error.javaClass.name, frames, isMain(thread), version, System.currentTimeMillis()))
        } catch (_: Throwable) {
            // The process is ending: a crash file that could not be written is lost, the exception still goes on.
        }
        if (previous != null) {
            previous.uncaughtException(thread, error)
        } else {
            Process.killProcess(Process.myPid())
            exitProcess(CRASH_EXIT)
        }
    }

    private companion object {
        const val CRASH_EXIT = 10
        const val FRAMES_WAIT_MS = 200L
    }
}

/** The message every app crash file gives: an exception's own message may quote anything (ARCH D21). */
internal const val CRASH_MESSAGE = "uncaught exception"

/** Frames of the app's own code; R8 keeps the package (`proguard-rules.pro`), so a release build's frames match too. */
private const val OWN = "dev.doona."
private const val MAX_FRAMES = 64

/**
 * A crash file's text for an exception of class [type], in the core's format: `key: value` lines, a blank line, then the
 * frames. It states the app's [version], whether the [main] thread threw (a thread's name can be anything, a URL
 * included), the first of [frames], a fixed message, then the class and [frames] as `class.method:line`; null [frames]
 * could not be read. Never an exception's message, nor a frame of other code: either may quote a URL, an address or a
 * credential (ARCH D21).
 */
internal fun crashText(type: String, frames: List<String>?, main: Boolean, version: String, atEpochMs: Long): String = buildString {
    appendLine("source: app")
    appendLine("atEpochMs: $atEpochMs")
    appendLine("version: $version")
    appendLine("thread: ${if (main) "main" else "other"}")
    appendLine("location: ${frames?.firstOrNull() ?: "unknown"}")
    appendLine("message: $CRASH_MESSAGE")
    appendLine()
    appendLine("backtrace:")
    appendLine("exception: $type")
    frames?.forEach { appendLine("  at $it") }
}

/**
 * The app's own frames of [error], at most [MAX_FRAMES], read on a thread of their own; null when they were not read
 * within [waitMs] or could not be (no thread to read them on). That thread hands any failure to nobody: it must not
 * reach the default handler, which is the one asking.
 */
internal fun ownFramesWithin(error: Throwable, waitMs: Long): List<String>? {
    val frames = AtomicReference<List<String>?>()
    return try {
        val reader = Thread({
            frames.set(error.stackTrace.filter { it.className.startsWith(OWN) }.take(MAX_FRAMES).map(::frame))
        }, "doona-crash-frames")
        reader.isDaemon = true
        reader.uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, _ -> }
        reader.start()
        reader.join(waitMs)
        frames.get()
    } catch (_: Throwable) {
        null
    }
}

private fun frame(e: StackTraceElement) = "${e.className}.${e.methodName}:${e.lineNumber}"
