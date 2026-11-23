package dev.doona.android.report

import android.os.Looper
import android.os.Process
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
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
        // An application created again in the same process, as in JVM tests, replaces its handler instead of stacking
        // one, and keeps its threads.
        val previous = if (current is CrashHandler) current.previous else current
        val threads = (current as? CrashHandler)?.threads ?: CrashThreads()
        Thread.setDefaultUncaughtExceptionHandler(CrashHandler(files, version, previous, threads) { it === Looper.getMainLooper().thread })
    }
}

/**
 * Saves the crash, then calls [previous]; nothing on the way can keep [previous] from being called. The crashing thread
 * hands the crash to [threads], started before any crash, and waits once, at most [waitMs] in all, then goes on however
 * far the file got: a sync that does not return, or the directory's lock held elsewhere, cannot hold the crash back. The
 * writer reads the exception's frames on the other thread, waited for at most [FRAMES_WAIT_MS]: reading them takes the
 * exception's lock, which a thread waiting for the crashing one to end may hold; the file then names the class only.
 */
internal class CrashHandler(
    private val files: CrashFiles,
    private val version: String,
    val previous: Thread.UncaughtExceptionHandler?,
    val threads: CrashThreads = CrashThreads(),
    private val waitMs: Long = CRASH_WAIT_MS,
    private val isMain: (Thread) -> Boolean,
) : Thread.UncaughtExceptionHandler {
    override fun uncaughtException(thread: Thread, error: Throwable) {
        try {
            val main = isMain(thread)
            threads.writer.call(waitMs) {
                val frames = threads.reader.call(FRAMES_WAIT_MS) { ownFrames(error) }
                files.write(crashText(error.javaClass.name, frames, main, version, System.currentTimeMillis()))
            }
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
        const val CRASH_WAIT_MS = 200L
        const val FRAMES_WAIT_MS = 50L
    }
}

/** The threads a crash is handled on, started with the handler: starting one while the process fails may fail too. */
internal class CrashThreads {
    val writer = CrashWorker("doona-crash")
    val reader = CrashWorker("doona-crash-frames")
}

/**
 * One thread that runs the jobs it is given, one at a time. It hands a failure to nobody: it must not reach the default
 * handler, which is the one asking.
 */
internal class CrashWorker(name: String) {
    private val jobs = LinkedBlockingQueue<Runnable>()

    init {
        val thread = Thread(::serve, name)
        thread.isDaemon = true
        thread.uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, _ -> }
        thread.start()
    }

    private fun serve() {
        while (true) {
            runCatching { jobs.take().run() }
        }
    }

    /** Runs [job] on the thread: its result, or null when it failed or did not end within [waitMs]. */
    fun <T : Any> call(waitMs: Long, job: () -> T?): T? {
        val result = AtomicReference<T?>()
        val done = CountDownLatch(1)
        jobs.put {
            try {
                result.set(job())
            } finally {
                done.countDown()
            }
        }
        return if (done.await(waitMs, TimeUnit.MILLISECONDS)) result.get() else null
    }
}

/** The message every app crash file gives: an exception's own message may quote anything (ARCH D21). */
internal const val CRASH_MESSAGE = "uncaught exception"

/** A frame [frameText] does not write. */
internal const val REDACTED = "<redacted>"

/** Frames of the app's own code; R8 keeps the package (`proguard-rules.pro`), so a release build's frames match too. */
private const val OWN = "dev.doona."
private const val MAX_FRAMES = 64

/** The two method names a JVM gives that are no identifiers: constructors and static initialisers. */
private val SPECIAL_METHODS = setOf("<init>", "<clinit>")

/**
 * A crash file's text for an exception of class [type], in the core's format: `key: value` lines, a blank line, then the
 * frames. It states the app's [version], whether the [main] thread threw (a thread's name can be anything, a URL
 * included), the first of [frames], a fixed message, then the class and [frames]; null [frames] could not be read. Never
 * an exception's message, nor a frame of other code: either may quote a URL, an address or a credential (ARCH D21).
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

/** The app's own frames of [error], at most [MAX_FRAMES], each [frameText]. Takes the exception's lock. */
internal fun ownFrames(error: Throwable): List<String> =
    error.stackTrace.filter { it.className.startsWith(OWN) }.take(MAX_FRAMES).map(::frameText)

/**
 * `class.method:line`, when every part of the class name and the method name is a plain JVM identifier; else
 * [REDACTED]. Any code can give an exception frames of its own (`setStackTrace`), with a URL for a method name.
 */
internal fun frameText(e: StackTraceElement): String {
    val plain = e.className.split('.').all(::isIdentifier) && (isIdentifier(e.methodName) || e.methodName in SPECIAL_METHODS)
    return if (plain) "${e.className}.${e.methodName}:${e.lineNumber}" else REDACTED
}

/** `[A-Za-z_$][A-Za-z0-9_$]*`. */
private fun isIdentifier(name: String): Boolean {
    fun letter(c: Char) = c in 'a'..'z' || c in 'A'..'Z' || c == '_' || c == '$'
    return name.isNotEmpty() && letter(name[0]) && name.all { letter(it) || it in '0'..'9' }
}
