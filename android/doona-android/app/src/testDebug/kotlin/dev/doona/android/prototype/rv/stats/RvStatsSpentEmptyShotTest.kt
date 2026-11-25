package dev.doona.android.prototype.rv.stats

import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import dev.doona.android.R
import dev.doona.android.feature.stats.RV_SPENT_CHART
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
 * The 統計 page before the first byte of the day: 已用流量 draws its chart with the line along zero up to now (the owner's
 * pick A of owner-fix2 item 13), light, dark, fs115 and fs200 (`proto-rv-stats-empty-a-*`), in the window that holds the
 * whole page, as `proto-rv-stats-empty-v1-*` does. The tiles keep the default [RvStatsEmptyLook.Grid].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rTW-w412dp-h2090dp-notnight-xhdpi")
class RvStatsSpentEmptyShotTest {
    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val utc = Utc()

    @Test fun aLight() = shot(dark = false)

    @Test fun aDark() = shot(dark = true)

    @Test fun aFs115() = shot(dark = false, fontScale = RV_FS115)

    @Test fun aFs200() = shot(dark = false, fontScale = RV_FS200)

    @Test
    fun aDrawsTheChartWithItsLine() {
        compose.rvContent(dark = false) { RvStatsScreenSample(rvStatsQuiet()) }
        compose.onAllNodes(hasTestTag(RV_SPENT_CHART), useUnmergedTree = true).assertCountIs(1, "the chart")
        val lines = compose.onAllNodes(hasText(rvText(R.string.rv_stats_nothing_today)), useUnmergedTree = true).fetchSemanticsNodes().size
        assertEquals("已用流量's line, then the two tiles'", TILES_AND_CARD, lines)
    }

    private fun shot(dark: Boolean, fontScale: Float = 1f) {
        compose.rvShot("stats-empty-a", dark, before = { assertWhole() }, fontScale = fontScale) {
            RvStatsScreenSample(rvStatsQuiet())
        }
    }

    private companion object {
        /** 已用流量's line under its figure and the download and upload tiles' lines on their empty grids. */
        const val TILES_AND_CARD = 3
    }
}
