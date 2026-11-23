package dev.doona.android.report

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

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
        val handler = CrashHandler(files, "0.1 debug arm64-v8a-android", { t, e -> handed = t to e }) { false }
        val error = leaky()
        handler.uncaughtException(Thread.currentThread(), error)

        assertSame(Thread.currentThread(), handed?.first)
        assertSame(error, handed?.second)
        val text = files.list().single().readText()
        val head = text.substringBefore("\n\n").lines().associate { it.substringBefore(": ") to it.substringAfter(": ") }
        assertEquals(listOf("source", "atEpochMs", "version", "thread", "location", "message"), head.keys.toList())
        assertEquals("app", head["source"])
        assertEquals("0.1 debug arm64-v8a-android", head["version"])
        assertEquals("other", head["thread"])
        assertEquals(CRASH_MESSAGE, head["message"])
        assertTrue(head["location"]!!.startsWith("dev.doona.android.report.CrashCaptureTest."))
        val trace = text.substringAfter("\n\nbacktrace:\n").lines().filter { it.isNotEmpty() }
        assertEquals("exception: java.lang.IllegalStateException", trace.first())
        assertTrue("cause: java.io.IOException" in trace)
        assertTrue(trace.filter { it.startsWith("  at ") }.all { it.startsWith("  at dev.doona.") })
        for (leak in listOf("https", "example", "tk_5ecret", "203.0.113.45", "refused", "org.junit", "java.lang.reflect")) {
            assertFalse(leak, leak in text)
        }
    }

    @Test
    fun `the main thread is named, any other is not`() {
        val handler = CrashHandler(files, "v", { _, _ -> }) { true }
        handler.uncaughtException(Thread("OkHttp https://sub.example.net/"), leaky())
        val text = files.list().single().readText()
        assertTrue("thread: main\n" in text)
        assertFalse("OkHttp" in text)
    }

    @Test
    fun `an exception that is its own cause is written once`() {
        val looped = object : RuntimeException() {
            override val cause: Throwable get() = this
        }
        CrashHandler(files, "v", { _, _ -> }) { false }.uncaughtException(Thread.currentThread(), looped)
        assertEquals(1, Regex("^(exception|cause): ", RegexOption.MULTILINE).findAll(files.list().single().readText()).count())
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
            CrashCapture.install(files, "v")
            val first = Thread.getDefaultUncaughtExceptionHandler() as CrashHandler
            CrashCapture.install(files, "v")
            val second = Thread.getDefaultUncaughtExceptionHandler() as CrashHandler
            assertSame(before, first.previous)
            assertSame(before, second.previous)
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(before)
        }
    }
}
