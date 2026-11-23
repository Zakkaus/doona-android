package dev.doona.android.report

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** A crash is written once and offered until the offer reaches the screen; never again after that, whatever the user did. */
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
        next.shown()
        next.dismiss()
        next.check()
        assertFalse("a second check in the same process offers nothing", next.offered.value)

        assertFalse("the launch after shows nothing for the same crash", launch().offered.value)
    }

    @Test
    fun `an offer that was on screen is not made again, answered or not`() {
        crash(1_795_300_150_000)
        val first = launch()
        assertTrue(first.offered.value)
        first.shown()
        first.shown()
        assertFalse(launch().offered.value)
    }

    @Test
    fun `a later crash is offered again`() {
        crash(1_795_300_150_000)
        launch().apply { shown() }.dismiss()
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

    @Test
    fun `an offer that never reached the screen is made again`() {
        crash(1_795_300_150_000)
        // The process ends, or the first-run page is up, before the banner is drawn.
        assertTrue(launch().offered.value)
        assertTrue(launch().offered.value)
    }
}
