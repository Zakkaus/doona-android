package dev.doona.android.prototype.rv

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onChild
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import com.github.takahirom.roborazzi.captureRoboImage
import dev.doona.android.R
import dev.doona.android.designsystem.rv.Rv
import dev.doona.android.designsystem.rv.RvSize
import dev.doona.android.engine.Status
import dev.doona.android.feature.activity.ActivityAction
import dev.doona.android.testing.drawFully
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

/**
 * 活動's 中斷連線, pressed and run to its end on a clock the test drives. Goldens cannot show motion, so the frame strips
 * `proto-rv-activity-disconnect-frames-light|dark` set fixed clock times of it side by side, cropped to the hero and the
 * action row, each time under its frame: at rest, the disc held, then [AFTER_UP] ms after the finger lifts (the test's
 * `onAction` makes it 中斷連線中 at once), then [AFTER_STOP] ms after it ends ([STOP_AT] ms after the lift).
 * `-frames-instant` is the same at animator scale 0, where every change is immediate.
 *
 * While it connects or disconnects the dot map takes the fair tone, and turns to it rather than snapping; the action keeps
 * its word 中斷連線 until the disconnect has ended.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rTW-w412dp-h915dp-notnight-xhdpi")
class RvActivityDisconnectTest {
    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val utc = Utc()

    private var state by mutableStateOf(rvSampleActivity())
    private var fair = Color.Unspecified
    private var dots = Color.Unspecified
    private var disconnects = 0

    @After
    fun restoreScale() = rvAnimatorScale(1f)

    @Test fun framesLight() = strip("light", dark = false)

    @Test fun framesDark() = strip("dark", dark = true)

    @Test
    fun framesInstant() {
        rvAnimatorScale(0f)
        strip("instant", dark = false)
    }

    @Test fun whileItConnectsTheMapIsFair() = busyMapIsFair(Status.Starting)

    @Test fun whileItDisconnectsTheMapIsFair() = busyMapIsFair(Status.Stopping)

    @Test
    fun theMapTurnsToTheFairToneRatherThanSnapping() {
        show()
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { state = state.copy(status = Status.Stopping) }
        // Each frame until well past the turn: how far the marker is from the accent and from the fair tone.
        val frames = (0 until TURN_FRAMES).map {
            compose.mainClock.advanceTimeByFrame()
            capture().let { it.nearest(dots) to it.nearest(fair) }
        }
        assertTrue("a frame shows the marker between the accent and the fair tone: $frames", frames.any { (accent, fair) -> accent > SAME && fair > SAME })
        assertTrue("it ends in the fair tone: ${frames.last()}", frames.last().second <= SAME)
    }

    @Test
    fun theDisconnectKeepsItsWordUntilItEnds() {
        rvAnimatorScale(0f)
        show()
        compose.onNodeWithText(rvText(R.string.rv_disconnect)).performClick()
        compose.waitForIdle()
        assertEquals(Status.Stopping, state.status)
        compose.onNodeWithText(rvText(R.string.rv_disconnect)).assertExists()
        compose.onAllNodes(connect()).assertCountIs(0, "連線 only once the disconnect has ended")
        state = state.copy(status = Status.Stopped)
        compose.waitForIdle()
        compose.onNode(connect()).assertExists()
    }

    /** While a disconnect is under way 中斷連線 takes no tap: a second one would only start it again. */
    @Test
    fun aDisconnectUnderWayTakesNoTap() {
        rvAnimatorScale(0f)
        state = state.copy(status = Status.Stopping)
        show()
        val action = compose.onNodeWithText(rvText(R.string.rv_disconnect))
        // The disc is the action's button, a node of its own under the label's.
        action.onChild().assertIsNotEnabled()
        action.performTouchInput { click(Offset(centerX, RvSize.actionDisc.toPx() / 2)) }
        compose.waitForIdle()
        assertEquals("disconnects sent while one is under way", 0, disconnects)
    }

    /**
     * When the disconnect ends, the action's name is the word its tap now acts on from the first frame, while the old word
     * is still fading out: a screen reader hears 連線, not both words.
     */
    @Test
    fun theNameTurnsWithTheAction() {
        show()
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { state = state.copy(status = Status.Stopping) }
        compose.mainClock.advanceTimeBy(STOP_AT)
        compose.runOnIdle { state = state.copy(status = Status.Stopped) }
        // Two frames into the fade through, the old word still drawn.
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeByFrame()
        compose.onAllNodes(hasText(rvText(R.string.rv_disconnect))).assertCountIs(0, "中斷連線 is not read once the tap connects")
        compose.onNode(connect()).assertExists()
    }

    private fun busyMapIsFair(status: Status) {
        rvAnimatorScale(0f)
        state = state.copy(status = status)
        show()
        assertTrue("the marker is in the fair tone while $status", capture().nearest(fair) <= SAME)
    }

    /** 活動 in [state], which its 中斷連線 makes 中斷連線中; [fair] and [dots] read from its theme. */
    private fun show(dark: Boolean = false) {
        compose.rvContent(dark) {
            fair = Rv.colors.fair
            dots = Rv.colors.dots
            val onAction: (ActivityAction) -> Unit = {
                if (it == ActivityAction.Disconnect) {
                    disconnects++
                    state = state.copy(status = Status.Stopping)
                }
            }
            RvActivityScreenSample(state, onAction, onConnect = {})
        }
        compose.waitForIdle()
    }

    private fun connect() = hasText(rvText(R.string.rv_connect)) and !isSelectable()

    private fun capture(): ImageBitmap {
        compose.drawFully()
        return compose.onRoot().captureToImage()
    }

    /** Presses 中斷連線's disc, holds it, lets go, and later ends the disconnect, capturing a frame at each time. */
    private fun strip(name: String, dark: Boolean) {
        show(dark)
        compose.mainClock.autoAdvance = false
        val clock = compose.mainClock
        val action = compose.onNodeWithText(rvText(R.string.rv_disconnect))
        val bottom = action.fetchSemanticsNode().boundsInRoot.bottom.toInt() + (CROP_BELOW_DP * compose.density.density).toInt()
        val frames = mutableListOf("running" to capture())
        action.performTouchInput { down(Offset(centerX, RvSize.actionDisc.toPx() / 2)) }
        clock.advanceTimeBy(HOLD_MS)
        frames += "held ${HOLD_MS} ms" to capture()
        action.performTouchInput { up() }
        val up = clock.currentTime
        AFTER_UP.forEach { at ->
            clock.advanceTimeBy(up + at - clock.currentTime)
            frames += "up +${clock.currentTime - up} ms" to capture()
        }
        clock.advanceTimeBy(up + STOP_AT - clock.currentTime)
        state = state.copy(status = Status.Stopped)
        val stop = clock.currentTime
        AFTER_STOP.forEach { at ->
            clock.advanceTimeBy(stop + at - clock.currentTime)
            frames += "stopped +${clock.currentTime - stop} ms" to capture()
        }
        rvStrip(frames, bottom, dark).captureRoboImage("$SHOTS/proto-rv-activity-disconnect-frames-$name.png")
    }

    /** How far, at most per channel, the pixel nearest [target] in the map's centre column (the marker's) lies from it. */
    private fun ImageBitmap.nearest(target: Color): Float {
        val pixels = toPixelMap()
        val x = width / 2
        return (0 until (MAP_DP * compose.density.density).toInt()).minOf { y ->
            val c = pixels[x, y]
            maxOf(abs(c.red - target.red), abs(c.green - target.green), abs(c.blue - target.blue))
        }
    }

    private companion object {
        val SHOTS = System.getProperty("doona.shots") ?: "src/test/screenshots"

        /** Past the tap timeout a scrolling page waits before it shows a press, and past [RvMotion.PRESS_MS] after it. */
        const val HOLD_MS = 192L
        val AFTER_UP = listOf(16L, 48L, 96L, 144L, 240L, 400L)
        const val STOP_AT = 640L
        val AFTER_STOP = listOf(48L, 96L, 144L, 400L)

        /** Frames of 16 ms that hold [RvMotion.HERO_FADE_MS] twice over. */
        const val TURN_FRAMES = 42

        /** The marker's centre is drawn in the map's colour itself; this close counts as that colour. */
        const val SAME = 2f / 255f

        /** The marker lies above this. */
        const val MAP_DP = 200f
        const val CROP_BELOW_DP = 16f
    }
}
