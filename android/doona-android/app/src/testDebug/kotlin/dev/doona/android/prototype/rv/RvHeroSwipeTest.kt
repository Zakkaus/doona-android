package dev.doona.android.prototype.rv

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.TouchInjectionScope
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.doona.android.R
import dev.doona.android.designsystem.rv.RvHeroSwipe
import dev.doona.android.designsystem.rv.RvMotion
import dev.doona.android.designsystem.rv.SEPARATOR
import dev.doona.android.feature.nodes.RV_PICKER
import dev.doona.android.prototype.rv.data.RvData
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
 * 活動's hero swipe (brief rv-gestures H1), on a clock the test drives: with the option on, a slow drag past half the
 * width turns the hero to 主節點 and a short one springs back; a fast short fling turns it; a vertical drag still scrolls
 * 活動 and pulls the picker down; the dots and TalkBack show the other view; at animator scale 0 it settles at once.
 * With the option off a horizontal drag changes nothing.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rTW-w412dp-h915dp-notnight-xhdpi")
class RvHeroSwipeTest {
    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val utc = Utc()

    private val taps = RvTaps()
    private val traffic = rvText(R.string.backend_connected) + SEPARATOR + rvText(R.string.rv_mode_rule)
    private val mainNode = rvText(R.string.rv_main_node) + SEPARATOR + rvText(RvData.current.place.label)

    @After
    fun animatorScaleBack() = rvAnimatorScale(1f)

    @Test
    fun aSlowDragPastHalfTheWidthTurnsToTheMainNode() {
        app()
        swipe(-FAR, STEPS)
        compose.mainClock.advanceTimeBy(RvMotion.SLIDE_MS * 3L)
        assertEquals("the main node's caption is centred", middle().value, centreX(mainNode).value, NEAR)
        compose.onAllNodesWithText(traffic).assertCountIs(0, "the traffic view has gone")
        assertEquals("a view change is no commit: no haptic", emptyList<HapticFeedbackType>(), taps.kinds)
        swipe(FAR, STEPS, from = mainNode)
        compose.mainClock.advanceTimeBy(RvMotion.SLIDE_MS * 3L)
        assertEquals("and back", middle().value, centreX(traffic).value, NEAR)
    }

    @Test
    fun theHeroFollowsTheFingerAndAShortDragSpringsBack() {
        app()
        val rest = centreX(traffic)
        swipe(-SHORT, STEPS, release = false)
        compose.mainClock.advanceTimeByFrame()
        assertTrue("it follows the finger: ${rest - centreX(traffic)}", rest - centreX(traffic) > SHORT / 2)
        compose.onRoot().performTouchInput { up() }
        compose.mainClock.advanceTimeBy(RvMotion.SNAP_MS / 3L)
        assertTrue("it takes its time", rest - centreX(traffic) > NEAR.dp)
        compose.mainClock.advanceTimeBy(RvMotion.SNAP_MS * 3L)
        assertEquals("back at rest", rest.value, centreX(traffic).value, NEAR)
        compose.onAllNodesWithText(mainNode).assertCountIs(0, "a short drag shows no other view")
    }

    @Test
    fun beforeTheFirstViewItResists() {
        app()
        val rest = centreX(traffic)
        swipe(FAR, STEPS, release = false)
        compose.mainClock.advanceTimeByFrame()
        val moved = centreX(traffic) - rest
        assertTrue("before the first view it rubber-bands: $moved for $FAR", moved > 0.dp && moved < RvMotion.pullGive)
        compose.onRoot().performTouchInput { up() }
        compose.mainClock.advanceTimeBy(RvMotion.SNAP_MS * 3L)
        assertEquals("and springs back", rest.value, centreX(traffic).value, NEAR)
    }

    @Test
    fun aFastShortFlingTurnsIt() {
        app()
        swipe(-SHORT, FLICK_STEPS, FLICK_MS)
        compose.mainClock.advanceTimeBy(RvMotion.SLIDE_MS * 3L)
        assertEquals("a fling faster than the pull's turns it", middle().value, centreX(mainNode).value, NEAR)
    }

    @Test
    fun aVerticalDragOnTheHeroStillScrolls() {
        app()
        val top = compose.onNodeWithText(traffic).getUnclippedBoundsInRoot().top
        drag(-SHORT)
        compose.mainClock.advanceTimeBy(RvMotion.SNAP_MS * 3L)
        val scrolled = top - compose.onNodeWithText(traffic).getUnclippedBoundsInRoot().top
        assertTrue("活動 scrolled up: $scrolled", scrolled > SHORT / 2)
        compose.onAllNodesWithText(mainNode).assertCountIs(0, "the hero did not turn")
    }

    @Test
    fun aPullDownOnTheHeroStillOpensThePicker() {
        app()
        drag(FAR)
        compose.mainClock.advanceTimeBy(RvMotion.REVEAL_MS * 2L)
        assertEquals("the picker is open", 0f, compose.onNodeWithTag(RV_PICKER).getUnclippedBoundsInRoot().top.value, NEAR)
        compose.onAllNodesWithText(mainNode).assertCountIs(0, "the hero did not turn")
    }

    @Test
    fun theDotsAndTalkBackShowTheOtherView() {
        app()
        compose.onNode(hasContentDescription(rvText(R.string.rv_main_node)) and isSelectable()).performClick()
        compose.mainClock.advanceTimeBy(RvMotion.SLIDE_MS * 3L)
        assertEquals("a tap on the second dot", middle().value, centreX(mainNode).value, NEAR)
        val hero = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.CustomActions) and hasAnyDescendant(hasText(mainNode)))
        val actions = hero.fetchSemanticsNode().config[SemanticsActions.CustomActions]
        val back = rvText(R.string.rv_hero_all_traffic)
        assertEquals("TalkBack names the other view", listOf(RuntimeEnvironment.getApplication().getString(R.string.rv_hero_show, back)), actions.map { it.label })
        compose.runOnIdle { actions.first().action() }
        compose.mainClock.advanceTimeBy(RvMotion.SLIDE_MS * 3L)
        assertEquals("its action shows it", middle().value, centreX(traffic).value, NEAR)
    }

    @Test
    fun atAnimatorScaleZeroItSettlesAtOnce() {
        rvAnimatorScale(0f)
        app()
        swipe(-FAR, STEPS)
        compose.mainClock.advanceTimeByFrame()
        assertEquals("a long drag lands at once", middle().value, centreX(mainNode).value, NEAR)
        compose.onNode(hasContentDescription(rvText(R.string.rv_hero_all_traffic)) and isSelectable()).performClick()
        compose.mainClock.advanceTimeByFrame()
        assertEquals("a dot's tap too", middle().value, centreX(traffic).value, NEAR)
    }

    @Test
    fun withTheOptionOffAHorizontalDragChangesNothing() {
        app(RvHeroSwipe.Off)
        val rest = compose.onNodeWithText(traffic).getUnclippedBoundsInRoot()
        swipe(-FAR, STEPS)
        compose.mainClock.advanceTimeBy(RvMotion.SLIDE_MS * 3L)
        assertEquals(rest, compose.onNodeWithText(traffic).getUnclippedBoundsInRoot())
        compose.onAllNodesWithText(mainNode).assertCountIs(0, "no second view")
        compose.onAllNodes(isSelectable() and hasContentDescription(rvText(R.string.rv_main_node))).assertCountIs(0, "no dots")
    }

    private fun app(hero: RvHeroSwipe = RvHeroSwipe.On) {
        compose.setContent {
            CompositionLocalProvider(LocalHapticFeedback provides taps) { RvPreviewApp(RvMotionOptions(hero = hero), RvSampleStart()) }
        }
        compose.mainClock.autoAdvance = false
    }

    private fun middle(): Dp = compose.onRoot().getBoundsInRoot().let { (it.left + it.right) / 2 }

    private fun centreX(text: String): Dp = compose.onNodeWithText(text).getUnclippedBoundsInRoot().let { (it.left + it.right) / 2 }

    /** A horizontal drag on the hero by [by] (right when positive) in [count] moves [ms] apart, from the caption [from]. */
    private fun swipe(by: Dp, count: Int, ms: Long = FRAME_MS, release: Boolean = true, from: String = traffic) {
        compose.onNodeWithText(from).performTouchInput {
            down(center)
            steps(Offset(by.toPx() / count, 0f), count, ms)
            if (release) up()
        }
    }

    /** A slow vertical drag from the traffic caption by [by] (down when positive). */
    private fun drag(by: Dp) {
        compose.onNodeWithText(traffic).performTouchInput {
            down(center)
            steps(Offset(0f, by.toPx() / STEPS), STEPS, FRAME_MS)
            up()
        }
    }

    private fun TouchInjectionScope.steps(step: Offset, count: Int, ms: Long) = repeat(count) { moveBy(step, delayMillis = ms) }

    private companion object {
        /** Past half of the 412 dp hero, and a quarter of it. */
        val FAR = 300.dp
        val SHORT = 100.dp

        /** 300 dp in 40 moves a frame apart: about 470 dp/s, slower than [RvMotion.pullFling]. */
        const val STEPS = 40
        const val FRAME_MS = 16L

        /** 100 dp in 4 moves 4 ms apart: far faster than [RvMotion.pullFling]. */
        const val FLICK_STEPS = 4
        const val FLICK_MS = 4L
        const val NEAR = 1.5f
    }
}
