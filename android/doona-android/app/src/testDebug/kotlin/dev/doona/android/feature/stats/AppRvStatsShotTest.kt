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
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import dev.doona.android.R
import dev.doona.android.designsystem.rv.RvChartSize
import dev.doona.android.engine.DayUsage
import dev.doona.android.engine.Engine
import dev.doona.android.engine.EngineException
import dev.doona.android.engine.ErrorCode
import dev.doona.android.engine.Mode
import dev.doona.android.engine.ScoreNetwork
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
import dev.doona.android.prototype.rv.data.RvData
import dev.doona.android.prototype.rv.rvContent
import dev.doona.android.prototype.rv.rvRowRules
import dev.doona.android.prototype.rv.stats.rvStatsScore
import dev.doona.android.testing.drawFully
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.ZoneOffset
import android.graphics.Color as AndroidColor

/**
 * 統計 as the app draws it: an engine, StatsViewModel, the mapper, then the page (goldens `app-rv-stats-*`). With the RV
 * sample engine and the Score card's sample `scoreState` it is the page of `proto-rv-stats-score-*`; with the same engine
 * before the day's first byte, untested and with no subscription, it is the empty state of `proto-rv-stats-empty-a-*`.
 * Each window holds the whole page. The engine's own sample instant, 2026-11-22 10:24 UTC, is the page's now.
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
    fun statsLight() = shot("stats", dark = false) { Page(::scoreEngine) }

    @Test
    @Config(qualifiers = WINDOW)
    fun statsDark() = shot("stats", dark = true) { Page(::scoreEngine) }

    @Test
    @Config(qualifiers = WINDOW_FS200)
    fun statsFs200() = shot("stats", dark = false, fontScale = RV_FS200) { Page(::scoreEngine) }

    @Test
    @Config(qualifiers = WINDOW_EN)
    fun statsEnLight() = shot("stats-en", dark = false) { Page(::scoreEngine) }

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
        assertEquals("a curve over the empty day", 0, curvePixels())
    }

    /** The control of [emptySaysSoAndDrawsNoCurve]: the same count finds the sample day's curve. */
    @Test
    @Config(qualifiers = WINDOW)
    fun aDayWithBytesDrawsItsCurve() {
        compose.rvContent(dark = false) { Page(::scoreEngine) }
        assertTrue("no curve over the sample day", curvePixels() > MIN_CURVE)
    }

    /**
     * Coloured pixels in 已用流量's chart above its bottom band: an empty day keeps its steps, line and live dot on the
     * bottom (the band, as tall as the dot's halo), and the grid and the card are grey, so any hue above it is a curve.
     */
    private fun curvePixels(): Int {
        compose.drawFully()
        val chart = compose.onNode(hasTestTag(RV_SPENT_CHART), useUnmergedTree = true).captureToImage().asAndroidBitmap()
        val band = with(Density(ApplicationProvider.getApplicationContext())) { RvChartSize.liveHalo.toPx() }.toInt()
        var count = 0
        for (y in 0 until chart.height - band) {
            for (x in 0 until chart.width) {
                val c = chart.getPixel(x, y)
                val channels = listOf(AndroidColor.red(c), AndroidColor.green(c), AndroidColor.blue(c))
                if (channels.max() - channels.min() > GREY_SPREAD) count++
            }
        }
        return count
    }

    /** A subscription without quota draws no legend (no `0/0 B`) and no date it runs out (none at the epoch, 1970). */
    @Test
    @Config(qualifiers = WINDOW)
    fun aSubscriptionWithoutQuotaHasNoLegend() {
        val engine = noQuotaEngine()
        compose.rvContent(dark = false) { Page { engine } }
        compose.onNode(hasText(text(R.string.rv_stats_no_quota)), useUnmergedTree = true).assertExists()
        for (sub in engine.subscriptions.value) {
            compose.onAllNodes(hasText(sub.name, substring = true), useUnmergedTree = true).fetchSemanticsNodes().let { assertEquals(sub.name, 0, it.size) }
        }
        compose.onAllNodes(hasText(text(R.string.rv_stats_no_expiry)), useUnmergedTree = true).fetchSemanticsNodes().let { assertEquals(0, it.size) }
        compose.onAllNodes(hasText("1970", substring = true), useUnmergedTree = true).fetchSemanticsNodes().let { assertEquals(0, it.size) }
    }

    /** 測試所有節點 that fails leaves the library's banner at the top: what failed, the engine's reason and 重試 (plan 8). */
    @Test
    @Config(qualifiers = WINDOW)
    fun aFailedTestShowsItsBanner() {
        val failing = object : Engine by scoreEngine() {
            override suspend fun testLatency(groupId: String?): Result<Unit> = Result.failure(EngineException(ErrorCode.Busy))
        }
        compose.rvContent(dark = false) { Page { failing } }
        compose.onAllNodes(hasText(text(R.string.rv_failed_test_latency)), useUnmergedTree = true).fetchSemanticsNodes().let { assertEquals(0, it.size) }
        compose.onNode(hasText(text(R.string.rv_stats_test_all)), useUnmergedTree = true).performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNode(hasText(text(R.string.rv_failed_test_latency)), useUnmergedTree = true).assertExists()
        compose.onNode(hasText(text(R.string.error_busy)), useUnmergedTree = true).assertExists()
        compose.onNode(hasText(text(R.string.rv_retry)), useUnmergedTree = true).assertExists()
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

    /**
     * The sample engine with the Score card's sample (`rvStatsScore`): one group, so the card shows no group name. The
     * sample names nodes by RvData's ids; the engine's catalog has its own, so each id is swapped for the engine's id of
     * the node of the same name.
     */
    private fun scoreEngine(): Engine {
        val engine = rvSampleEngine()
        val byName = engine.catalog.value!!.groups.flatMap { it.members }.associate { it.name to it.id }
        val idOf = RvData.nodes.associate { it.id to byName.getValue(it.name) }
        fun ScoreNetwork.engineIds() = copy(selectedId = selectedId?.let(idOf::getValue), challengers = challengers.map { it.copy(nodeId = it.nodeId?.let(idOf::getValue)) })
        val sample = rvStatsScore().state
        val state = sample.copy(groups = sample.groups.map { it.copy(nodeIds = it.nodeIds.map(idOf::getValue), tcp = it.tcp.engineIds(), udp = it.udp.engineIds()) })
        return object : Engine by engine {
            override suspend fun scoreState(): Result<ScoreState> = Result.success(state)
        }
    }

    /** The sample engine with its subscriptions' quota and expiry unknown. */
    private fun noQuotaEngine(): FakeEngine {
        val subscriptions = RvSampleNodes.subscriptions.map { it.copy(usedBytes = null, totalBytes = null, expiresAtEpochMs = null) }
        return FakeEngine(RvSampleNodes.groups, subscriptions, Status.Stopped, Traffic(), Mode.Rule, FakeData(RvSampleNodes.details, scoreState = ScoreState(0, emptyList())))
    }

    /** The sample engine on a fresh install before the day's first byte: untested nodes, no subscription, no Score yet. */
    private fun quietEngine(): FakeEngine {
        val zero = List(HOURS) { 0L }
        val groups = RvSampleNodes.groups.map { g -> g.copy(members = g.members.map { it.copy(latencyMs = null, failed = false) }) }
        val usage = Usage(DayUsage(SAMPLE_DATE, zero, zero, zero, emptyList()), null, emptyList())
        return FakeEngine(groups, emptyList(), Status.Stopped, Traffic(), Mode.Rule, FakeData(RvSampleNodes.details, usage, TodayTraffic(), scoreState = ScoreState(0, emptyList())))
    }

    private companion object {
        val SHOTS: String = System.getProperty("doona.shots") ?: "src/test/screenshots"
        const val PERCENT = 100
        const val HOURS = 24
        const val SAMPLE_NOW = 1_795_343_040_000L
        const val SAMPLE_DATE = "2026-11-22"
        const val CARD_AND_TILES = 4

        /** A grey's channels differ by a few levels at most; a hue's by far more. */
        const val GREY_SPREAD = 24

        /** The sample day's curve is thousands of pixels; a stray anti-aliased edge is a handful. */
        const val MIN_CURVE = 500

        /** Windows as tall as each page whole, rounded up to a whole dp (measured: 2350.5, 2760, 2382.5, 1948.5, 2101). */
        const val WINDOW = "+h2351dp"
        const val WINDOW_FS200 = "+h2760dp"
        const val WINDOW_EN = "en-w412dp-h2383dp-notnight-xhdpi"
        const val WINDOW_EMPTY = "+h1949dp"
        const val WINDOW_EMPTY_FS200 = "+h2101dp"
    }
}
