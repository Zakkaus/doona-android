package dev.doona.android.prototype.rv

import androidx.activity.BackEventCompat
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.TouchInjectionScope
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import dev.doona.android.R
import dev.doona.android.prototype.rv.data.RvData
import dev.doona.android.prototype.rv.nodes.RV_PICKER
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 活動's pull-down to the node picker and back (brief rv-gestures G1, G2), on a clock the test drives: the hero follows
 * the finger 1:1 to the threshold and with resistance past it; short of it, it springs back in about the measured
 * 167 ms; past it, or on a fast fling past the dead zone, the picker opens; the threshold ticks each time it is crossed
 * outward; back, the system's predictive back and a pull down at the picker's top take it back; a touch catches it on
 * its way; at animator scale 0 it settles at once.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rTW-w412dp-h915dp-notnight-xhdpi")
class RvPullTest {
    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val utc = Utc()

    private val taps = RvTaps()
    private lateinit var back: OnBackPressedDispatcher
    private var slop = 0.dp

    @After
    fun animatorScaleBack() = rvAnimatorScale(1f)

    @Test
    fun theHeroFollowsTheFingerThenResists() {
        app()
        val rest = heroTop()
        drag(activity(), SHORT_OF_IT, release = false)
        compose.mainClock.advanceTimeByFrame()
        val short = heroTop() - rest
        // The touch slop is the only travel the hero does not follow.
        assertEquals("1:1 short of the threshold", (SHORT_OF_IT - slop).value, short.value, NEAR)
        drag(activity(), FAR - SHORT_OF_IT, release = false, startAt = null)
        compose.mainClock.advanceTimeByFrame()
        val far = heroTop() - rest
        assertTrue("past the threshold it resists: $far for $FAR", far > RvMotion.pullCommit && far < RvMotion.pullCommit + RvMotion.pullGive)
        compose.onAllNodesWithTag(RV_PICKER).assertCountIs(0, "nothing opens while the finger is down")
        assertEquals(listOf(HapticFeedbackType.GestureThresholdActivate), taps.kinds)
    }

    @Test
    fun aPullPastTheThresholdOpensThePicker() {
        app()
        drag(activity(), FAR)
        compose.mainClock.advanceTimeBy(RvMotion.REVEAL_MS / 2L)
        val height = compose.onRoot().getBoundsInRoot().height
        val half = pickerTop()
        assertTrue("half way the sheet $half is still coming down", half < 0.dp && half > -height)
        compose.mainClock.advanceTimeBy(RvMotion.REVEAL_MS * 2L)
        assertEquals(0f, pickerTop().value, NEAR)
    }

    @Test
    fun aShortPullSpringsBackInAboutTheMeasuredTime() {
        app()
        val rest = heroTop()
        drag(activity(), SHORT_OF_IT)
        compose.mainClock.advanceTimeBy(RvMotion.SNAP_MS / 3L)
        assertTrue("it takes its time", heroTop() - rest > NEAR.dp)
        compose.mainClock.advanceTimeBy(RvMotion.SNAP_MS.toLong())
        assertEquals("back at rest", rest.value, heroTop().value, NEAR)
        compose.onAllNodesWithTag(RV_PICKER).assertCountIs(0, "a pull short of the threshold opens nothing")
        assertEquals("no tick short of the threshold", emptyList<HapticFeedbackType>(), taps.kinds)
    }

    @Test
    fun aFastFlingOpensShortOfTheThreshold() {
        app()
        flick(activity(), FLING, FLING_MS)
        compose.mainClock.advanceTimeBy(RvMotion.REVEAL_MS * 2L)
        assertEquals("a fling past the dead zone opens", 0f, pickerTop().value, NEAR)
        assertEquals("the threshold was never reached", emptyList<HapticFeedbackType>(), taps.kinds)
    }

    @Test
    fun aFlickInsideTheDeadZoneOpensNothing() {
        app()
        flick(activity(), FLICK, FLICK_MS)
        compose.mainClock.advanceTimeBy(RvMotion.REVEAL_MS * 2L)
        compose.onAllNodesWithTag(RV_PICKER).assertCountIs(0, "a flick inside the dead zone is not a pull")
    }

    @Test
    fun theThresholdTicksEachTimeItIsCrossedOutward() {
        app()
        drag(activity(), FAR, release = false)
        compose.mainClock.advanceTimeByFrame()
        drag(activity(), -FAR, release = false, startAt = null)
        compose.mainClock.advanceTimeByFrame()
        drag(activity(), FAR, release = false, startAt = null)
        compose.mainClock.advanceTimeByFrame()
        assertEquals(List(2) { HapticFeedbackType.GestureThresholdActivate }, taps.kinds)
    }

    @Test
    fun backWhileItComesInTurnsItBack() {
        app()
        drag(activity(), FAR)
        compose.mainClock.advanceTimeBy(RvMotion.REVEAL_MS / 4L)
        compose.runOnIdle { back.onBackPressed() }
        compose.mainClock.advanceTimeBy(RvMotion.RETURN_MS * 2L)
        compose.onAllNodesWithTag(RV_PICKER).assertCountIs(0, "back mid-reveal runs it back")
    }

    @Test
    fun aTouchWhileItComesInCatchesIt() {
        app()
        drag(activity(), FAR)
        compose.mainClock.advanceTimeBy(RvMotion.REVEAL_MS / 4L)
        compose.onRoot().performTouchInput { down(center) }
        compose.mainClock.advanceTimeByFrame()
        val caught = pickerTop()
        compose.mainClock.advanceTimeBy(RvMotion.REVEAL_MS / 2L)
        assertEquals("a finger holds it where it was", caught.value, pickerTop().value, NEAR)
        drag(compose.onRoot(), -FAR, startAt = null)
        compose.mainClock.advanceTimeBy(RvMotion.RETURN_MS * 2L)
        compose.onAllNodesWithTag(RV_PICKER).assertCountIs(0, "pushed back up, it closes")
    }

    @Test
    fun aPullDownAtThePickersTopClosesIt() {
        app()
        open()
        val title = compose.onNodeWithText(rvText(R.string.rv_nodes_title)).getUnclippedBoundsInRoot().top
        drag(picker(), FAR, release = false)
        compose.mainClock.advanceTimeByFrame()
        val moved = compose.onNodeWithText(rvText(R.string.rv_nodes_title)).getUnclippedBoundsInRoot().top - title
        assertTrue("the list follows the finger with resistance: $moved", moved > RvMotion.pullCommit && moved < FAR)
        assertEquals("the closing threshold ticks too", List(2) { HapticFeedbackType.GestureThresholdActivate }, taps.kinds)
        compose.onRoot().performTouchInput { up() }
        compose.mainClock.advanceTimeBy(RvMotion.RETURN_MS * 2L)
        compose.onAllNodesWithTag(RV_PICKER).assertCountIs(0, "the picker has closed")
    }

    @Test
    fun predictiveBackMovesThePickerWithTheGesture() {
        app()
        open()
        val height = compose.onRoot().getBoundsInRoot().height
        compose.runOnIdle {
            back.dispatchOnBackStarted(BackEventCompat(EDGE_X, EDGE_Y, 0f, BackEventCompat.EDGE_LEFT))
            back.dispatchOnBackProgressed(BackEventCompat(EDGE_X * 2, EDGE_Y, HALF, BackEventCompat.EDGE_LEFT))
        }
        compose.mainClock.advanceTimeByFrame()
        assertEquals("half the gesture, half the way back", (-height * HALF).value, pickerTop().value, NEAR)
        compose.runOnIdle { back.dispatchOnBackCancelled() }
        compose.mainClock.advanceTimeBy(RvMotion.REVEAL_MS * 2L)
        assertEquals("a cancel opens it again", 0f, pickerTop().value, NEAR)
        compose.runOnIdle {
            back.dispatchOnBackStarted(BackEventCompat(EDGE_X, EDGE_Y, 0f, BackEventCompat.EDGE_LEFT))
            back.dispatchOnBackProgressed(BackEventCompat(EDGE_X * 2, EDGE_Y, HALF, BackEventCompat.EDGE_LEFT))
        }
        compose.mainClock.advanceTimeByFrame()
        compose.runOnIdle { back.onBackPressed() }
        compose.mainClock.advanceTimeBy(RvMotion.RETURN_MS * 2L)
        compose.onAllNodesWithTag(RV_PICKER).assertCountIs(0, "a committed back closes it")
    }

    @Test
    fun atAnimatorScaleZeroItSettlesAtOnce() {
        rvAnimatorScale(0f)
        app()
        val rest = heroTop()
        drag(activity(), SHORT_OF_IT)
        compose.mainClock.advanceTimeByFrame()
        assertEquals("a short pull is back at once", rest.value, heroTop().value, NEAR)
        drag(activity(), FAR, release = false)
        compose.mainClock.advanceTimeByFrame()
        compose.onRoot().performTouchInput { up() }
        compose.mainClock.advanceTimeByFrame()
        assertEquals("a long one opens at once", 0f, pickerTop().value, NEAR)
        compose.runOnIdle { back.onBackPressed() }
        compose.mainClock.advanceTimeByFrame()
        compose.onAllNodesWithTag(RV_PICKER).assertCountIs(0, "back closes it at once")
        assertEquals("the threshold still ticks", HapticFeedbackType.GestureThresholdActivate, taps.kinds.first())
    }

    @Test
    fun closingDoesNotComposeThePickerEachFrame() {
        var compositions = 0
        lateinit var state: RvPullState
        compose.setContent {
            RvTheme(seedColorScheme(Seed.Cyan.argb, false), false) {
                state = rememberRvPullState(open = true)
                // As the sample's picker does, this one takes the motion's close and stretch.
                RvPullDown(RvPullMotion.Sheet, state, activity = { Box(Modifier.fillMaxSize()) }) { motion ->
                    SideEffect { compositions++ }
                    Box(Modifier.fillMaxSize().clickable(onClick = motion.close).graphicsLayer { translationY = motion.stretch() })
                }
            }
        }
        compose.mainClock.autoAdvance = false
        compose.mainClock.advanceTimeByFrame()
        val before = compositions
        compose.runOnIdle { state.close() }
        compose.mainClock.advanceTimeBy(RvMotion.RETURN_MS * 2L)
        assertEquals("closed", 0f, state.shown.value)
        assertTrue("the close moves the picker by its layer, not by composing it (${compositions - before} times)", compositions - before <= 1)
    }

    @Test
    fun aPickerComposedAheadIsHiddenUntilItOpens() {
        var composed = false
        lateinit var state: RvPullState
        compose.setContent {
            RvTheme(seedColorScheme(Seed.Cyan.argb, false), false) {
                state = rememberRvPullState()
                RvPullDown(RvPullMotion.Sheet, state, activity = { Box(Modifier.fillMaxSize()) }) {
                    SideEffect { composed = true }
                    Box(Modifier.fillMaxSize().testTag(SHEET).clickable {})
                }
            }
        }
        compose.runOnIdle { state.warmUp() }
        compose.waitForIdle()
        assertTrue("composed ahead", composed)
        compose.onAllNodesWithTag(SHEET).assertCountIs(0, "and hidden from accessibility and touch")
        compose.runOnIdle { state.reveal() }
        compose.waitForIdle()
        compose.onAllNodesWithTag(SHEET).assertCountIs(1, "revealed, it is there")
    }

    @Test
    fun closingKeepsThePickerComposed() {
        var disposed = 0
        lateinit var state: RvPullState
        compose.setContent {
            RvTheme(seedColorScheme(Seed.Cyan.argb, false), false) {
                state = rememberRvPullState(open = true)
                RvPullDown(RvPullMotion.Sheet, state, activity = { Box(Modifier.fillMaxSize()) }) {
                    DisposableEffect(Unit) { onDispose { disposed++ } }
                    Box(Modifier.fillMaxSize().testTag(SHEET))
                }
            }
        }
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { state.close() }
        compose.mainClock.advanceTimeBy(RvMotion.RETURN_MS * 2L)
        assertEquals("closed", 0f, state.shown.value)
        assertEquals("the close's last frame disposes nothing", 0, disposed)
        compose.onAllNodesWithTag(SHEET).assertCountIs(0, "closed, the picker is hidden")
    }

    @Test
    fun aPullComposesThePickerAheadSoItsRevealComposesNoRows() {
        app()
        drag(activity(), FAR, release = false)
        compose.mainClock.advanceTimeBy(HOLD_MS)
        compose.onAllNodesWithTag(RV_PICKER).assertCountIs(0, "composed while the finger pulls, the picker is hidden")
        activity().performTouchInput { up() }
        compose.mainClock.advanceTimeByFrame()
        assertEquals("the reveal's first frame has every row", RvData.nodes.size, rows())
    }

    @Test
    fun activitysSecondHoldsWhileThePullOrThePickerCoversIt() {
        var second by mutableIntStateOf(0)
        var progress by mutableFloatStateOf(0f)
        var shown = -1
        val look = RvPullLook(null, { progress }, null)
        compose.setContent { shown = rememberShownSecond({ second }, look) }
        compose.runOnIdle { second = 1 }
        compose.runOnIdle { assertEquals("uncovered, the run's second", 1, shown) }
        compose.runOnIdle {
            progress = 0.5f
            second = 2
        }
        compose.runOnIdle { assertEquals("covered, the second it had", 1, shown) }
        compose.runOnIdle { progress = 0f }
        compose.runOnIdle { assertEquals("back, it catches up", 2, shown) }
    }

    private fun rows(): Int = RvData.nodes.sumOf { compose.onAllNodes(hasText(it.name) and hasAnyAncestor(hasTestTag(RV_PICKER))).fetchSemanticsNodes().size }

    private fun app() {
        compose.setContent {
            back = LocalOnBackPressedDispatcherOwner.current!!.onBackPressedDispatcher
            slop = with(LocalDensity.current) { LocalViewConfiguration.current.touchSlop.toDp() }
            CompositionLocalProvider(LocalHapticFeedback provides taps) { RvPreviewApp(RvMotionOptions(), RvSampleStart()) }
        }
        compose.mainClock.autoAdvance = false
    }

    private fun open() {
        drag(activity(), FAR)
        compose.mainClock.advanceTimeBy(RvMotion.REVEAL_MS * 2L)
    }

    private fun activity() = compose.onAllNodes(hasScrollAction()).onFirst()

    private fun picker() = compose.onNode(hasScrollAction() and hasAnyAncestor(hasTestTag(RV_PICKER)))

    private fun heroTop(): Dp = compose.onNodeWithText("${rvText(R.string.backend_connected)} · ${rvText(R.string.rv_mode_rule)}").getUnclippedBoundsInRoot().top

    private fun pickerTop(): Dp = compose.onNodeWithTag(RV_PICKER).getUnclippedBoundsInRoot().top

    /**
     * Drags [node] by [by] (down when positive) in small steps a frame apart, slow enough not to fling; touches down at
     * [startAt] first unless it is null (the finger is already down), and lets go when [release].
     */
    private fun drag(node: SemanticsNodeInteraction, by: Dp, release: Boolean = true, startAt: Dp? = START) {
        node.performTouchInput {
            if (startAt != null) down(Offset(centerX, startAt.toPx()))
            steps(by.toPx() / STEPS, STEPS, FRAME_MS)
            if (release) up()
        }
    }

    /** A quick flick of [by] in four moves [ms] apart. */
    private fun flick(node: SemanticsNodeInteraction, by: Dp, ms: Long) {
        node.performTouchInput {
            down(Offset(centerX, START.toPx()))
            steps(by.toPx() / FLICK_STEPS, FLICK_STEPS, ms)
            up()
        }
    }

    private fun TouchInjectionScope.steps(step: Float, count: Int, ms: Long) = repeat(count) { moveBy(Offset(0f, step), delayMillis = ms) }

    private companion object {
        val START = 300.dp
        val SHORT_OF_IT = 64.dp
        val FAR = 300.dp
        val FLING = 64.dp
        val FLICK = 20.dp
        const val STEPS = 20
        const val FRAME_MS = 16L
        const val SHEET = "sheet"

        /** A finger held at the end of a pull long enough for the picker's rows to come in, a row a frame. */
        const val HOLD_MS = 1_500L
        const val FLICK_STEPS = 4

        /** 64 dp in 40 ms and 20 dp in 16 ms: both faster than [RvMotion.pullFling]. */
        const val FLING_MS = 10L
        const val FLICK_MS = 4L
        const val NEAR = 1.5f
        const val EDGE_X = 10f
        const val EDGE_Y = 900f
        const val HALF = 0.5f
    }
}
