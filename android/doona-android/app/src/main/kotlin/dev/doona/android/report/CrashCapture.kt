package dev.doona.android.report

import android.os.Looper
import android.os.Process
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport
import kotlin.system.exitProcess

/**
 * The app's uncaught-exception handler: it saves a crash file ([crashText]) among the core's ([CrashFiles]), then hands
 * the exception to the handler it replaced, which ends the process as before.
 */
object CrashCapture {
    /**
     * Installs the handler; [version] is the app's version line, as a crash file states it. Call once, from the
     * application: [main] is read then, since asking the Looper at a crash takes a monitor another thread may hold.
     */
    fun install(files: CrashFiles, version: String, main: Thread = Looper.getMainLooper().thread) {
        val current = Thread.getDefaultUncaughtExceptionHandler()
        // An application created again in the same process, as in JVM tests, replaces its handler instead of stacking
        // one, and keeps its threads.
        val previous = if (current is CrashHandler) current.previous else current
        val threads = (current as? CrashHandler)?.threads ?: CrashThreads()
        Thread.setDefaultUncaughtExceptionHandler(CrashHandler(files, version, previous, threads) { it === main })
    }
}

/**
 * Saves the crash, then calls [previous]; nothing on the way can keep [previous] from being called. The crashing thread
 * takes its deadline, [waitMs] from entering, before anything else; hands the crash to [threads], started before any
 * crash, without waiting for a lock; and waits only for what is left, then goes on however far the file got: a sync that
 * does not return, the directory's lock held elsewhere, or [isMain] blocking cannot hold the crash back. The writer has
 * the exception's frames read and checked ([KnownNames]) on the other thread, waited for at most [FRAMES_WAIT_MS]:
 * reading them takes the exception's lock, which a thread waiting for the crashing one to end may hold; the file then
 * names the class only.
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
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(waitMs)
        try {
            threads.writer.call(deadline) {
                val frames = threads.reader.call(System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(FRAMES_WAIT_MS)) { ownFrames(error) }
                val events = AppEvents.breadcrumbs(CRASH_EVENT_LIMIT)
                files.write(crashText(error.javaClass.name, frames, isMain(thread), version, System.currentTimeMillis(), events = events))
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
 * handler, which is the one asking. A job reaches it through a queue without locks, and a wake-up that takes none.
 */
internal class CrashWorker(name: String) {
    private val jobs = ConcurrentLinkedQueue<Runnable>()
    private val thread = Thread(::serve, name)

    init {
        thread.isDaemon = true
        thread.uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, _ -> }
        thread.start()
    }

    private fun serve() {
        while (true) {
            val job = jobs.poll()
            if (job == null) LockSupport.park(this) else runCatching { job.run() }
        }
    }

    /** Runs [job] on the thread: its result, or null when it failed or did not end by [deadline], a [System.nanoTime]. */
    fun <T : Any> call(deadline: Long, job: () -> T?): T? {
        val result = AtomicReference<T?>()
        val done = CountDownLatch(1)
        jobs.offer {
            try {
                result.set(job())
            } finally {
                done.countDown()
            }
        }
        LockSupport.unpark(thread)
        val left = (deadline - System.nanoTime()).coerceAtLeast(0)
        return if (done.await(left, TimeUnit.NANOSECONDS)) result.get() else null
    }
}

/** The message every app crash file gives: an exception's own message may quote anything (ARCH D21). */
internal const val CRASH_MESSAGE = "uncaught exception"

/** Frames of the app's own code; R8 keeps the package (`proguard-rules.pro`), so a release build's frames match too. */
private const val OWN = "dev.doona."

/**
 * The app's frames a file holds, the first ones. Each is checked against the app's classes, which loads them: past 16,
 * the checks of a cold start, with no class loaded yet, would not end within the reader's 50 ms.
 */
private const val MAX_FRAMES = 16

internal const val BREADCRUMBS = "breadcrumbs:"
internal const val BREADCRUMB = "  event: "
internal const val CRASH_EVENT_LIMIT = 50

/**
 * A crash file's text for an exception of class [type], in the core's format: `key: value` lines, a blank line, then the
 * frames. After its source it states its format ([APP_FORMAT]), which the export reads ([exportedCrash]); then the app's
 * [version], whether the [main] thread threw (a thread's name can be anything, a URL included), the first of [frames], a
 * fixed message, then the class, if [names] knows it, and [frames]; null [frames] could not be read. Never an exception's
 * message, nor a frame of other code: either may quote a URL, an address or a credential (ARCH D21).
 */
internal fun crashText(
    type: String,
    frames: List<String>?,
    main: Boolean,
    version: String,
    atEpochMs: Long,
    names: KnownNames = KnownNames(),
    events: List<AppEvent> = emptyList(),
): String = buildString {
    append(APP_HEADER)
    appendLine("atEpochMs: $atEpochMs")
    appendLine("version: $version")
    appendLine("thread: ${if (main) "main" else "other"}")
    appendLine(LOCATION + (frames?.firstOrNull() ?: UNKNOWN))
    appendLine("message: $CRASH_MESSAGE")
    appendLine()
    appendLine(BACKTRACE)
    appendLine(EXCEPTION + names.type(type))
    frames?.forEach { appendLine(FRAME + it) }
    if (events.isNotEmpty()) {
        appendLine(BREADCRUMBS)
        for (event in events) {
            appendLine("$BREADCRUMB${event.atEpochMs} ${event.level} ${event.text}")
        }
    }
}

/**
 * The app's own frames of [error], at most [MAX_FRAMES], each `class.method:line` when [names] knows both, else
 * [REDACTED]. Takes the exception's lock.
 */
internal fun ownFrames(error: Throwable, names: KnownNames = KnownNames()): List<String> =
    error.stackTrace.filter { it.className.startsWith(OWN) }.take(MAX_FRAMES).map { names.frame(it.className, it.methodName, it.lineNumber) }
