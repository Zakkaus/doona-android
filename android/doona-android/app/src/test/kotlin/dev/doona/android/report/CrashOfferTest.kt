package dev.doona.android.report

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** A crash is written once and offered once: at the first check after it, never again, whatever the user did. */
class CrashOfferTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val dir get() = tmp.root.resolve("crash")
    private val seen get() = tmp.root.resolve("crash-offered")

    /** A process that starts and looks for a crash to offer, as the activity's first creation does. */
    private fun launch(): CrashOffer = CrashOffer(CrashFiles(dir), seen).also { it.check() }

    private fun crash(at: Long) {
        CrashHandler(CrashFiles(dir, clock = { at }), "v", { _, _ -> }) { true }.uncaughtException(Thread.currentThread(), IllegalStateException())
    }

    @Test
    fun `a crash is written and offered once`() {
        crash(1_795_300_150_000)
        assertTrue(CrashFiles(dir).list().size == 1)

        val next = launch()
        assertTrue(next.offered.value)
        next.dismiss()
        next.check()
        assertFalse("a second check in the same process offers nothing", next.offered.value)

        assertFalse("the launch after shows nothing for the same crash", launch().offered.value)
    }

    @Test
    fun `an offer left unanswered is not made again`() {
        crash(1_795_300_150_000)
        assertTrue(launch().offered.value)
        assertFalse(launch().offered.value)
    }

    @Test
    fun `a later crash is offered again`() {
        crash(1_795_300_150_000)
        launch().dismiss()
        crash(1_795_300_160_000)
        assertTrue(launch().offered.value)
    }

    @Test
    fun `without a crash nothing is offered`() {
        assertFalse(launch().offered.value)
    }

    @Test
    fun `a crash the core wrote counts like the app's`() {
        dir.mkdirs()
        dir.resolve("1795300150000.txt").writeText("source: core\n")
        assertTrue(launch().offered.value)
    }
}
