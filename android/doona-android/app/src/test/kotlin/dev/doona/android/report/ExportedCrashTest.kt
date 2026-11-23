package dev.doona.android.report

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The header of an app crash file as the export sends it (ARCH D21): the time and the version go once each, at their
 * places before the backtrace, and the version only in the grammar the app writes.
 */
class ExportedCrashTest {
    private val head = listOf("source: app", "format: 1", "atEpochMs: 1795300150000")
    private val version = "version: 0.1 debug arm64-v8a-android"
    private val rest = listOf("thread: main", "location: unknown", "message: $CRASH_MESSAGE", "", BACKTRACE, "exception: java.lang.IllegalStateException")

    private fun file(lines: List<String>) = lines.joinToString("\n", postfix = "\n")

    private fun exported(lines: List<String>) = exportedCrash(file(lines).toByteArray(), KnownNames())!!.decodeToString()

    @Test
    fun `the version line as the app writes it goes out`() {
        assertEquals(file(head + version + rest), exported(head + version + rest))
    }

    @Test
    fun `a second version line is dropped`() {
        assertEquals(file(head + version + rest), exported(head + version + "version: 0.2 release x86_64-android" + rest))
    }

    @Test
    fun `a version line after the backtrace is dropped`() {
        assertEquals(file(head + version + rest), exported(head + version + rest + "version: 0.1 debug arm64-v8a-android"))
    }

    @Test
    fun `a version that is a DNS name is dropped`() {
        assertEquals(file(head + rest), exported(head + "version: customer.example.net" + rest))
        assertEquals(file(head + rest), exported(head + "version: 0.1 debug customer.example.net-android" + rest))
    }

    @Test
    fun `a time line out of its place is dropped`() {
        val twice = head + "atEpochMs: 1795300160000" + rest
        assertEquals(file(head + rest), exported(twice))
        assertEquals(file(head + version + rest), exported(head + version + rest + "atEpochMs: 1795300160000"))
    }

    @Test
    fun `the app writes only a version line the export keeps`() {
        val app = AppInfo("0.1", 1, "debug", listOf("arm64-v8a"), "16", 36, "Google", "Pixel 9")
        assertEquals("0.1 debug arm64-v8a-android", app.crashVersion)
        val odd = app.copy(version = "customer.example.net", buildType = "staging", abis = listOf("hunter2"))
        assertEquals("unknown unknown unknown-android", odd.crashVersion)
        assertEquals("unknown release unknown-android", app.copy(version = "", buildType = "release", abis = emptyList()).crashVersion)
        for (info in listOf(app, odd)) {
            val text = crashText("java.lang.IllegalStateException", null, true, info.crashVersion, 1_795_300_150_000)
            assertEquals(text, exportedCrash(text.toByteArray(), KnownNames())!!.decodeToString())
        }
    }
}
