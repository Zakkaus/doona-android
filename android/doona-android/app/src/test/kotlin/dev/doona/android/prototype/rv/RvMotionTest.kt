package dev.doona.android.prototype.rv

import android.provider.Settings
import androidx.activity.BackEventCompat
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.annotation.StringRes
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.test.SemanticsNodeInteractionCollection
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import dev.doona.android.R
import dev.doona.android.prototype.rv.nodes.RV_PICKER
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The sample's motion, on a clock the test drives (goldens cannot show motion): 活動's pull-down commits past its
 * threshold and goes back short of it; at animator scale 0 a push, the pull-down and the connect sequence finish at once
 * and the haptic ticks still come; a cancelled predictive back leaves the pushed page as it was, a committed one pops;
 * connecting runs 未連線 → 連線中 → 已連線 → 正在中斷連線 → 未連線 with one tick each way, and a failed connect says why.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rTW-w412dp-h915dp-notnight-xhdpi")
class RvMotionTest {
    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val utc = Utc()

    private val taps = Taps()
    private lateinit var back: OnBackPressedDispatcher

    @After
    fun animatorScaleBack() = animatorScale(1f)

    @Test
    fun aPullPastTheThresholdOpensThePicker() {
        app()
        compose.mainClock.autoAdvance = false
        pull(PULL_LONG, release = false)
        compose.mainClock.advanceTimeByFrame()
        val height = compose.onRoot().getBoundsInRoot().height
        // The sheet follows the finger: its bottom edge has come down, its top is still above the screen.
        val following = compose.onNodeWithTag(RV_PICKER).getUnclippedBoundsInRoot()
        assertTrue("the sheet's top ${following.top} should be above the screen", following.top < 0.dp)
        assertTrue("the sheet's bottom ${following.bottom} should follow the finger", following.bottom > PULL_LONG / 2 && following.bottom < height)
        compose.onRoot().performTouchInput { up() }
        compose.mainClock.advanceTimeBy(RvMotion.REVEAL_MS / 2L)
        val revealing = compose.onNodeWithTag(RV_PICKER).getUnclippedBoundsInRoot().top
        assertTrue("half way, the sheet's top $revealing should still be coming down", revealing < 0.dp && revealing > following.top)
        compose.mainClock.advanceTimeBy(RvMotion.REVEAL_MS.toLong())
        assertEquals(0f, compose.onNodeWithTag(RV_PICKER).getUnclippedBoundsInRoot().top.value, EDGE)
    }

    @Test
    fun aShortPullGoesBack() {
        app()
        compose.mainClock.autoAdvance = false
        pull(PULL_SHORT)
        compose.mainClock.advanceTimeByFrame()
        compose.onAllNodesWithTag(RV_PICKER).assertCountIs(1, "the sheet runs back, so it is still drawn just after the release")
        compose.mainClock.advanceTimeBy(RvMotion.RETURN_MS + FRAMES_MS)
        compose.onAllNodesWithTag(RV_PICKER).assertCountIs(0, "a pull short of the threshold returns to 活動")
    }

    @Test
    fun aPushTakesItsTimeAtScaleOne() {
        app()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithContentDescription(text(R.string.rv_stats)).performClick()
        compose.mainClock.advanceTimeBy(FRAMES_MS)
        compose.onAllNodesWithText(text(R.string.rv_search_nodes)).assertCountIs(1, "活動 is still fading out")
        compose.mainClock.advanceTimeBy(RvMotion.PUSH_MS.toLong())
        compose.onAllNodesWithText(text(R.string.rv_search_nodes)).assertCountIs(0, "活動 has gone")
    }

    @Test
    fun animatorScaleZeroMakesEveryTransitionInstant() {
        animatorScale(0f)
        app()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithContentDescription(text(R.string.rv_stats)).performClick()
        compose.mainClock.advanceTimeBy(FRAMES_MS)
        compose.onAllNodesWithText(text(R.string.rv_search_nodes)).assertCountIs(0, "the push is over at once")
        compose.onNodeWithContentDescription(text(R.string.ui_back)).performClick()
        compose.mainClock.advanceTimeBy(FRAMES_MS)
        compose.onAllNodesWithContentDescription(text(R.string.ui_back)).assertCountIs(0, "the pop is over at once")
        pull(PULL_LONG)
        compose.mainClock.advanceTimeBy(FRAMES_MS)
        assertEquals("the pull-down opens at once", 0f, compose.onNodeWithTag(RV_PICKER).getUnclippedBoundsInRoot().top.value, EDGE)
    }

    @Test
    fun animatorScaleZeroStillTicks() {
        animatorScale(0f)
        app(RvSampleStart(connected = false))
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText(text(R.string.rv_connect)).performClick()
        compose.mainClock.advanceTimeBy(RV_CONNECT_MS + FRAMES_MS)
        compose.onAllNodesWithText(caption(R.string.status_starting)).assertCountIs(0, "the old state does not linger")
        compose.onNodeWithText(caption(R.string.backend_connected)).assertExists()
        assertEquals(listOf(HapticFeedbackType.ToggleOn), taps.kinds)
    }

    @Test
    fun cancellingPredictiveBackRestoresThePage() {
        app()
        compose.onNodeWithContentDescription(text(R.string.rv_stats)).performClick()
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        compose.runOnIdle {
            back.dispatchOnBackStarted(BackEventCompat(EDGE_X, EDGE_Y, 0f, BackEventCompat.EDGE_LEFT))
            back.dispatchOnBackProgressed(BackEventCompat(EDGE_X * 2, EDGE_Y, HALF, BackEventCompat.EDGE_LEFT))
        }
        compose.mainClock.advanceTimeBy(FRAMES_MS)
        compose.onAllNodesWithText(text(R.string.rv_search_nodes)).assertCountIs(1, "活動 shows under the page while the finger is down")
        compose.runOnIdle { back.dispatchOnBackCancelled() }
        compose.mainClock.advanceTimeBy(SETTLE_MS)
        compose.onAllNodesWithText(text(R.string.rv_search_nodes)).assertCountIs(0, "the page is back and 活動 is gone")
        compose.onNodeWithContentDescription(text(R.string.ui_back)).assertExists()
    }

    @Test
    fun committingPredictiveBackPops() {
        app()
        compose.onNodeWithContentDescription(text(R.string.rv_stats)).performClick()
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        compose.runOnIdle {
            back.dispatchOnBackStarted(BackEventCompat(EDGE_X, EDGE_Y, 0f, BackEventCompat.EDGE_LEFT))
            back.dispatchOnBackProgressed(BackEventCompat(EDGE_X * 2, EDGE_Y, HALF, BackEventCompat.EDGE_LEFT))
        }
        compose.mainClock.advanceTimeBy(FRAMES_MS)
        compose.runOnIdle { back.onBackPressed() }
        compose.mainClock.advanceTimeBy(SETTLE_MS)
        compose.onAllNodesWithContentDescription(text(R.string.ui_back)).assertCountIs(0, "the page has gone")
        compose.onNodeWithText(text(R.string.rv_search_nodes)).assertExists()
    }

    @Test
    fun connectingRunsThroughItsStatesWithOneTickEachWay() {
        app(RvSampleStart(connected = false))
        compose.mainClock.autoAdvance = false
        compose.mainClock.advanceTimeBy(FRAMES_MS)
        compose.onNodeWithText(caption(R.string.status_stopped)).assertExists()
        compose.onNodeWithText(text(R.string.rv_connect)).performClick()
        compose.mainClock.advanceTimeBy(RvMotion.FADE_MS + FRAMES_MS)
        compose.onNodeWithText(caption(R.string.status_starting)).assertExists()
        compose.onAllNodesWithText(caption(R.string.status_stopped)).assertCountIs(0, "未連線 has faded out")
        assertEquals("no tick until connected", emptyList<HapticFeedbackType>(), taps.kinds)
        compose.mainClock.advanceTimeBy(RV_CONNECT_MS)
        compose.onNodeWithText(caption(R.string.backend_connected)).assertExists()
        assertEquals(listOf(HapticFeedbackType.ToggleOn), taps.kinds)
        compose.onNodeWithText(text(R.string.rv_disconnect)).performClick()
        compose.mainClock.advanceTimeBy(RvMotion.FADE_MS + FRAMES_MS)
        compose.onNodeWithText(caption(R.string.status_stopping)).assertExists()
        compose.mainClock.advanceTimeBy(RV_STOP_MS + RvMotion.FADE_MS)
        compose.onNodeWithText(caption(R.string.status_stopped)).assertExists()
        assertEquals(listOf(HapticFeedbackType.ToggleOn, HapticFeedbackType.ToggleOff), taps.kinds)
    }

    @Test
    fun aFailedConnectSaysWhyWithoutATick() {
        app(RvSampleStart(connected = false, fails = true))
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText(text(R.string.rv_connect)).performClick()
        compose.mainClock.advanceTimeBy(RV_CONNECT_MS + RvMotion.FADE_MS + FRAMES_MS)
        compose.onNodeWithText(caption(R.string.status_failed_start)).assertExists()
        assertEquals(emptyList<HapticFeedbackType>(), taps.kinds)
    }

    private fun app(start: RvSampleStart = RvSampleStart()) = compose.setContent {
        back = LocalOnBackPressedDispatcherOwner.current!!.onBackPressedDispatcher
        CompositionLocalProvider(LocalHapticFeedback provides taps) { RvPreviewApp(RvMotionOptions(), start) }
    }

    /** Drags 活動 down by [by] in small steps a frame apart (slow, so no fling), and lets go unless [release] is false. */
    private fun pull(by: Dp, release: Boolean = true) {
        compose.onAllNodes(hasScrollAction()).onFirst().performTouchInput {
            val step = by.toPx() / PULL_STEPS
            down(Offset(centerX, PULL_FROM.toPx()))
            repeat(PULL_STEPS) { moveBy(Offset(0f, step), delayMillis = FRAME_MS) }
            if (release) up()
        }
    }

    private fun animatorScale(scale: Float) {
        Settings.Global.putFloat(RuntimeEnvironment.getApplication().contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, scale)
    }

    private fun text(@StringRes id: Int) = RuntimeEnvironment.getApplication().getString(id)

    /** 活動's caption in a state: the state, then the mode (規則). */
    private fun caption(@StringRes state: Int) = "${text(state)} · ${text(R.string.rv_mode_rule)}"

    private fun SemanticsNodeInteractionCollection.assertCountIs(count: Int, why: String) =
        assertEquals(why, count, fetchSemanticsNodes().size)

    /** Records every haptic tick the screen asks for. */
    private class Taps : HapticFeedback {
        val kinds = mutableListOf<HapticFeedbackType>()

        override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
            kinds += hapticFeedbackType
        }
    }

    private companion object {
        val PULL_LONG = 300.dp
        val PULL_SHORT = 48.dp
        val PULL_FROM = 300.dp
        const val PULL_STEPS = 20
        const val FRAME_MS = 16L
        const val FRAMES_MS = 50L
        const val SETTLE_MS = 600L
        const val EDGE = 0.5f
        const val EDGE_X = 10f
        const val EDGE_Y = 900f
        const val HALF = 0.5f
    }
}
