package dev.doona.android.prototype.rv.nodes

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.performClick
import dev.doona.android.R
import dev.doona.android.prototype.rv.Utc
import dev.doona.android.prototype.rv.assertCountIs
import dev.doona.android.prototype.rv.data.RvData
import dev.doona.android.prototype.rv.data.rvTestTimes
import dev.doona.android.prototype.rv.rvContent
import dev.doona.android.prototype.rv.rvText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 延遲紀錄 on one open page while its node's history changes under it, as the engine's tests come in, and one test's ways. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rTW-w412dp-h915dp-notnight-xhdpi")
class RvNodeDetailHistoryTest {
    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val utc = Utc()

    private val node = RvData.nodes.first { it.id == RV_NODES_DETAIL }
    private var probes by mutableIntStateOf(0)
    private var oneTest by mutableStateOf(RvOneTest.Centred)

    /** No test, then the first one, on the same page: the figure becomes that test's, not a pick from before it. */
    @Test
    fun theFirstTestOnTheOpenPageIsShown() {
        show()
        probes = 1
        compose.waitForIdle()
        assertFigure(node.history.first())
    }

    /** The latest test while none is picked, and again when the history shrinks under a pick it no longer has. */
    @Test
    fun aPickTheHistoryNoLongerHasFallsBackToTheLatest() {
        probes = node.history.size
        show()
        assertFigure(node.history.last())
        probes = 1
        compose.waitForIdle()
        assertFigure(node.history.first())
    }

    /** A (item 9): one test draws no chart, but its figure, the line that a second test draws the trend, and 測試延遲. */
    @Test
    fun oneTestAsAFigureDrawsNoChart() {
        var tests = 0
        probes = 1
        show(RvOneTest.Figure) { tests++ }
        compose.onAllNodes(hasTestTag(RV_LATENCY_DOTS), useUnmergedTree = true).assertCountIs(0, "no chart")
        assertFigure(node.history.first())
        compose.onAllNodes(hasText(rvText(R.string.rv_nodes_trend_next))).assertCountIs(1, "the line about the trend")
        compose.onNode(hasText(rvText(R.string.rv_test_latency)) and hasClickAction()).performClick()
        assertEquals("測試延遲 in the card tests the node", 1, tests)
        // The second test draws the chart, and the line and the card's action go.
        probes = 2
        compose.waitForIdle()
        compose.onAllNodes(hasTestTag(RV_LATENCY_DOTS), useUnmergedTree = true).assertCountIs(1, "the chart with two tests")
        compose.onAllNodes(hasText(rvText(R.string.rv_nodes_trend_next))).assertCountIs(0, "no line with two tests")
    }

    /** B (item 9), and the approved centred dot: one test keeps the chart under its figure, with no line or card action. */
    @Test
    fun oneTestOnAChartKeepsTheChart() {
        probes = 1
        show(RvOneTest.Latest)
        assertChart()
        oneTest = RvOneTest.Centred
        compose.waitForIdle()
        assertChart()
    }

    private fun show(variant: RvOneTest = RvOneTest.Centred, onTest: () -> Unit = {}) {
        oneTest = variant
        compose.rvContent(dark = false) {
            RvNodeDetail(node.copy(history = node.history.take(probes)), current = false, rvTestTimes().take(probes), onBack = {}, onUse = {}, onTest = onTest, oneTest = oneTest)
        }
        compose.waitForIdle()
    }

    private fun assertChart() {
        compose.onAllNodes(hasTestTag(RV_LATENCY_DOTS), useUnmergedTree = true).assertCountIs(1, "$oneTest: the chart")
        assertFigure(node.history.first())
        compose.onAllNodes(hasText(rvText(R.string.rv_nodes_trend_next))).assertCountIs(0, "$oneTest: no line")
        compose.onAllNodes(hasText(rvText(R.string.rv_test_latency)) and hasClickAction()).assertCountIs(0, "$oneTest: no card action")
    }

    private fun assertFigure(ms: Int?) {
        val figure = requireNotNull(ms).toString()
        assertTrue("the figure shows $figure", compose.onAllNodes(hasText(figure, substring = true), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
    }
}
