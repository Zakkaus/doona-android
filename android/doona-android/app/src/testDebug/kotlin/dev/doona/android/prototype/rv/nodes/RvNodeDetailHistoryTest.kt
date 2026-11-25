package dev.doona.android.prototype.rv.nodes

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import dev.doona.android.prototype.rv.Utc
import dev.doona.android.prototype.rv.assertCountIs
import dev.doona.android.prototype.rv.data.RvData
import dev.doona.android.prototype.rv.data.rvTestTimes
import dev.doona.android.prototype.rv.rvContent
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 延遲紀錄 on one open page while its node's history changes under it, as the engine's tests come in, and one test. */
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

    /** One test (item 22, the owner's B): its figure over the chart, as with many. */
    @Test
    fun oneTestKeepsTheChart() {
        probes = 1
        show()
        compose.onAllNodes(hasTestTag(RV_LATENCY_DOTS), useUnmergedTree = true).assertCountIs(1, "the chart")
        assertFigure(node.history.first())
    }

    /** One probe against every round's time: the probe is the latest test, so it reads the latest round's time. */
    @Test
    fun oneTestReadsTheLatestTimeOfALongerList() {
        probes = 1
        show(times = { rvTestTimes() })
        assertFigure(node.history.first())
        assertHasText(LATEST)
        assertNoText(OLDEST)
    }

    /** No probe against every round's time: 上次測試 is a dash, not a round the node never had. */
    @Test
    fun noTestReadsNoTimeFromALongerList() {
        probes = 0
        show(times = { rvTestTimes() })
        assertNoText(LATEST)
        assertNoText(OLDEST)
    }

    /** Every test with only the latest time: the page opens, the latest test reads that time and the rest a dash. */
    @Test
    fun aShorterListOfTimesDoesNotCrash() {
        probes = node.history.size
        show(times = { rvTestTimes().takeLast(1) })
        assertFigure(node.history.last())
        assertHasText(LATEST)
    }

    /** [times] is read on each composition, so the times follow [probes] as the history does. */
    private fun show(times: () -> List<Long> = { rvTestTimes().take(probes) }) {
        compose.rvContent(dark = false) {
            RvNodeDetail(node.copy(history = node.history.take(probes)), current = false, times(), onBack = {}, onUse = {}, onTest = {})
        }
        compose.waitForIdle()
    }

    private fun texts(text: String) = compose.onAllNodes(hasText(text, substring = true), useUnmergedTree = true).fetchSemanticsNodes()

    private fun assertHasText(text: String) = assertTrue("the page shows $text", texts(text).isNotEmpty())

    private fun assertNoText(text: String) = assertTrue("the page shows no $text", texts(text).isEmpty())

    private companion object {
        /** The rounds' first and last times in UTC ([rvTestTimes]). */
        const val OLDEST = "06:30"
        const val LATEST = "10:00"
    }

    private fun assertFigure(ms: Int?) {
        val figure = requireNotNull(ms).toString()
        assertTrue("the figure shows $figure", compose.onAllNodes(hasText(figure, substring = true), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
    }
}
