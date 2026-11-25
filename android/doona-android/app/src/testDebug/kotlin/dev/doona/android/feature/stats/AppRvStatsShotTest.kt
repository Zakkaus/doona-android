package dev.doona.android.feature.stats

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import dev.doona.android.R
import dev.doona.android.engine.DayUsage
import dev.doona.android.engine.Engine
import dev.doona.android.engine.Mode
import dev.doona.android.engine.ScoreState
import dev.doona.android.engine.Status
import dev.doona.android.engine.TodayTraffic
import dev.doona.android.engine.Traffic
import dev.doona.android.engine.Usage
import dev.doona.android.engine.fake.FakeData
import dev.doona.android.engine.fake.FakeEngine
import dev.doona.android.engine.fake.RvSampleNodes
import dev.doona.android.engine.fake.rvSampleEngine
import dev.doona.android.prototype.rv.RV_FS200
import dev.doona.android.prototype.rv.Utc
import dev.doona.android.prototype.rv.assertWhole
import dev.doona.android.prototype.rv.rvContent
import dev.doona.android.prototype.rv.rvRowRules
import dev.doona.android.testing.drawFully
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.ZoneOffset

/**
 * 統計 as the app draws it: an engine, StatsViewModel, the mapper, then the page (goldens `app-rv-stats-*`). With the RV
 * sample engine it is the page of `proto-rv-stats-score-*` (both carry the Score card); with the same engine before the
 * day's first byte, untested and with no quota, it is the empty state of `proto-rv-stats-empty-a-*`. Each window holds the
 * whole page. The engine's own sample instant, 2026-11-22 10:24 UTC, is the page's now.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rTW-w412dp-h915dp-notnight-xhdpi")
class AppRvStatsShotTest {
    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val utc = Utc()

    @Test
    @Config(qualifiers = WINDOW)
    fun statsLight() = shot("stats", dark = false) { Page(::rvSampleEngine) }

    @Test
    @Config(qualifiers = WINDOW)
    fun statsDark() = shot("stats", dark = true) { Page(::rvSampleEngine) }

    @Test
    @Config(qualifiers = WINDOW_FS200)
    fun statsFs200() = shot("stats", dark = false, fontScale = RV_FS200) { Page(::rvSampleEngine) }

    @Test
    @Config(qualifiers = WINDOW_EN)
    fun statsEnLight() = shot("stats-en", dark = false) { Page(::rvSampleEngine) }

    @Test
    @Config(qualifiers = WINDOW_EMPTY)
    fun emptyLight() = shot("stats-empty", dark = false) { Page(::quietEngine) }

    @Test
    @Config(qualifiers = WINDOW_EMPTY)
    fun emptyDark() = shot("stats-empty", dark = true) { Page(::quietEngine) }

    @Test
    @Config(qualifiers = WINDOW_EMPTY_FS200)
    fun emptyFs200() = shot("stats-empty", dark = false, fontScale = RV_FS200) { Page(::quietEngine) }

    /** The empty state keeps its cards, says so in words and draws no made-up curve (owner, 2026-11-24 15:24, q-stats A). */
    @Test
    @Config(qualifiers = WINDOW_EMPTY)
    fun emptySaysSoAndDrawsNoCurve() {
        compose.rvContent(dark = false) { Page(::quietEngine) }
        compose.onAllNodes(hasTestTag(RV_SPENT_CHART), useUnmergedTree = true).fetchSemanticsNodes().let { assertEquals(1, it.size) }
        val nothing = compose.onAllNodes(hasText(text(R.string.rv_stats_nothing_today)), useUnmergedTree = true).fetchSemanticsNodes().size
        // 已用流量's line under its figure, then the tiles' lines on their empty grids: 下載, 上傳 and, peeking, 連線次數.
        assertEquals(CARD_AND_TILES, nothing)
    }

    private fun text(id: Int) = ApplicationProvider.getApplicationContext<Context>().getString(id)

    private fun shot(screen: String, dark: Boolean, fontScale: Float = 1f, content: @Composable () -> Unit) {
        compose.rvContent(dark, fontScale, content = content)
        compose.assertWhole()
        compose.drawFully()
        val variant = when {
            fontScale != 1f -> "fs${(fontScale * PERCENT).toInt()}"
            dark -> "dark"
            else -> "light"
        }
        val name = "app-rv-$screen-$variant"
        compose.rvRowRules(name)
        compose.onRoot().captureToImage().asAndroidBitmap().captureRoboImage("$SHOTS/$name.png")
    }

    /** The route on a view model with the sample's clock and zone. */
    @Composable
    private fun Page(engine: () -> Engine) {
        val vm = remember { StatsViewModel(engine(), clock = { SAMPLE_NOW }, zone = { ZoneOffset.UTC }, work = Dispatchers.Unconfined) }
        StatsRoute(vm, onBack = {}, onOpenReport = {})
    }

    /** The sample engine before the day's first byte: untested nodes, no quota, no Score yet. */
    private fun quietEngine(): FakeEngine {
        val zero = List(HOURS) { 0L }
        val groups = RvSampleNodes.groups.map { g -> g.copy(members = g.members.map { it.copy(latencyMs = null, failed = false) }) }
        val subscriptions = RvSampleNodes.subscriptions.map { it.copy(usedBytes = null, totalBytes = null, expiresAtEpochMs = null) }
        val usage = Usage(DayUsage(SAMPLE_DATE, zero, zero, zero, emptyList()), null, emptyList())
        return FakeEngine(groups, subscriptions, Status.Stopped, Traffic(), Mode.Rule, FakeData(RvSampleNodes.details, usage, TodayTraffic(), scoreState = ScoreState(0, emptyList())))
    }

    private companion object {
        val SHOTS: String = System.getProperty("doona.shots") ?: "src/test/screenshots"
        const val PERCENT = 100
        const val HOURS = 24
        const val SAMPLE_NOW = 1_795_343_040_000L
        const val SAMPLE_DATE = "2026-11-22"
        const val CARD_AND_TILES = 4

        /** Windows as tall as each page whole, rounded up to a whole dp (measured: 2514, 3079, 2563.5, 1930.5, 2124). */
        const val WINDOW = "+h2514dp"
        const val WINDOW_FS200 = "+h3079dp"
        const val WINDOW_EN = "en-w412dp-h2564dp-notnight-xhdpi"
        const val WINDOW_EMPTY = "+h1931dp"
        const val WINDOW_EMPTY_FS200 = "+h2124dp"
    }
}
