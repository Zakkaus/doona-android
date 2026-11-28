package dev.doona.android.feature.traffic

import android.view.View
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.doona.android.R
import dev.doona.android.designsystem.rv.RV_CHART
import dev.doona.android.designsystem.rv.RV_NO_VALUE
import dev.doona.android.designsystem.rv.RvFormat
import dev.doona.android.designsystem.rv.RvTheme
import dev.doona.android.designsystem.rv.rvSystemScheme
import dev.doona.android.prototype.rv.Utc
import dev.doona.android.prototype.rv.assertCountIs
import dev.doona.android.prototype.rv.rvContent
import dev.doona.android.prototype.rv.rvText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

/** [RvTrafficScreen] on a given state: the scrubbed point, each direction's figures, no session, and what it keeps across a rotation. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "zh-rTW-w412dp-h915dp-notnight-xhdpi")
class TrafficPageTest {
    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val utc = Utc()

    private val format = RvFormat(Locale.TAIWAN)

    private val chart = RvTrafficChart(
        range = RV_TRAFFIC_LIVE,
        down = List(RV_TRAFFIC_POINTS) { 1_000f * (it + 1) },
        up = List(RV_TRAFFIC_POINTS) { 3f * (it + 1) },
        endEpochMs = END,
        stepMs = 1_000,
    )
    private val session = RvTrafficSession(9_100_000.0, 870_000.0, 2_000.0, 200.0, 7_200_000, 720_000, 3_660)
    private val state = RvTrafficState(downRate = 4_000, upRate = 300, sampled = true, chart = chart, session = session)

    /** What TalkBack is given for the node [matcher] finds: its node info's spoken text, and that it reads as one stop. */
    private fun talkBack(view: View, matcher: SemanticsMatcher): String? {
        val info = view.accessibilityNodeProvider.createAccessibilityNodeInfo(compose.onNode(matcher).fetchSemanticsNode().id)!!
        assertEquals("one stop, no parts read after it", 0, info.childCount)
        return info.contentDescription?.toString()
    }

    /** A peak or an average row, which is read as one spoken line. */
    private fun reads(figure: String) = compose.onAllNodes(hasContentDescription(figure, substring = true), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun shows(text: String) = compose.onAllNodesWithText(text, substring = true, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun aScrubbedPointShowsItsRateAndWhenItEnded() {
        var current by mutableStateOf(chart)
        compose.rvContent(dark = false) { RvTrafficScreen(state.copy(chart = current), onRange = {}, onBack = {}, initialScrub = 49) }
        // Point 49 of 60 ends ten seconds before the last: 10:23:50 UTC.
        assertTrue("its time", shows(format.clock(END - 10_000)))
        assertTrue("its rate", shows(format.liveRate(50_000.0).number))
        val download = rvText(R.string.rv_a11y_download).format(format.rate(50_000.0).toString())
        val selected = rvText(R.string.rv_a11y_stat_row).format(format.clock(END - 10_000), download)
        compose.onNode(hasTestTag(RV_CHART) and hasContentDescription(selected), useUnmergedTree = true).assertExists()
        compose.onAllNodesWithText(rvText(R.string.rv_now), useUnmergedTree = true).assertCountIs(0, "not now")
        compose.runOnIdle { current = chart.copy(endEpochMs = END + 1_000, down = chart.down.drop(1) + 0f) }
        compose.onNode(hasTestTag(RV_CHART) and hasContentDescription(selected), useUnmergedTree = true).assertExists()
        compose.runOnIdle { current = chart.copy(endEpochMs = END + 60_000) }
        assertTrue(shows(rvText(R.string.rv_now)))
    }

    @Test
    fun browsingSkipsMissingSamplesAndStopsAtTheEnd() {
        val down = listOf(1_000f, Float.NaN, 3_000f)
        compose.rvContent(dark = false) { RvTrafficScreen(state.copy(chart = chart.copy(down = down)), {}, {}) }
        fun next() = compose.onNodeWithTag(RV_CHART).fetchSemanticsNode().config[SemanticsActions.CustomActions]
            .first { it.label == rvText(R.string.rv_chart_next) }.let { compose.runOnIdle { it.action() } }
        assertTrue(next())
        assertTrue(shows(format.clock(END - 2_000)))
        assertTrue(next())
        assertTrue(shows(format.clock(END)))
        assertEquals(false, next())
        compose.onNodeWithText(rvText(R.string.ui_upload)).performClick()
        assertTrue(shows(rvText(R.string.rv_now)))
    }

    @Test
    fun atRestTheHeroIsTheSecondsRateNow() {
        compose.rvContent(dark = false) { RvTrafficScreen(state, onRange = {}, onBack = {}) }
        assertTrue(shows(rvText(R.string.rv_now)))
        assertTrue(shows(format.liveRate(4_000.0).number))
    }

    @Test
    fun uploadShowsUploadsFigures() {
        compose.rvContent(dark = false) { RvTrafficScreen(state, onRange = {}, onBack = {}) }
        assertTrue("download's peak first", reads(format.rate(9_100_000.0).toString()))
        compose.onNodeWithText(rvText(R.string.ui_upload)).performClick()
        assertTrue("upload's peak", reads(format.rate(870_000.0).toString()))
        assertTrue("upload's average", reads(format.rate(200.0).toString()))
        assertTrue("upload's rate now", shows(format.liveRate(300.0).number))
    }

    @Test
    fun talkBackReadsEachFigureWithItsDirection() {
        lateinit var view: View
        compose.rvContent(dark = false) {
            view = LocalView.current
            RvTrafficScreen(state, onRange = {}, onBack = {})
        }
        val now = format.liveRate(4_000.0)
        assertEquals("the hero", "下載 $now", talkBack(view, hasContentDescription("下載 ${now.number}", substring = true)))
        assertEquals("the peak row, its name kept", "${rvText(R.string.rv_stat_peak)}，下載 ${format.rate(9_100_000.0)}", talkBack(view, hasContentDescription(rvText(R.string.rv_stat_peak), substring = true)))
        compose.onNodeWithText(rvText(R.string.ui_upload)).performClick()
        assertEquals("the average row", "平均，上傳 ${format.rate(200.0)}", talkBack(view, hasContentDescription(rvText(R.string.rv_stat_average), substring = true)))
    }

    @Test
    fun withNoSessionTheCardShowsNoFigures() {
        compose.rvContent(dark = false) { RvTrafficScreen(state.copy(chart = null, session = null), onRange = {}, onBack = {}) }
        compose.onAllNodesWithText(RV_NO_VALUE, useUnmergedTree = true).assertCountIs(5, "a dash for each figure")
    }

    @Test
    fun withNoChartTheGridSaysThereIsNoDataYet() {
        compose.rvContent(dark = false) { RvTrafficScreen(state.copy(chart = null), onRange = {}, onBack = {}) }
        compose.onAllNodesWithText(rvText(R.string.rv_stats_no_data), useUnmergedTree = true).assertCountIs(1, "said on the grid")
    }

    @Test
    fun aChartWithDataSaysNothingOnIt() {
        compose.rvContent(dark = false) { RvTrafficScreen(state, onRange = {}, onBack = {}) }
        compose.onAllNodesWithText(rvText(R.string.rv_stats_no_data), useUnmergedTree = true).assertCountIs(0, "a drawn chart")
    }

    @Test
    fun aChartArrivingAfterNoneOrAnEmptyOneIsDrawn() {
        var shown by mutableStateOf(state.copy(chart = null))
        compose.rvContent(dark = false) { RvTrafficScreen(shown, onRange = {}, onBack = {}) }
        shown = state.copy(chart = chart.copy(down = emptyList(), up = emptyList()))
        compose.waitForIdle()
        shown = state
        compose.waitForIdle()
        // The chart's glide looked for the first sample of the empty list before it and threw.
        assertTrue("still drawn", shows(rvText(R.string.rv_now)))
    }

    @Test
    fun theRangeIsReportedAndKeptAcrossARotation() {
        val reported = mutableListOf<Int>()
        val restore = StateRestorationTester(compose)
        restore.setContent { RvTheme(rvSystemScheme(false), false) { RvTrafficScreen(state, onRange = { reported += it }, onBack = {}) } }
        compose.onNodeWithText(rvText(R.string.rv_range_15m)).performClick()
        compose.onNodeWithText(rvText(R.string.ui_upload)).performClick()
        compose.waitForIdle()
        restore.emulateSavedInstanceStateRestore()
        compose.waitForIdle()
        assertEquals("the first range, the chosen one, and the chosen one again once restored", listOf(0, 2, 2), reported)
        assertTrue("upload kept", reads(format.rate(870_000.0).toString()))
    }

    private companion object {
        const val END = 1_795_343_040_000L
    }
}
