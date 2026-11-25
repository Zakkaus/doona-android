package dev.doona.android.prototype.rv

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.doona.android.designsystem.rv.RvMotion
import dev.doona.android.designsystem.rv.RvTheme
import dev.doona.android.testing.Seed
import dev.doona.android.testing.seedColorScheme
import dev.doona.android.testing.setDoonaContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The map's pulse runs while connected and not resting, and stands still while resting. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rTW-w412dp-h915dp-notnight-xxhdpi")
class RvPulseTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun theRingMovesOnlyWhileNotResting() {
        rvAnimatorScale(1f)
        var resting by mutableStateOf(true)
        var phase: () -> Float = { -1f }
        compose.mainClock.autoAdvance = false
        compose.setDoonaContent { RvTheme(seedColorScheme(Seed.Cyan.argb, false), false) { phase = rvPulse(true) { resting } } }
        compose.mainClock.advanceTimeBy(RvMotion.PULSE_MS.toLong())
        assertEquals("resting, the ring stands at 0", 0f, phase(), 0f)
        compose.runOnUiThread {
            resting = false
            Snapshot.sendApplyNotifications()
        }
        compose.mainClock.advanceTimeByFrame()
        var seen = 0f
        repeat(FRAMES) {
            compose.mainClock.advanceTimeByFrame()
            seen = maxOf(seen, phase())
        }
        assertTrue("not resting, the ring moves: $seen", seen > 0f)
        compose.runOnUiThread {
            resting = true
            Snapshot.sendApplyNotifications()
        }
        compose.mainClock.advanceTimeByFrame()
        assertEquals("resting again, it stands at 0", 0f, phase(), 0f)
    }

    private companion object {
        const val FRAMES = 20
    }
}
