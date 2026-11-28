package dev.doona.android.prototype.rv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.doona.android.designsystem.rv.RvDotMotion
import dev.doona.android.designsystem.rv.RvMotion
import dev.doona.android.designsystem.rv.RvPlace
import dev.doona.android.designsystem.rv.RvTheme
import dev.doona.android.designsystem.rv.rvDotMap
import dev.doona.android.designsystem.rv.rvDotPulse
import dev.doona.android.feature.activity.rvPulse
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
    fun markerAndPulseDoNotCrossTheStatusBarDuringANorthwardPan() {
        val focus = RvPlace.SE.spot!!
        // A frame near the end of the pan from South Africa, with the marker on the status bar edge.
        val moving = RvDotMotion(lat = { focus.lat - 10f }, lon = { focus.lon }, pulse = { 0.5f })
        var marked by mutableStateOf(false)
        compose.setContent {
            Box(
                Modifier.size(100.dp).testTag("map").background(Color.White)
                    .rvDotMap(if (marked) focus else null, Color.Red, 52.dp, fadeTop = 24.dp, live = moving)
                    .rvDotPulse(if (marked) focus else null, Color.Red, 52.dp, 24.dp, moving),
            )
        }
        val plain = compose.onNodeWithTag("map").captureToImage().toPixelMap()
        compose.runOnIdle { marked = true }
        val pixels = compose.onNodeWithTag("map").captureToImage().toPixelMap()
        val top = with(compose.density) { 24.dp.roundToPx() }
        assertTrue((0 until top).all { y -> (0 until pixels.width).all { x -> pixels[x, y] == plain[x, y] } })
        assertTrue((top until top * 2).any { y -> (0 until pixels.width).any { x -> pixels[x, y] != plain[x, y] } })
    }

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
