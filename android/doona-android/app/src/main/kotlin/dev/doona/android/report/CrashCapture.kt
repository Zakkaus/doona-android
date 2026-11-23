package dev.doona.android.report

import android.os.Looper
import android.os.Process
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

/** Saves the crash, then calls [previous]; nothing it does on the way can keep [previous] from being called. */
internal class CrashHandler(
    private val files: CrashFiles,
    private val version: String,
    val previous: Thread.UncaughtExceptionHandler?,
    private val isMain: (Thread) -> Boolean,
) : Thread.UncaughtExceptionHandler {
    override fun uncaughtException(thread: Thread, error: Throwable) {
        try {
            files.write(crashText(error, isMain(thread), version, System.currentTimeMillis()))
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
    }
}

/** The message every app crash file gives: an exception's own message may quote anything (ARCH D21). */
internal const val CRASH_MESSAGE = "uncaught exception"

/** Frames of the app's own code; R8 keeps the package (`proguard-rules.pro`), so a release build's frames match too. */
private const val OWN = "dev.doona."
private const val MAX_CAUSES = 8
private const val MAX_FRAMES = 64

/**
 * A crash file's text for [error], in the core's format: `key: value` lines, a blank line, then the frames. It states the
 * app's [version], whether the [main] thread threw (a thread's name can be anything, a URL included), the first frame
 * of the app's own code, a fixed message, then for the exception and each cause its class and the app's own frames as
 * `class.method:line`. Never an exception's message, nor a frame of other code: either may quote a URL, an address or a
 * credential (ARCH D21).
 */
internal fun crashText(error: Throwable, main: Boolean, version: String, atEpochMs: Long): String = buildString {
    val chain = causes(error)
    appendLine("source: app")
    appendLine("atEpochMs: $atEpochMs")
    appendLine("version: $version")
    appendLine("thread: ${if (main) "main" else "other"}")
    appendLine("location: ${chain.firstNotNullOfOrNull { ownFrames(it).firstOrNull() }?.let(::frame) ?: "unknown"}")
    appendLine("message: $CRASH_MESSAGE")
    appendLine()
    appendLine("backtrace:")
    chain.forEachIndexed { i, t ->
        appendLine("${if (i == 0) "exception" else "cause"}: ${t.javaClass.name}")
        ownFrames(t).forEach { appendLine("  at ${frame(it)}") }
    }
}

/** [error] and its causes, outermost first, each once. */
private fun causes(error: Throwable): List<Throwable> {
    val seen = mutableListOf<Throwable>()
    var next: Throwable? = error
    while (next != null && seen.size < MAX_CAUSES) {
        val current: Throwable = next
        if (seen.any { it === current }) break
        seen += current
        next = current.cause
    }
    return seen
}

private fun ownFrames(t: Throwable): List<StackTraceElement> = t.stackTrace.filter { it.className.startsWith(OWN) }.take(MAX_FRAMES)

private fun frame(e: StackTraceElement) = "${e.className}.${e.methodName}:${e.lineNumber}"
