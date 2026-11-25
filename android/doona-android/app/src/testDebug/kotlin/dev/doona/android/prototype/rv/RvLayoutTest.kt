package dev.doona.android.prototype.rv

import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import dev.doona.android.R
import dev.doona.android.designsystem.rv.RvChevronSize
import dev.doona.android.designsystem.rv.RvMaterialPlumbing
import dev.doona.android.designsystem.rv.RvPart
import dev.doona.android.designsystem.rv.RvSpace
import dev.doona.android.designsystem.rv.RvTheme
import dev.doona.android.designsystem.rv.rvSystemScheme
import dev.doona.android.feature.model.RvNodeSort
import dev.doona.android.feature.model.RvPolicyState
import dev.doona.android.feature.model.RvRouteMode
import dev.doona.android.feature.report.RvRedaction
import dev.doona.android.feature.report.RvReportBuild
import dev.doona.android.prototype.rv.config.RvConfigHistoryData
import dev.doona.android.prototype.rv.config.RvConfigImportPage
import dev.doona.android.prototype.rv.connections.RvConnectionDetail
import dev.doona.android.prototype.rv.connections.RvConnectionsPage
import dev.doona.android.prototype.rv.data.RvData
import dev.doona.android.prototype.rv.nodes.RV_NODES_DETAIL
import dev.doona.android.prototype.rv.nodes.RvNodeDetail
import dev.doona.android.prototype.rv.nodes.RvNodePicker
import dev.doona.android.prototype.rv.nodes.RvNodePickerPage
import dev.doona.android.prototype.rv.nodes.RvNodePickerPageSample
import dev.doona.android.prototype.rv.nodes.RvNodePickerSample
import dev.doona.android.prototype.rv.nodes.RvNodesPage
import dev.doona.android.prototype.rv.nodes.sampleNodesPage
import dev.doona.android.prototype.rv.policy.RvEffectiveConfigPage
import dev.doona.android.prototype.rv.policy.RvEffectiveConfigPageSample
import dev.doona.android.prototype.rv.policy.RvPolicyData
import dev.doona.android.prototype.rv.policy.RvPolicyGroupPage
import dev.doona.android.prototype.rv.policy.RvPolicyRulePage
import dev.doona.android.prototype.rv.policy.RvPolicyScreen
import dev.doona.android.prototype.rv.policy.RvPolicyScreenSample
import dev.doona.android.prototype.rv.policy.RvQuickRule
import dev.doona.android.prototype.rv.policy.RvRuleDraft
import dev.doona.android.prototype.rv.policy.RvRuleEditPage
import dev.doona.android.prototype.rv.policy.RvRulesPage
import dev.doona.android.prototype.rv.policy.RvServiceRulesPage
import dev.doona.android.prototype.rv.policy.RvTemplateData
import dev.doona.android.prototype.rv.policy.RvTemplateImpactPage
import dev.doona.android.prototype.rv.policy.RvTemplatePage
import dev.doona.android.prototype.rv.policy.RvTemplatePageSample
import dev.doona.android.prototype.rv.settings.RvAboutScreen
import dev.doona.android.prototype.rv.settings.RvDnsScreen
import dev.doona.android.prototype.rv.settings.RvDnsUpstream
import dev.doona.android.prototype.rv.settings.RvIntervalScreen
import dev.doona.android.prototype.rv.settings.RvLicensesScreen
import dev.doona.android.prototype.rv.settings.RvLogLevel
import dev.doona.android.prototype.rv.settings.RvLogLevelScreen
import dev.doona.android.prototype.rv.settings.RvPerAppActions
import dev.doona.android.prototype.rv.settings.RvPerAppScreen
import dev.doona.android.prototype.rv.settings.RvReportScreen
import dev.doona.android.prototype.rv.settings.RvSettingsScreen
import dev.doona.android.prototype.rv.settings.RvSettingsScreenSample
import dev.doona.android.prototype.rv.settings.RvThemeScreen
import dev.doona.android.prototype.rv.settings.RvUpdate
import dev.doona.android.prototype.rv.settings.rvPerAppSample
import dev.doona.android.prototype.rv.settings.rvSampleAppearance
import dev.doona.android.prototype.rv.settings.rvSampleReport
import dev.doona.android.prototype.rv.settings.rvSampleSettings
import dev.doona.android.prototype.rv.settings.rvSampleVersions
import dev.doona.android.prototype.rv.stats.RvStatsScreen
import dev.doona.android.prototype.rv.stats.rvStatsSample
import dev.doona.android.testing.PhoneFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Measures the library's layout parts on every RV page against the tokens (plans/reviews/spacing-audit.md), so no page
 * drifts from the Revolut grid without a failure: the large title (or the mark above it) under the bar, its caption and
 * control row on the gutter at the header's gaps, then the first card or section title one header gap under them (a
 * control gap under a control row), a tab home's first content and an in-page search's list one search gap under the
 * search; every card, section title and footer on the
 * gutter, cards 16 dp apart, a section title 24 dp under what is above it and 12 dp over its card; rows 58 or 82 dp edge
 * to edge in their card; segmented controls 36 dp, 16 dp inside a card or on the gutter. The parts are found by their
 * [RvPart] tags; each page is the composition its golden records. A failure lists every drift on the page in dp.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rTW-w412dp-h915dp-notnight-xhdpi")
class RvLayoutTest {
    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val utc = Utc()

    @Test
    fun activity() = measure("activity", Top.Hero) { RvActivityScreenSample(rvSampleActivity(), onAction = {}, onConnect = {}) }

    @Test
    fun traffic() = measure("traffic", Top.Title) { RvTrafficScreen(rvSampleActivity(), RvData.session, onBack = {}) }

    @Test
    fun policyRule() = measure("policy", Top.Tab) { RvPolicyScreenSample(RvPolicyState(mode = RvRouteMode.Rule), onChange = {}) }

    @Test
    fun policyGlobal() = measure("policy-global", Top.Tab) { RvPolicyScreenSample(RvPolicyState(mode = RvRouteMode.Global), onChange = {}) }

    @Test
    fun policyDirect() = measure("policy-direct", Top.Tab) { RvPolicyScreenSample(RvPolicyState(mode = RvRouteMode.Direct), onChange = {}) }

    @Test
    fun groupSelect() = measure("policy-group-select", Top.Title) { RvPolicyGroupPage(RvPolicyData.streaming, onBack = {}, onSelect = {}, onTest = {}) }

    @Test
    fun groupAuto() = measure("policy-group-auto", Top.Title) { RvPolicyGroupPage(RvPolicyData.auto, onBack = {}, onSelect = {}, onTest = {}) }

    @Test
    fun picker() = measure("policy-picker", Top.Title) {
        RvNodePickerPageSample(RvData.nodes, RvData.subscriptions, RvData.CURRENT_ID, RvNodeSort.Region, onSort = {}, onPick = {}, onBack = {}, onTest = {}, onSearch = {})
    }

    @Test
    fun ruleFlow() = measure("policy-flow", Top.Title) { RvPolicyRulePage(RvPolicyData.connection, RvQuickRule(), onChange = {}, onBack = {}, onApply = {}) }

    @Test
    fun ruleEdit() = measure("rule-edit", Top.Title) { RvRuleEditPage(RvRuleDraft.of(RvPolicyData.allRules[GITHUB]), onChange = {}, onBack = {}, onApply = {}, onDelete = {}) }

    @Test
    fun template() = measure("template", Top.Title) { RvTemplatePageSample(RvTemplateData.cnDirect, RvTemplateData.services, onSelect = {}, onBack = {}, onReview = {}) }

    @Test
    fun templateImpact() = measure("template-impact", Top.Title) { RvTemplateImpactPage(RvTemplateData.impact, onBack = {}, onApply = {}) }

    @Test
    fun effectiveConfig() = measure("effective-config", Top.Title) { RvEffectiveConfigPageSample(RvPolicyState(), rvSampleSettings(), onBack = {}) }

    @Test
    fun rules() = measure("rules", Top.Title) { RvRulesPage(RvPolicyData.allRules, emptyList(), editing = false, onBack = {}) }

    @Test
    fun importLimit() = measure("config-import-limit", Top.Title) { RvConfigImportPage(RvConfigHistoryData.limit, onBack = {}) }

    @Test
    fun importEscape() = measure("config-import-escape", Top.Title) { RvConfigImportPage(RvConfigHistoryData.escape, onBack = {}) }

    /** 策略's value rows (範本, 設定檔) end their value a trailing gap before the chevron, as the node picker's row does. */
    @Test
    fun policyValueEndsATrailingGapBeforeTheChevron() {
        show { RvPolicyScreenSample(RvPolicyState(mode = RvRouteMode.Rule), onChange = {}) }
        // Not boundsInRoot: the card is below the window, where those bounds are clipped to nothing.
        val value = compose.onNodeWithText(rvText(R.string.rv_policy_template_value), useUnmergedTree = true).fetchSemanticsNode()
        val end = (value.positionInRoot.x + value.size.width) / value.layoutInfo.density.density
        val rowEnd = PHONE_WIDTH - RvSpace.gutter - RvSpace.rowPadding
        assertEquals((rowEnd - RvChevronSize.box - RvSpace.trailingGap).value, end, HALF_DP)
    }

    @Test
    fun serviceRules() = measure("rules-service", Top.Title) { RvServiceRulesPage(onBack = {}) }

    /** The pull-down node picker (S3): its caption in the header's slot, 4 dp under the title and 24 dp over the first card. */
    @Test
    fun nodePicker() = measure("node-picker", Top.Title) { RvNodePickerSample(RvData.nodes, RvData.subscriptions, RvData.CURRENT_ID, onPick = {}, onDetail = {}, onClose = {}) }

    @Test
    fun nodesPage() = measure("nodes-page", Top.Title) { RvNodesPage(sampleNodesPage(), onBack = {}, onFilter = {}, onOpen = {}, onAllNodes = {}) }

    @Test
    fun nodeDetail() = measure("node-detail", Top.Title) {
        val page = sampleNodesPage()
        val node = page.nodes.first { it.id == RV_NODES_DETAIL }
        RvNodeDetail(node, current = node.id == page.currentId, page.testedAtEpochMs, onBack = {}, onUse = {}, onTest = {})
    }

    @Test
    fun stats() = measure("stats", Top.Title) { RvStatsScreen(rvStatsSample(), onBack = {}) }

    @Test
    fun settings() = measure("settings", Top.Hero) { RvSettingsScreenSample(rvSampleSettings(), onChange = {}, onOpenTheme = {}) }

    @Test
    fun theme() = measure("settings-theme", Top.Title) {
        RvThemeScreen(rvSampleAppearance(), onChange = {}, light = seedColorScheme(Seed.Cyan.argb, false), dark = seedColorScheme(Seed.Cyan.argb, true), onBack = {})
    }

    @Test
    fun dns() = measure("settings-dns", Top.Title) { RvDnsScreen(RvDnsUpstream.Cloudflare, onSelect = {}, onBack = {}) }

    @Test
    fun connections() = measure("connections", Top.Tab) { RvConnectionsPage(RvPolicyData.connections) }

    @Test
    fun connection() = measure("connection", Top.Header) { RvConnectionDetail(RvPolicyData.connections[YOUTUBE], onBack = {}, onClose = {}, onNewRule = {}) }

    @Test
    fun perApp() = measure("perapp-only", Top.Title) { RvPerAppScreen(rvPerAppSample(""), RvPerAppActions(), onBack = {}) }

    @Test
    fun perAppNoneSelected() = measure("perapp-none-selected", Top.Title) { RvPerAppScreen(rvPerAppSample("none-selected"), RvPerAppActions(), onBack = {}) }

    @Test
    fun perAppSaveFailed() = measure("perapp-save-failed", Top.Title) { RvPerAppScreen(rvPerAppSample("save-failed"), RvPerAppActions(), onBack = {}) }

    @Test
    fun licenses() = measure("licenses", Top.Title) { RvLicensesScreen(onOpen = {}, onBack = {}) }

    // Every page 設定 opens keeps the one pushed header: the title where DNS and the licences have it (owner-fix2 item 2).
    // Not 語言: its 简体中文 row is 60 dp, since Noto Sans TC has no 简 and the row falls back to a taller face.
    @Test
    fun about() = measure("settings-about", Top.Title) { RvAboutScreen(rvSampleVersions, onBack = {}) }

    /** 設定 with a failed update check: the banner under the hero, a section's gap above and below it (owner-fix2 item 18). */
    @Test
    fun settingsUpdateFailed() = measure("settings-update-failed", Top.Hero) {
        RvSettingsScreenSample(rvSampleSettings(), onChange = {}, onOpenTheme = {}, update = RvUpdate.Failed)
    }

    @Test
    fun interval() = measure("settings-interval", Top.Title) { RvIntervalScreen(RvData.UPDATE_HOURS, onSelect = {}, onBack = {}) }

    @Test
    fun logLevel() = measure("settings-log-level", Top.Title) { RvLogLevelScreen(RvLogLevel.Info, onSelect = {}, onBack = {}) }

    @Test
    fun logLevelDebug() = measure("settings-log-level-debug", Top.Title) { RvLogLevelScreen(RvLogLevel.Debug, onSelect = {}, onBack = {}) }

    @Test
    fun report() = measure("report", Top.Title) {
        RvReportScreen(RvReportBuild.Ready(RvRedaction()), rvSampleReport, RvRedaction(), onRedaction = {}, onBack = {})
    }

    @Test
    fun reportFailed() = measure("report-failed", Top.Title) {
        RvReportScreen(RvReportBuild.Failed, rvSampleReport, RvRedaction(), onRedaction = {}, onBack = {})
    }

    private fun measure(page: String, top: Top, content: @Composable () -> Unit) {
        show(content)
        val drift = RvMeasure(compose.rvParts(), rvBarBottom(top)).run(top)
        printHeight(page)
        assertTrue("$page drifts from the tokens:\n" + drift.joinToString("\n"), drift.isEmpty())
    }

    private fun show(content: @Composable () -> Unit) {
        compose.setContent {
            RvMaterialPlumbing(rvSystemScheme(false)) {
                PhoneFrame { RvTheme(seedColorScheme(Seed.Cyan.argb, false), false, content) }
            }
        }
        compose.waitForIdle()
    }

    /** The page's full height, for the whole-page goldens' windows (the page is taller than this window, so it scrolls). */
    private fun printHeight(page: String) {
        val scroll = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)).fetchSemanticsNodes().firstOrNull() ?: return
        val dp = scroll.layoutInfo.density.density
        println("RvLayoutTest $page: ${(scroll.size.height / dp + scroll.config[SemanticsProperties.VerticalScrollAxisRange].maxValue() / dp)} dp")
    }

    private companion object {
        /** The phone window's width, as the class's qualifiers set it. */
        val PHONE_WIDTH = 412.dp

        const val HALF_DP = 0.5f

        /** YouTube's connection, whose host wraps. */
        const val YOUTUBE = 3

        /** github.com, the second of the user's rules, as `rule-edit` shows it. */
        const val GITHUB = 5
    }
}
