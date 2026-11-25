package dev.doona.android.prototype.rv

import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.doona.android.R
import dev.doona.android.designsystem.rv.RvMotion
import dev.doona.android.feature.nodes.RV_PICKER
import dev.doona.android.prototype.rv.data.RvData
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
 * The search page (brief rv-gestures G7), on a clock the test drives: a tap on 活動's search pill pushes the page, whose
 * field the pill becomes, in [RvMotion.SEARCH_MS]; the field takes the cursor (and the keyboard) only once the page has
 * arrived; back returns to 活動 and its pill; at animator scale 0 all of it is at once.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rTW-w412dp-h915dp-notnight-xhdpi")
class RvSearchTest {
    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val utc = Utc()

    private lateinit var back: OnBackPressedDispatcher

    @After
    fun animatorScaleBack() = rvAnimatorScale(1f)

    @Test
    fun theKeyboardComesAfterThePage() {
        app()
        compose.onNodeWithText(rvText(R.string.rv_search_nodes)).performClick()
        compose.mainClock.advanceTimeBy(RvMotion.SEARCH_MS / 2L)
        compose.onAllNodesWithTag(RV_PICKER).assertCountIs(1, "the search page is coming in")
        assertEquals("no cursor while it comes", false, focused())
        compose.mainClock.advanceTimeBy(RvMotion.SEARCH_MS + FRAMES_MS)
        assertEquals("the field has the cursor once it has arrived", true, focused())
        compose.runOnIdle { back.onBackPressed() }
        compose.mainClock.advanceTimeBy(RvMotion.SEARCH_MS * 2L)
        compose.onAllNodesWithTag(RV_PICKER).assertCountIs(0, "back leaves the page")
        compose.onNodeWithText(rvText(R.string.rv_search_nodes)).assertExists()
    }

    @Test
    fun atAnimatorScaleZeroItIsAtOnce() {
        rvAnimatorScale(0f)
        app()
        compose.onNodeWithText(rvText(R.string.rv_search_nodes)).performClick()
        compose.mainClock.advanceTimeBy(RvMotion.SEARCH_MS / 2L)
        assertEquals("half the page's time: at scale 1 nothing is focused yet", true, focused())
    }

    @Test
    fun theFirstFrameComposesAWindowOfRowsAndTheRestFollow() {
        app()
        compose.onNodeWithText(rvText(R.string.rv_search_nodes)).performClick()
        // The frame that first composes the page.
        var first = 0
        repeat(FIRST_FRAMES) { if (first == 0) compose.mainClock.advanceTimeByFrame().also { first = rows() } }
        assertTrue("a window's worth at first, not all ${RvData.nodes.size} ($first)", first in 1 until RvData.nodes.size / 2)
        // A row a frame: every 訂閱 and node row after the window's.
        compose.mainClock.advanceTimeBy(FRAME_MS * (RvData.subscriptions.size + RvData.nodes.size))
        assertEquals("the rest come in over the next frames", RvData.nodes.size, rows())
    }

    /** How many node rows the search page has composed. */
    private fun rows(): Int = RvData.nodes.sumOf { compose.onAllNodes(hasText(it.name) and hasAnyAncestor(hasTestTag(RV_PICKER))).fetchSemanticsNodes().size }

    private fun app() {
        compose.setContent {
            back = LocalOnBackPressedDispatcherOwner.current!!.onBackPressedDispatcher
            RvPreviewApp(RvMotionOptions(), RvSampleStart())
        }
        compose.mainClock.autoAdvance = false
    }

    /** Whether the search page's field has the focus. */
    private fun focused(): Boolean {
        val field = compose.onNode(hasSetTextAction() and hasContentDescription(rvText(R.string.rv_search_nodes))).fetchSemanticsNode()
        return field.config.getOrElse(SemanticsProperties.Focused) { false }
    }

    private companion object {
        const val FRAMES_MS = 50L
        const val FIRST_FRAMES = 5
        const val FRAME_MS = 16L
    }
}
