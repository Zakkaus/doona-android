package dev.doona.android.prototype.rv.stats

import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import dev.doona.android.R
import dev.doona.android.prototype.rv.RV_FS115
import dev.doona.android.prototype.rv.RV_FS200
import dev.doona.android.prototype.rv.Utc
import dev.doona.android.prototype.rv.assertCountIs
import dev.doona.android.prototype.rv.assertWhole
import dev.doona.android.prototype.rv.rvContent
import dev.doona.android.prototype.rv.rvShot
import dev.doona.android.prototype.rv.rvText
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The 統計 page before the first byte of the day, 已用流量 in the two forms of [RvSpentEmptyLook] for the owner: A, today's
 * chart with its line along zero (`proto-rv-stats-empty-a-*`), and B, the card without the chart (`-b-*`); each light,
 * dark, fs115 and fs200, in the window that holds the whole page, as `proto-rv-stats-empty-v1-*` does. The tiles keep
 * the default [RvStatsEmptyLook.Grid].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rTW-w412dp-h2090dp-notnight-xhdpi")
class RvStatsSpentEmptyShotTest {
    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val utc = Utc()

    @Test fun aLight() = shot(RvSpentEmptyLook.Curve, dark = false)

    @Test fun aDark() = shot(RvSpentEmptyLook.Curve, dark = true)

    @Test fun aFs115() = shot(RvSpentEmptyLook.Curve, dark = false, fontScale = RV_FS115)

    @Test fun aFs200() = shot(RvSpentEmptyLook.Curve, dark = false, fontScale = RV_FS200)

    @Test fun bLight() = shot(RvSpentEmptyLook.Text, dark = false)

    @Test fun bDark() = shot(RvSpentEmptyLook.Text, dark = true)

    @Test fun bFs115() = shot(RvSpentEmptyLook.Text, dark = false, fontScale = RV_FS115)

    @Test fun bFs200() = shot(RvSpentEmptyLook.Text, dark = false, fontScale = RV_FS200)

    @Test
    fun aDrawsTheChartWithItsLine() {
        val lines = quiet(RvSpentEmptyLook.Curve)
        compose.onAllNodes(hasTestTag(RV_SPENT_CHART), useUnmergedTree = true).assertCountIs(1, "A: the chart")
        assertEquals("A: 已用流量's line, then the two tiles'", TILES_AND_CARD, lines)
    }

    @Test
    fun bLeavesTheChartOutAndKeepsTheLine() {
        val lines = quiet(RvSpentEmptyLook.Text)
        compose.onAllNodes(hasTestTag(RV_SPENT_CHART), useUnmergedTree = true).assertCountIs(0, "B: no chart")
        assertEquals("B: 已用流量's line, then the two tiles'", TILES_AND_CARD, lines)
    }

    @Test
    fun bStillDrawsTheChartOnceTheDayHasUsage() {
        compose.rvContent(dark = false) { RvStatsScreen(rvStatsSample(), onBack = {}, spent = RvSpentEmptyLook.Text) }
        compose.onAllNodes(hasTestTag(RV_SPENT_CHART), useUnmergedTree = true).assertCountIs(1, "B with usage: the chart")
    }

    /** Sets the quiet page with [look] and counts the `今天還沒有流量` lines on it. */
    private fun quiet(look: RvSpentEmptyLook): Int {
        compose.rvContent(dark = false) { RvStatsScreen(rvStatsQuiet(), onBack = {}, spent = look) }
        return compose.onAllNodes(hasText(rvText(R.string.rv_stats_nothing_today)), useUnmergedTree = true).fetchSemanticsNodes().size
    }

    private fun shot(look: RvSpentEmptyLook, dark: Boolean, fontScale: Float = 1f) {
        val name = if (look == RvSpentEmptyLook.Curve) "a" else "b"
        compose.rvShot("stats-empty-$name", dark, before = { assertWhole() }, fontScale = fontScale) {
            RvStatsScreen(rvStatsQuiet(), onBack = {}, spent = look)
        }
    }

    private companion object {
        /** 已用流量's line under its figure and the download and upload tiles' lines on their empty grids. */
        const val TILES_AND_CARD = 3
    }
}
