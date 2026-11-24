package dev.doona.android.report

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** A wait no write here outlasts, for the tests that read the file a crash left: a slow machine may take 200 ms. */
private const val SURE_MS = 10_000L

/** The uncaught-exception handler: the crash file it writes holds no message and no foreign frame, and the chain goes on. */
class CrashCaptureTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val files get() = CrashFiles(tmp.root.resolve("crash"))

    /** An exception as a library would throw it: its messages quote a subscription URL and a node's address. */
    private fun leaky() = IllegalStateException(
        "GET https://sub.example.net/api?token=tk_5ecret failed",
        java.io.IOException("connect to 203.0.113.45:443 (hk1.edge.example.org) refused"),
    )

    @Test
    fun `a crash leaves one file in the core's format, then goes to the previous handler`() {
        var handed: Pair<Thread, Throwable>? = null
        val handler = CrashHandler(files, "0.1 debug arm64-v8a-android", { t, e -> handed = t to e }, waitMs = SURE_MS) { false }
        val error = leaky()
        handler.uncaughtException(Thread.currentThread(), error)

        assertSame(Thread.currentThread(), handed?.first)
        assertSame(error, handed?.second)
        val text = files.list().single().readText()
        val head = text.substringBefore("\n\n").lines().associate { it.substringBefore(": ") to it.substringAfter(": ") }
        assertEquals(listOf("source", "format", "atEpochMs", "version", "thread", "location", "message"), head.keys.toList())
        assertEquals("app", head["source"])
        assertEquals("1", head["format"])
        assertEquals("0.1 debug arm64-v8a-android", head["version"])
        assertEquals("other", head["thread"])
        assertEquals(CRASH_MESSAGE, head["message"])
        assertTrue(head["location"]!!.startsWith("dev.doona.android.report.CrashCaptureTest."))
        val trace = text.substringAfter("\n\nbacktrace:\n").substringBefore("\n$BREADCRUMBS\n").lines().filter { it.isNotEmpty() }
        assertEquals("exception: java.lang.IllegalStateException", trace.first())
        assertEquals("the cause is not read", 1, trace.count { !it.startsWith("  at ") })
        // The test's own frames are code the app holds, written as they are; any other is redacted.
        assertTrue(trace.filter { it.startsWith("  at ") }.all { it.startsWith("  at dev.doona.") || it == "  at $REDACTED" })
        for (leak in listOf("https", "example", "tk_5ecret", "203.0.113.45", "refused", "org.junit", "java.lang.reflect")) {
            assertFalse(leak, leak in text)
        }
    }

    @Test
    fun `the main thread is named, any other is not`() {
        val handler = CrashHandler(files, "v", { _, _ -> }, waitMs = SURE_MS) { true }
        handler.uncaughtException(Thread("OkHttp https://sub.example.net/"), leaky())
        val text = files.list().single().readText()
        assertTrue("thread: main\n" in text)
        assertFalse("OkHttp" in text)
    }

    @Test
    fun `an exception's cause is never asked for`() {
        CrashHandler(files, "v", { _, _ -> }, waitMs = SURE_MS) { false }.uncaughtException(Thread.currentThread(), GuardedCause())
        assertTrue(files.list().single().readText().contains("\nexception: dev.doona.android.report.GuardedCause\n"))
    }

    @Test
    fun `a file that cannot be written still hands the crash on`() {
        tmp.newFile("crash")
        var handed = false
        CrashHandler(files, "v", { _, _ -> handed = true }) { false }.uncaughtException(Thread.currentThread(), leaky())
        assertTrue(handed)
    }

    @Test
    fun `installing again replaces the handler instead of stacking one`() {
        val before = Thread.getDefaultUncaughtExceptionHandler()
        try {
            CrashCapture.install(files, "v", main = Thread.currentThread())
            val first = Thread.getDefaultUncaughtExceptionHandler() as CrashHandler
            CrashCapture.install(files, "v", main = Thread.currentThread())
            val second = Thread.getDefaultUncaughtExceptionHandler() as CrashHandler
            assertSame(before, first.previous)
            assertSame(before, second.previous)
            assertSame("the threads are kept", first.threads, second.threads)
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(before)
        }
    }

    @Test
    fun `the crash goes on while another thread holds the exception`() {
        val error = leaky()
        val handed = CountDownLatch(1)
        val handler = CrashHandler(files, "v", { _, _ -> handed.countDown() }, waitMs = SURE_MS) { false }
        val crashing = Thread { handler.uncaughtException(Thread.currentThread(), error) }
        // As a thread that locked the exception and waits for the crashing one to end.
        synchronized(error) {
            crashing.start()
            assertTrue("the handler waited on the exception's lock", handed.await(5, TimeUnit.SECONDS))
        }
        crashing.join()
        assertTrue(files.list().single().readText().contains("exception: java.lang.IllegalStateException"))
    }

    @Test
    fun `a write that hangs still hands the crash on by the deadline, from a thread started before the crash`() {
        val release = CountDownLatch(1)
        val writing = AtomicReference<Thread>()
        // The clock is read in the middle of the write: blocked there, it stands for an fsync that does not return.
        val hanging = CrashFiles(tmp.root.resolve("crash"), clock = {
            writing.set(Thread.currentThread())
            release.await()
            1_795_300_150_000
        })
        val handed = CountDownLatch(1)
        val handler = CrashHandler(hanging, "v", { _, _ -> handed.countDown() }) { false }
        val before = Thread.getAllStackTraces().keys
        val crashing = Thread { handler.uncaughtException(Thread.currentThread(), leaky()) }
        try {
            val begun = System.nanoTime()
            crashing.start()
            assertTrue("the crash was not handed on", handed.await(5, TimeUnit.SECONDS))
            assertTrue("handed on late", System.nanoTime() - begun < TimeUnit.SECONDS.toNanos(1))
            assertTrue("the write ran on a thread made at the crash", writing.get() in before)
        } finally {
            release.countDown()
            crashing.join()
        }
    }

    @Test
    fun `a frame whose names are no plain identifiers is written redacted`() {
        val error = IllegalStateException().apply {
            stackTrace = arrayOf(
                StackTraceElement("dev.doona.android.Forged", "https://user:hunter2@sub.example.net/api", "Forged.kt", 7),
                StackTraceElement("dev.doona.android.https://user:hunter2@sub.example.net", "run", "Forged.kt", 8),
                StackTraceElement("dev.doona.android.report.CrashFiles\$Step", "valueOf", "CrashFiles.kt", 9),
                StackTraceElement("dev.doona.android.report.CrashFiles", "<init>", "CrashFiles.kt", 10),
            )
        }
        CrashHandler(files, "v", { _, _ -> }, waitMs = SURE_MS) { false }.uncaughtException(Thread.currentThread(), error)
        val text = files.list().single().readText()
        for (leak in listOf("hunter2", "example", "https", "user:")) assertFalse(leak, leak in text)
        val frames = text.lines().filter { it.startsWith("  at ") }
        val own = listOf("dev.doona.android.report.CrashFiles\$Step.valueOf:9", "dev.doona.android.report.CrashFiles.<init>:10")
        assertEquals(listOf("<redacted>", "<redacted>") + own, frames.map { it.removePrefix("  at ") })
        assertTrue("location: <redacted>\n" in text)
    }

    @Test
    fun `a frame is written only when the app loads its class and that class declares its method`() {
        val error = IllegalStateException().apply {
            stackTrace = arrayOf(
                // Identifiers, as a customer's host or a password can be.
                StackTraceElement("dev.doona.customer.example", "run", "Forged.kt", 7),
                StackTraceElement("dev.doona.android.report.CrashFiles", "hunter2", "Forged.kt", 8),
                StackTraceElement("dev.doona.android.report.CrashFiles", "write", "CrashFiles.kt", 66),
            )
        }
        CrashHandler(files, "v", { _, _ -> }, waitMs = SURE_MS) { false }.uncaughtException(Thread.currentThread(), error)
        val text = files.list().single().readText()
        for (leak in listOf("customer", "hunter2")) assertFalse(leak, leak in text)
        val frames = text.lines().filter { it.startsWith("  at ") }.map { it.removePrefix("  at ") }
        assertEquals(listOf(REDACTED, REDACTED, "dev.doona.android.report.CrashFiles.write:66"), frames)
    }

    @Test
    fun `the exception's class is written only when the app loads it`() {
        val text = crashText("dev.doona.customer.example.Leak", emptyList(), false, "v", 1_795_300_150_000)
        assertFalse(text, "customer" in text)
        assertTrue(text, "\nexception: $REDACTED\n" in text)
        assertTrue("exception: java.lang.IllegalStateException\n" in crashText("java.lang.IllegalStateException", emptyList(), false, "v", 1))
    }

    @Test
    fun `a static initialiser passes only for a class that has one, a constructor only for a class that has one`() {
        val names = KnownNames()
        // An object keeps its instance in a static field, which its static initialiser sets.
        assertEquals("dev.doona.android.report.AppEvents.<clinit>:12", names.frame("dev.doona.android.report.AppEvents", "<clinit>", 12))
        // Instance fields only: no static initialiser.
        assertEquals(REDACTED, names.frame("dev.doona.android.report.CrashThreads", "<clinit>", 7))
        assertEquals("dev.doona.android.report.CrashThreads.<init>:7", names.frame("dev.doona.android.report.CrashThreads", "<init>", 7))
        // An interface has no constructor.
        assertEquals(REDACTED, names.frame("dev.doona.android.report.ReportExporter", "<init>", 7))
    }

    @Test
    fun `only the first sixteen of the app's frames are checked and written`() {
        val error = IllegalStateException().apply {
            stackTrace = Array(40) { StackTraceElement("dev.doona.android.report.CrashFiles", "write", "CrashFiles.kt", it + 1) }
        }
        CrashHandler(files, "v", { _, _ -> }, waitMs = SURE_MS) { false }.uncaughtException(Thread.currentThread(), error)
        val frames = files.list().single().readText().lines().filter { it.startsWith("  at ") }
        assertEquals((1..16).map { "  at dev.doona.android.report.CrashFiles.write:$it" }, frames)
    }

    @Test
    fun `the wait starts when the handler is entered`() {
        val release = CountDownLatch(1)
        val handed = CountDownLatch(1)
        // Asking whether the thread is the main one blocks, as the Looper's monitor can.
        val handler = CrashHandler(files, "v", { _, _ -> handed.countDown() }) {
            release.await()
            true
        }
        val crashing = Thread { handler.uncaughtException(Thread.currentThread(), leaky()) }
        try {
            val begun = System.nanoTime()
            crashing.start()
            assertTrue("the crash was not handed on", handed.await(5, TimeUnit.SECONDS))
            assertTrue("handed on late", System.nanoTime() - begun < TimeUnit.SECONDS.toNanos(1))
        } finally {
            release.countDown()
            crashing.join()
        }
    }
}

/** Asking for its cause throws: asking takes the exception's lock (Throwable.getCause is synchronized). */
private class GuardedCause : RuntimeException() {
    override val cause: Throwable get() = error("asked")
}
