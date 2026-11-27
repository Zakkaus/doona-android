package dev.doona.android.designsystem.rv

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

/** A live rate's fixed form: one decimal in kB/s and up, its unit held near a step, and the widest it is written. */
class RvLiveRateTest {
    private val format = RvFormat(Locale.US)

    private fun shown(bytesPerSecond: Double, held: Int = -1) = format.rate(bytesPerSecond, format.rateUnit(bytesPerSecond, held)).toString()

    @Test
    fun `a live rate always has one decimal and never shows B per second`() {
        assertEquals("0.0 kB/s", shown(0.0))
        assertEquals("0.5 kB/s", shown(512.0))
        assertEquals("12.0 kB/s", shown(12_000.0))
        assertEquals("999.9 kB/s", shown(999_940.0))
        assertEquals("1.0 MB/s", shown(999_960.0))
        assertEquals("4.3 MB/s", shown(4_260_000.0))
    }

    @Test
    fun `a held unit steps down only below nine tenths`() {
        assertEquals("0.9 MB/s", shown(920_000.0, held = 2))
        assertEquals("899.0 kB/s", shown(899_000.0, held = 2))
        assertEquals("920.0 kB/s", shown(920_000.0, held = 1))
    }

    @Test
    fun `the widest live rate is four digits in each unit`() {
        assertEquals(listOf("999.9 kB/s", "999.9 MB/s", "999.9 GB/s", "999.9 TB/s"), format.widestRates().map { it.toString() })
    }
}
