package dev.doona.android.prototype.rv

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import com.github.takahirom.roborazzi.captureRoboImage
import dev.doona.android.R
import dev.doona.android.designsystem.rv.RvSize
import dev.doona.android.engine.Status
import dev.doona.android.feature.activity.ActivityAction
import dev.doona.android.feature.activity.ActivityUiState
import dev.doona.android.prototype.rv.data.RvData
import dev.doona.android.prototype.rv.data.rvUnknownPlaceNodes
import dev.doona.android.testing.drawFully
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 活動's map for a main node outside Hong Kong (the owner: 「這個地圖如果我是其他國家的節點呢？」), as frame strips on a
 * clock the test drives, cropped to the hero and the action row like `proto-rv-activity-disconnect-frames-*`:
 * - `proto-rv-activity-connect-frames-<place>-light|dark`: stopped on Hong Kong 02, 連線 held and let go; the connect
 *   brings the node [Place] names (the automatic group's pick), so the map pans to it over the frames after the lift,
 *   the short way round (to the US across the Pacific); then [STOP_AT] ms after the lift it is connected.
 * - `proto-rv-activity-disconnect-frames-<place>-light|dark`: connected on that node, 中斷連線 held, let go, ended.
 *
 * A node whose name gives no country (其他) marks no place: the map pans back to where it rests and stays there.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rTW-w412dp-h915dp-notnight-xhdpi")
class RvActivityPlaceFramesTest {
    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val utc = Utc()

    private var state by mutableStateOf(rvSampleActivity())

    @Test fun connectJapanLight() = connect(Place.JP, dark = false)

    @Test fun connectJapanDark() = connect(Place.JP, dark = true)

    @Test fun connectUsWestLight() = connect(Place.US_WEST, dark = false)

    @Test fun connectUsWestDark() = connect(Place.US_WEST, dark = true)

    @Test fun connectGermanyLight() = connect(Place.DE, dark = false)

    @Test fun connectGermanyDark() = connect(Place.DE, dark = true)

    @Test fun connectUsEastLight() = connect(Place.US_EAST, dark = false)

    @Test fun connectUsEastDark() = connect(Place.US_EAST, dark = true)

    @Test fun connectDubaiLight() = connect(Place.DUBAI, dark = false)

    @Test fun connectDubaiDark() = connect(Place.DUBAI, dark = true)

    @Test fun connectMelbourneLight() = connect(Place.MELBOURNE, dark = false)

    @Test fun connectMelbourneDark() = connect(Place.MELBOURNE, dark = true)

    @Test fun connectOtherLight() = connect(Place.OTHER, dark = false)

    @Test fun connectOtherDark() = connect(Place.OTHER, dark = true)

    @Test fun disconnectJapanLight() = disconnect(Place.JP, dark = false)

    @Test fun disconnectJapanDark() = disconnect(Place.JP, dark = true)

    @Test fun disconnectUsWestLight() = disconnect(Place.US_WEST, dark = false)

    @Test fun disconnectUsWestDark() = disconnect(Place.US_WEST, dark = true)

    @Test fun disconnectGermanyLight() = disconnect(Place.DE, dark = false)

    @Test fun disconnectGermanyDark() = disconnect(Place.DE, dark = true)

    @Test fun disconnectUsEastLight() = disconnect(Place.US_EAST, dark = false)

    @Test fun disconnectUsEastDark() = disconnect(Place.US_EAST, dark = true)

    @Test fun disconnectDubaiLight() = disconnect(Place.DUBAI, dark = false)

    @Test fun disconnectDubaiDark() = disconnect(Place.DUBAI, dark = true)

    @Test fun disconnectMelbourneLight() = disconnect(Place.MELBOURNE, dark = false)

    @Test fun disconnectMelbourneDark() = disconnect(Place.MELBOURNE, dark = true)

    @Test fun disconnectOtherLight() = disconnect(Place.OTHER, dark = false)

    @Test fun disconnectOtherDark() = disconnect(Place.OTHER, dark = true)

    /** Stopped on the sample's Hong Kong node; 連線 starts the connect on [place]'s node, which then ends connected. */
    private fun connect(place: Place, dark: Boolean) {
        state = rvSampleActivity().copy(status = Status.Stopped)
        val connected = place.state()
        show(dark, onConnect = { state = connected.copy(status = Status.Starting) })
        run("connect", place, dark, hasText(rvText(R.string.rv_connect)) and !isSelectable(), stopped = "stopped", end = "connected", ended = connected)
    }

    /** Connected on [place]'s node; 中斷連線 makes it 中斷連線中, which then ends stopped. */
    private fun disconnect(place: Place, dark: Boolean) {
        state = place.state()
        show(dark, onConnect = {})
        run("disconnect", place, dark, hasText(rvText(R.string.rv_disconnect)), stopped = "running", end = "stopped", ended = state.copy(status = Status.Stopped))
    }

    /**
     * Presses the disc of [action] (連線 is also the nav's tab, which is selectable), holds it, lets go, and [STOP_AT] ms
     * later sets [ended], capturing a frame at each time.
     */
    private fun run(kind: String, place: Place, dark: Boolean, action: SemanticsMatcher, stopped: String, end: String, ended: ActivityUiState) {
        compose.mainClock.autoAdvance = false
        val clock = compose.mainClock
        val disc = compose.onNode(action)
        val bottom = disc.fetchSemanticsNode().boundsInRoot.bottom.toInt() + (CROP_BELOW_DP * compose.density.density).toInt()
        val frames = mutableListOf(stopped to capture())
        disc.performTouchInput { down(Offset(centerX, RvSize.actionDisc.toPx() / 2)) }
        clock.advanceTimeBy(HOLD_MS)
        frames += "held $HOLD_MS ms" to capture()
        disc.performTouchInput { up() }
        val up = clock.currentTime
        AFTER_UP.forEach { at ->
            clock.advanceTimeBy(up + at - clock.currentTime)
            frames += "up +${clock.currentTime - up} ms" to capture()
        }
        clock.advanceTimeBy(up + STOP_AT - clock.currentTime)
        state = ended
        val stop = clock.currentTime
        AFTER_STOP.forEach { at ->
            clock.advanceTimeBy(stop + at - clock.currentTime)
            frames += "$end +${clock.currentTime - stop} ms" to capture()
        }
        val theme = if (dark) "dark" else "light"
        rvStrip(frames, bottom, dark).captureRoboImage("$SHOTS/proto-rv-activity-$kind-frames-${place.tag}-$theme.png")
    }

    /** 活動 in [state]; its 中斷連線 makes it 中斷連線中, its 連線 calls [onConnect]. */
    private fun show(dark: Boolean, onConnect: () -> Unit) {
        compose.rvContent(dark) {
            val onAction: (ActivityAction) -> Unit = { if (it == ActivityAction.Disconnect) state = state.copy(status = Status.Stopping) }
            RvActivityScreen(state, onAction, onConnect = onConnect)
        }
        compose.waitForIdle()
    }

    private fun capture(): ImageBitmap {
        compose.drawFully()
        return compose.onRoot().captureToImage()
    }

    /**
     * The strips' nodes, each marked at its city: Tokyo, Los Angeles (across ±180 degrees from Asia), Frankfurt, Ashburn
     * (the US east coast, across the Pacific too), Dubai ([RvData.worldNodes]) and Melbourne, and one whose name gives no
     * country.
     */
    private enum class Place(val tag: String, val nodeId: String) {
        JP("jp", "jp-01"),
        US_WEST("us-west", "us-01"),
        DE("de", "de-01"),
        US_EAST("us-east", "us-07"),
        DUBAI("dubai", "ae-01"),
        MELBOURNE("melbourne", "au-02"),
        OTHER("other", "other-01"),
        ;

        /** Connected on this node, as the sample is on Hong Kong 02. */
        fun state(): ActivityUiState = if (this == OTHER) rvSampleActivity(nodeId, rvUnknownPlaceNodes()) else rvSampleActivity(nodeId, RvData.nodes + RvData.worldNodes)
    }

    private companion object {
        val SHOTS = System.getProperty("doona.shots") ?: "src/test/screenshots"

        /** The disconnect strip's times: past the tap timeout and the press, then the lift and the pan, then the end. */
        const val HOLD_MS = 192L
        val AFTER_UP = listOf(16L, 48L, 96L, 144L, 240L, 400L)
        const val STOP_AT = 640L
        val AFTER_STOP = listOf(48L, 96L, 144L, 400L)
        const val CROP_BELOW_DP = 16f
    }
}
