package dev.doona.android.feature.stats

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import dev.doona.android.R
import dev.doona.android.prototype.rv.RV_FS200
import dev.doona.android.prototype.rv.Utc
import dev.doona.android.prototype.rv.assertWhole
import dev.doona.android.prototype.rv.rvContent
import dev.doona.android.prototype.rv.rvRowRules
import dev.doona.android.testing.drawFully
import dev.doona.android.vpn.ActivityHonkFixtures
import dev.doona.android.vpn.ActivityHonkFixtures.noQuota
import dev.doona.android.vpn.ActivityHonkFixtures.refusingProbes
import dev.doona.android.vpn.ActivityHonkFixtures.scoring
import dev.doona.android.vpn.ActivityHonkFixtures.untested
import dev.doona.android.vpn.FakeHonk
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.DayOfWeek
import java.time.ZoneOffset

/**
 * 統計 as the app draws it: honk (the RV sample as its native API serves it), StatsViewModel, the mapper, then the page
 * (goldens `app-rv-stats-*`). With the sample's HK group on Score it is the page of `proto-rv-stats-score-*` as far as
 * honk reports it: no usage by the day and no Score counters. With no provider and no node probed it is the empty state
 * of `proto-rv-stats-empty-a-*`. Each window holds the whole page. The sample instant, 2026-11-22 10:24 UTC, is the
 * page's now.
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
    fun statsLight() = shot("stats", dark = false) { Page(::scoring) }

    @Test
    @Config(qualifiers = WINDOW)
    fun statsDark() = shot("stats", dark = true) { Page(::scoring) }

    @Test
    @Config(qualifiers = WINDOW_FS200)
    fun statsFs200() = shot("stats", dark = false, fontScale = RV_FS200) { Page(::scoring) }

    @Test
    @Config(qualifiers = WINDOW_EN)
    fun statsEnLight() = shot("stats-en", dark = false) { Page(::scoring) }

    @Test
    @Config(qualifiers = WINDOW_EMPTY)
    fun emptyLight() = shot("stats-empty", dark = false) { Page(::quiet) }

    @Test
    @Config(qualifiers = WINDOW_EMPTY)
    fun emptyDark() = shot("stats-empty", dark = true) { Page(::quiet) }

    @Test
    @Config(qualifiers = WINDOW_EMPTY_FS200)
    fun emptyFs200() = shot("stats-empty", dark = false, fontScale = RV_FS200) { Page(::quiet) }

    @Test
    @Config(qualifiers = WINDOW)
    fun weekLight() = shot("stats-week", dark = false) { Page(::scoring, RvStatsRange.Week) }

    @Test
    @Config(qualifiers = WINDOW)
    fun weekDark() = shot("stats-week", dark = true) { Page(::scoring, RvStatsRange.Week) }

    @Test
    @Config(qualifiers = WINDOW_FS200)
    fun weekFs200() = shot("stats-week", dark = false, fontScale = RV_FS200) { Page(::scoring, RvStatsRange.Week) }

    @Test
    @Config(qualifiers = WINDOW_EN)
    fun weekEnLight() = shot("stats-week-en", dark = false) { Page(::scoring, RvStatsRange.Week) }

    @Test
    @Config(qualifiers = WINDOW)
    fun monthLight() = shot("stats-month", dark = false) { Page(::scoring, RvStatsRange.Month) }

    @Test
    @Config(qualifiers = WINDOW)
    fun monthDark() = shot("stats-month", dark = true) { Page(::scoring, RvStatsRange.Month) }

    @Test
    @Config(qualifiers = WINDOW_FS200)
    fun monthFs200() = shot("stats-month", dark = false, fontScale = RV_FS200) { Page(::scoring, RvStatsRange.Month) }

    @Test
    @Config(qualifiers = WINDOW_EN)
    fun monthEnLight() = shot("stats-month-en", dark = false) { Page(::scoring, RvStatsRange.Month) }

    @Test
    @Config(qualifiers = WINDOW)
    fun subscriptionLight() = shot("stats-sub", dark = false) { Page(::scoring, subscription = PICKED) }

    @Test
    @Config(qualifiers = WINDOW)
    fun subscriptionDark() = shot("stats-sub", dark = true) { Page(::scoring, subscription = PICKED) }

    @Test
    @Config(qualifiers = WINDOW_FS200)
    fun subscriptionFs200() = shot("stats-sub", dark = false, fontScale = RV_FS200) { Page(::scoring, subscription = PICKED) }

    @Test
    @Config(qualifiers = WINDOW_EN)
    fun subscriptionEnLight() = shot("stats-sub-en", dark = false) { Page(::scoring, subscription = PICKED) }

    /**
     * The range chip stays while 已用流量 and the tiles are hidden (q-usage): 本週 and 本月 tag 分流比例 今天, and a picked
     * subscription tags it 全部訂閱 too.
     */
    @Test
    @Config(qualifiers = WINDOW)
    fun longerRangesAndAPickedSubscriptionSaySo() {
        var range by mutableStateOf(RvStatsRange.Today)
        var subscription by mutableStateOf<String?>(null)
        compose.rvContent(dark = false) { Page(::scoring, range, subscription) }
        fun count(id: Int, substring: Boolean = false) = compose.onAllNodes(hasText(text(id), substring), useUnmergedTree = true).fetchSemanticsNodes().size
        assertEquals("the range chip", 1, count(R.string.rv_stats_today))
        range = RvStatsRange.Week
        compose.waitForIdle()
        assertEquals("分流比例's tag", 1, count(R.string.rv_stats_today))
        range = RvStatsRange.Month
        compose.waitForIdle()
        assertEquals("a month compares with nothing", 0, count(R.string.rv_stats_vs_last_week, true) + count(R.string.rv_stats_vs_yesterday, true))
        subscription = PICKED
        compose.waitForIdle()
        // 分流比例's tag joins both scopes, and the chip names the subscription.
        assertEquals(1, count(R.string.rv_stats_all_subscriptions, true))
        compose.onNode(hasText("Lantern Net"), useUnmergedTree = true).assertExists()
    }

    /** honk keeps no usage by the day, so neither 已用流量 nor the tiles are drawn, even empty (owner, 2026-11-26, q-usage). */
    @Test
    @Config(qualifiers = WINDOW_EMPTY)
    fun theUsageCardsAreHidden() {
        compose.rvContent(dark = false) { Page(::quiet) }
        assertEquals(0, compose.onAllNodes(hasTestTag(RV_SPENT_CHART), useUnmergedTree = true).fetchSemanticsNodes().size)
        assertEquals(0, compose.onAllNodes(hasText(text(R.string.rv_stats_nothing_today)), useUnmergedTree = true).fetchSemanticsNodes().size)
        assertEquals(0, compose.onAllNodes(hasText(text(R.string.rv_stats_connections)), useUnmergedTree = true).fetchSemanticsNodes().size)
    }

    /** A subscription without quota draws no legend (no `0/0 B`) and no date it runs out (none at the epoch, 1970). */
    @Test
    @Config(qualifiers = WINDOW)
    fun aSubscriptionWithoutQuotaHasNoLegend() {
        compose.rvContent(dark = false) { Page(honk = { ActivityHonkFixtures.sample().noQuota() }) }
        compose.onNode(hasText(text(R.string.rv_stats_no_quota)), useUnmergedTree = true).assertExists()
        for (name in listOf("Harbor Cloud", "Lantern Net")) {
            compose.onAllNodes(hasText(name, substring = true), useUnmergedTree = true).fetchSemanticsNodes().let { assertEquals(name, 0, it.size) }
        }
        compose.onAllNodes(hasText(text(R.string.rv_stats_no_expiry)), useUnmergedTree = true).fetchSemanticsNodes().let { assertEquals(0, it.size) }
        compose.onAllNodes(hasText("1970", substring = true), useUnmergedTree = true).fetchSemanticsNodes().let { assertEquals(0, it.size) }
    }

    /** 測試所有節點 that fails leaves the library's banner at the top: what failed, honk's reason and 重試 (plan 8). */
    @Test
    @Config(qualifiers = WINDOW)
    fun aFailedTestShowsItsBanner() {
        compose.rvContent(dark = false) { Page(honk = { scoring().refusingProbes() }) }
        compose.onAllNodes(hasText(text(R.string.rv_failed_test_latency)), useUnmergedTree = true).fetchSemanticsNodes().let { assertEquals(0, it.size) }
        compose.onNode(hasText(text(R.string.rv_stats_test_all)), useUnmergedTree = true).performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNode(hasText(text(R.string.rv_failed_test_latency)), useUnmergedTree = true).assertExists()
        compose.onNode(hasText(text(R.string.error_busy)), useUnmergedTree = true).assertExists()
        compose.onNode(hasText(text(R.string.rv_retry)), useUnmergedTree = true).assertExists()
    }

    private fun text(id: Int) = ApplicationProvider.getApplicationContext<Context>().getString(id)

    private fun shot(screen: String, dark: Boolean, fontScale: Float = 1f, before: () -> Unit = {}, content: @Composable () -> Unit) {
        compose.rvContent(dark, fontScale, content = content)
        before()
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

    /**
     * The route on a view model with the sample's clock and zone, weeks from Monday (the sample day is a Sunday, so 本週
     * is a whole week), and the chips at [range] and [subscription]; the profile is read before the page is drawn.
     */
    @Composable
    private fun Page(honk: () -> FakeHonk, range: RvStatsRange = RvStatsRange.Today, subscription: String? = null) {
        val vm = remember {
            StatsViewModel(honk(), clock = { SAMPLE_NOW }, zone = { ZoneOffset.UTC }, firstDay = { DayOfWeek.MONDAY }, work = Dispatchers.Unconfined, io = Dispatchers.Unconfined).apply { load() }
        }
        LaunchedEffect(range, subscription) {
            vm.pickRange(range)
            vm.pickSubscription(subscription)
        }
        StatsRoute(vm, onBack = {}, onOpenReport = {})
    }

    /** The sample with its HK group on Score: one group, so the card shows no group name. */
    private fun scoring(): FakeHonk = ActivityHonkFixtures.sample().scoring()

    /** The sample on a fresh install: no provider, and no node probed yet. */
    private fun quiet(): FakeHonk = ActivityHonkFixtures.sample().untested()

    private companion object {
        val SHOTS: String = System.getProperty("doona.shots") ?: "src/test/screenshots"
        const val PERCENT = 100
        const val PICKED = "s2"

        /** Windows as tall as each page whole, rounded up to a whole dp (measured: 2350.5, 2770, 2386.5, 1948.5, 2107). */
        const val WINDOW = "+h2351dp"
        const val WINDOW_FS200 = "+h2770dp"
        const val WINDOW_EN = "en-w412dp-h2387dp-notnight-xhdpi"
        const val WINDOW_EMPTY = "+h1949dp"
        const val WINDOW_EMPTY_FS200 = "+h2107dp"
    }
}
