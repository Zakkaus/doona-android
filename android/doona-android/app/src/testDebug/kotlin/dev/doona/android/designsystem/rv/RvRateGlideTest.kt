package dev.doona.android.designsystem.rv

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

/** [rvGlidingRate] when the readings stop and start again: it drops at once, and a new session starts from its first sample. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "zh-rTW-w412dp-h915dp-notnight-xhdpi")
class RvRateGlideTest {
    @get:Rule
    val compose = createComposeRule()

    private val format = RvFormat(Locale.TAIWAN)
    private var reading by mutableStateOf<Double?>(FAST)
    private var shown = ""

    private fun show() {
        compose.mainClock.autoAdvance = false
        compose.setContent { RvTheme(rvSystemScheme(false), false) { shown = rvGlidingRate(reading).toString() } }
        compose.mainClock.advanceTimeByFrame()
    }

    /** One frame after [value] arrives. */
    private fun next(value: Double?) {
        reading = value
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeByFrame()
    }

    @Test
    fun aStoppedTunnelDropsToNothingAtOnce() {
        show()
        compose.mainClock.advanceTimeBy(SETTLE_MS)
        assertEquals(format.liveRate(FAST).toString(), shown)

        next(null)

        assertEquals(format.liveRate(0.0).toString(), shown)
    }

    @Test
    fun aNewSessionStartsFromItsFirstSample() {
        show()
        compose.mainClock.advanceTimeBy(SETTLE_MS)
        next(null)
        compose.mainClock.advanceTimeBy(SETTLE_MS)

        next(SLOW)

        assertEquals(format.liveRate(SLOW).toString(), shown)
    }

    @Test
    fun withinASessionANewSecondGlides() {
        show()
        compose.mainClock.advanceTimeBy(SETTLE_MS)

        next(SLOW)

        assertNotEquals(format.liveRate(SLOW).toString(), shown)
        compose.mainClock.advanceTimeBy(SETTLE_MS)
        assertEquals(format.liveRate(SLOW).toString(), shown)
    }

    private companion object {
        const val FAST = 2_000_000.0
        const val SLOW = 500_000.0

        /** Long enough for the average to reach a rate and the number to arrive (2 s time constant, 0.1 %). */
        const val SETTLE_MS = 30_000L
    }
}
