package dev.doona.android.prototype.rv

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.doona.android.designsystem.rv.LocalFrameInsets
import dev.doona.android.designsystem.rv.RvTheme
import dev.doona.android.feature.model.RvPolicyState
import dev.doona.android.feature.model.RvRouteMode
import dev.doona.android.feature.report.RvRedaction
import dev.doona.android.feature.report.RvReportBuild
import dev.doona.android.prototype.rv.connections.RvConnectionsPage
import dev.doona.android.prototype.rv.data.RvData
import dev.doona.android.prototype.rv.data.rvTestTimes
import dev.doona.android.prototype.rv.nodes.RvNodeDetail
import dev.doona.android.prototype.rv.nodes.RvNodesPage
import dev.doona.android.prototype.rv.nodes.sampleNodesPage
import dev.doona.android.prototype.rv.policy.RvPolicyData
import dev.doona.android.prototype.rv.policy.RvPolicyGroupPage
import dev.doona.android.prototype.rv.policy.RvPolicyScreen
import dev.doona.android.prototype.rv.policy.RvPolicyScreenSample
import dev.doona.android.prototype.rv.policy.RvRulesPage
import dev.doona.android.prototype.rv.policy.RvServiceRulesPage
import dev.doona.android.prototype.rv.settings.RvAboutScreen
import dev.doona.android.prototype.rv.settings.RvLanguage
import dev.doona.android.prototype.rv.settings.RvLanguageScreen
import dev.doona.android.prototype.rv.settings.RvLogLevel
import dev.doona.android.prototype.rv.settings.RvLogLevelScreen
import dev.doona.android.prototype.rv.settings.RvPerAppActions
import dev.doona.android.prototype.rv.settings.RvPerAppScreen
import dev.doona.android.prototype.rv.settings.RvReportScreen
import dev.doona.android.prototype.rv.settings.RvSettingsScreen
import dev.doona.android.prototype.rv.settings.RvSettingsScreenSample
import dev.doona.android.prototype.rv.settings.rvPerAppLookSample
import dev.doona.android.prototype.rv.settings.rvSampleReport
import dev.doona.android.prototype.rv.settings.rvSampleSettings
import dev.doona.android.prototype.rv.settings.rvSampleVersions
import dev.doona.android.prototype.rv.states.RvActivityNotice
import dev.doona.android.prototype.rv.states.rvActivityBanner
import dev.doona.android.prototype.rv.states.rvNoticeActivity
import dev.doona.android.prototype.rv.stats.RvStatsScreen
import dev.doona.android.prototype.rv.stats.rvStatsSample
import dev.doona.android.prototype.rv.subs.RvSubDetail
import dev.doona.android.prototype.rv.subs.RvSubDetailSample
import dev.doona.android.prototype.rv.subs.RvSubDetailState
import dev.doona.android.prototype.rv.subs.RvSubsActions
import dev.doona.android.prototype.rv.subs.RvSubsPage
import dev.doona.android.prototype.rv.subs.RvSubsState
import dev.doona.android.prototype.rv.subs.rvSubsStateSample
import dev.doona.android.testing.GestureBar
import dev.doona.android.testing.StatusBar
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Every page's one end rule (owner-fix2 item 8): scrolled to its end, under the gesture handle's 24 dp strip and under a
 * 48 dp three-button bar, the page's last line ends above the navigation bar, and above what stands over the page at
 * the window's foot: the floating tab bar on a tab, a pinned button (訂閱's 新增訂閱, 依 App's 儲存), a page's floating
 * actions (節點詳情). 活動 in each of its states, 策略 in each mode, 連線, 設定, and the pushed pages.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rTW-w412dp-h915dp-notnight-xhdpi")
class RvPageEndTest {
    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val utc = Utc()

    @Test
    fun activityEndsAboveTheNavInEveryState() = endsClear(
        listOf(
            Page("活動") { RvActivityScreenSample(rvSampleActivity(), onAction = {}, onConnect = {}) },
            Page("活動 loading") { RvActivityScreenSample(rvSampleActivity(), onAction = {}, onConnect = {}, loading = true) },
            Page("活動 direct") { RvActivityScreenSample(rvSampleActivity(), onAction = {}, onConnect = {}, mode = RvRouteMode.Direct) },
        ) + RvActivityNotice.entries.map { notice ->
            Page("活動 ${notice.route}") {
                RvActivityScreenSample(
                    rvNoticeActivity(notice),
                    onAction = {},
                    onConnect = {},
                    mode = if (notice == RvActivityNotice.StartNoNodes) RvRouteMode.Global else RvRouteMode.Rule,
                    banner = rvActivityBanner(notice) {},
                    noSubscription = { }.takeIf { notice == RvActivityNotice.NoSubscription },
                )
            }
        },
    )

    @Test
    fun policyEndsAboveTheNavInEveryMode() = endsClear(RvRouteMode.entries.map { mode -> Page("策略 $mode") { RvPolicyScreenSample(RvPolicyState(mode = mode), onChange = {}) } })

    @Test
    fun connectionsAndSettingsEndAboveTheNav() = endsClear(
        listOf(
            Page("連線") { RvConnectionsPage(RvPolicyData.connections) },
            Page("連線 disconnected") { RvConnectionsPage(RvPolicyData.connections, connected = false) },
            Page("設定") { RvSettingsScreenSample(rvSampleSettings(), onChange = {}, onOpenTheme = {}) },
        ),
    )

    @Test
    fun pushedPagesEndAboveTheNavigationBar() = endsClear(
        listOf(
            Page("節點") { RvNodesPage(sampleNodesPage(), onBack = {}, onFilter = {}, onOpen = {}, onAllNodes = {}) },
            Page("統計") { RvStatsScreen(rvStatsSample(), onBack = {}) },
            Page("流量") { RvTrafficScreen(rvSampleActivity(), RvData.session, onBack = {}) },
            Page("關於") { RvAboutScreen(rvSampleVersions, onBack = {}) },
            Page("日誌等級") { RvLogLevelScreen(RvLogLevel.Info, onSelect = {}, onBack = {}) },
            Page("語言") { RvLanguageScreen(RvLanguage.TraditionalChinese, RvLanguage.TraditionalChinese, onSelect = {}, onBack = {}) },
            Page("新增服務規則") { RvServiceRulesPage(onBack = {}) },
        ),
    )

    @Test
    fun pagesWithAPinnedActionEndAboveIt() = endsClear(
        listOf(
            Page("節點詳情") { RvNodeDetail(RvData.nodes.first(), current = false, rvTestTimes(), onBack = {}, onUse = {}, onTest = {}) },
            Page("訂閱") { RvSubsPage(rvSubsStateSample(), RvSubsActions()) },
            Page("訂閱詳情") { RvSubDetailSample(RvData.subscriptions.first(), RvSubDetailState(), {}, onBack = {}, onUpdate = {}) },
            Page("依 App") { RvPerAppScreen(rvPerAppLookSample(""), RvPerAppActions(), onBack = {}) },
            Page("回報問題") { RvReportScreen(RvReportBuild.Ready(RvRedaction()), rvSampleReport, RvRedaction(), {}, onBack = {}) },
            Page("規則") { RvRulesPage(RvPolicyData.allRules, emptyList(), editing = false, onBack = {}) },
            Page("策略群組") { RvPolicyGroupPage(RvPolicyData.streaming, onBack = {}, onSelect = {}, onTest = {}) },
        ),
    )

    /** A page under test: its [name] in a failure, and its [content]. */
    private class Page(val name: String, val content: @Composable () -> Unit)

    /**
     * Shows each of [pages] under the gesture strip and under a three-button bar, each from its top, scrolls it to its
     * end and lists every page whose last line ends under the bar or what stands over the page's foot.
     */
    private fun endsClear(pages: List<Page>) {
        var shown by mutableIntStateOf(0)
        var bar by mutableStateOf(GestureBar)
        compose.setContent {
            RvTheme(seedColorScheme(Seed.Cyan.argb, false), false) {
                key(shown, bar) {
                    CompositionLocalProvider(LocalFrameInsets provides WindowInsets(top = StatusBar, bottom = bar), pages[shown].content)
                }
            }
        }
        val faults = pages.indices.flatMap { i ->
            listOf(GestureBar, THREE_BUTTON_BAR).mapNotNull { insets ->
                compose.runOnIdle {
                    shown = i
                    bar = insets
                }
                compose.waitForIdle()
                scrollToTheEnd()
                fault(pages[i].name, insets)
            }
        }
        assertEquals(emptyList<String>(), faults)
    }

    /** Scrolls every vertical scroller on the window to its end, as far as a finger can take it. */
    private fun scrollToTheEnd() {
        val scrollers = compose.onAllNodes(SCROLLER)
        repeat(scrollers.fetchSemanticsNodes().size) { i ->
            scrollers[i].performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, TO_THE_END * compose.density.density) }
        }
        compose.waitForIdle()
    }

    /**
     * The page [name] under a navigation bar of [bar], at its end: null when its lowest line of text in the scroll ends
     * at or above the bar and every control that stands over the window's lower half outside the scroll (the tab bar's
     * tabs, a pinned button, floating actions), else what ends where.
     */
    private fun fault(name: String, bar: Dp): String? {
        val density = compose.density.density
        val window = compose.onRoot().fetchSemanticsNode().boundsInRoot.bottom / density
        val inScroll = hasAnyAncestor(SCROLLER)
        val over = compose.onAllNodes(hasClickAction() and !inScroll, useUnmergedTree = true).fetchSemanticsNodes()
            .map { it.boundsInRoot.top / density }
            .filter { it > window / 2 }
        val floor = (over + (window - bar.value)).min()
        val lowest = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text) and inScroll, useUnmergedTree = true)
            .fetchSemanticsNodes()
            .maxByOrNull { it.boundsInRoot.bottom }
            ?: return "$name ($bar bar): no text in its scroll"
        val end = lowest.boundsInRoot.bottom / density
        val line = lowest.config[SemanticsProperties.Text].joinToString()
        return "$name ($bar bar): the last line ($line) ends at $end dp, under $floor dp".takeIf { end > floor + SLACK }
    }

    private companion object {
        val SCROLLER = SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)

        /** Three-button navigation's bar, as `RvWindowShotTest` draws it. */
        val THREE_BUTTON_BAR = 48.dp

        /** Farther than any of these pages runs: the scroll stops at its end. */
        const val TO_THE_END = 10_000f

        /** A layout's rounding to the pixel, half a pixel at 2 px per dp. */
        const val SLACK = 0.25f
    }
}
