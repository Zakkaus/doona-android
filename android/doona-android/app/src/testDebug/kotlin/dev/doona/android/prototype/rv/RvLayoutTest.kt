package dev.doona.android.prototype.rv

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.toSize
import dev.doona.android.designsystem.theme.DoonaTheme
import dev.doona.android.designsystem.theme.LocalFrameInsets
import dev.doona.android.prototype.rv.connections.RvConnectionDetail
import dev.doona.android.prototype.rv.connections.RvConnectionsPage
import dev.doona.android.prototype.rv.data.RvData
import dev.doona.android.prototype.rv.data.RvNodeSort
import dev.doona.android.prototype.rv.data.RvRouteMode
import dev.doona.android.prototype.rv.nodes.RV_NODES_DETAIL
import dev.doona.android.prototype.rv.nodes.RvNodeDetail
import dev.doona.android.prototype.rv.nodes.RvNodePickerPage
import dev.doona.android.prototype.rv.nodes.RvNodesPage
import dev.doona.android.prototype.rv.nodes.sampleNodesPage
import dev.doona.android.prototype.rv.policy.RvEffectiveConfigPage
import dev.doona.android.prototype.rv.policy.RvPolicyData
import dev.doona.android.prototype.rv.policy.RvPolicyGroupPage
import dev.doona.android.prototype.rv.policy.RvPolicyRulePage
import dev.doona.android.prototype.rv.policy.RvPolicyScreen
import dev.doona.android.prototype.rv.policy.RvPolicyState
import dev.doona.android.prototype.rv.policy.RvQuickRule
import dev.doona.android.prototype.rv.policy.RvRuleDraft
import dev.doona.android.prototype.rv.policy.RvRuleEditPage
import dev.doona.android.prototype.rv.policy.RvTemplateData
import dev.doona.android.prototype.rv.policy.RvTemplateImpactPage
import dev.doona.android.prototype.rv.policy.RvTemplatePage
import dev.doona.android.prototype.rv.settings.RvDnsScreen
import dev.doona.android.prototype.rv.settings.RvDnsUpstream
import dev.doona.android.prototype.rv.settings.RvExport
import dev.doona.android.prototype.rv.settings.RvLicensesScreen
import dev.doona.android.prototype.rv.settings.RvLogLevel
import dev.doona.android.prototype.rv.settings.RvPerAppActions
import dev.doona.android.prototype.rv.settings.RvPerAppScreen
import dev.doona.android.prototype.rv.settings.RvReportScreen
import dev.doona.android.prototype.rv.settings.RvSettingsScreen
import dev.doona.android.prototype.rv.settings.RvThemeScreen
import dev.doona.android.prototype.rv.settings.rvPerAppSample
import dev.doona.android.prototype.rv.settings.rvSampleAppearance
import dev.doona.android.prototype.rv.settings.rvSampleSettings
import dev.doona.android.prototype.rv.stats.RvStatsScreen
import dev.doona.android.prototype.rv.stats.rvStatsSample
import dev.doona.android.testing.PhoneFrame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

/**
 * Measures the library's layout parts on every RV page against the tokens (plans/reviews/spacing-audit.md), so no page
 * drifts from the Revolut grid without a failure: the large title (or the mark above it) under the bar, its caption and
 * control row on the gutter at the header's gaps, then the first card or section title one header gap under them (a
 * control gap under a control row); every card, section title and footer on the
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
    fun activity() = measure("activity", Top.Hero) { RvActivityScreen(rvSampleActivity(), onAction = {}, onConnect = {}) }

    @Test
    fun traffic() = measure("traffic", Top.Title) { RvTrafficScreen(rvSampleActivity(), RvData.session, onBack = {}) }

    @Test
    fun policyRule() = measure("policy", Top.Tab) { RvPolicyScreen(RvPolicyState(mode = RvRouteMode.Rule), onChange = {}) }

    @Test
    fun policyGlobal() = measure("policy-global", Top.Tab) { RvPolicyScreen(RvPolicyState(mode = RvRouteMode.Global), onChange = {}) }

    @Test
    fun policyDirect() = measure("policy-direct", Top.Tab) { RvPolicyScreen(RvPolicyState(mode = RvRouteMode.Direct), onChange = {}) }

    @Test
    fun groupSelect() = measure("policy-group-select", Top.Title) { RvPolicyGroupPage(RvPolicyData.streaming, onBack = {}, onSelect = {}, onTest = {}) }

    @Test
    fun groupAuto() = measure("policy-group-auto", Top.Title) { RvPolicyGroupPage(RvPolicyData.auto, onBack = {}, onSelect = {}, onTest = {}) }

    @Test
    fun picker() = measure("policy-picker", Top.Title) {
        RvNodePickerPage(RvData.nodes, RvData.subscriptions, RvData.CURRENT_ID, RvNodeSort.Region, onSort = {}, onPick = {}, onBack = {}, onTest = {})
    }

    @Test
    fun ruleFlow() = measure("policy-flow", Top.Title) { RvPolicyRulePage(RvPolicyData.connection, RvQuickRule(), onChange = {}, onBack = {}, onApply = {}) }

    @Test
    fun ruleEdit() = measure("rule-edit", Top.Title) { RvRuleEditPage(RvRuleDraft.of(RvPolicyData.allRules[GITHUB]), onChange = {}, onBack = {}, onApply = {}, onDelete = {}) }

    @Test
    fun template() = measure("template", Top.Title) { RvTemplatePage(RvTemplateData.cnDirect, RvTemplateData.services, onSelect = {}, onBack = {}, onReview = {}) }

    @Test
    fun templateImpact() = measure("template-impact", Top.Title) { RvTemplateImpactPage(RvTemplateData.impact, onBack = {}, onApply = {}) }

    @Test
    fun effectiveConfig() = measure("effective-config", Top.Title) { RvEffectiveConfigPage(RvPolicyState(), rvSampleSettings(), onBack = {}) }

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
    fun settings() = measure("settings", Top.Hero) { RvSettingsScreen(rvSampleSettings(), onChange = {}, onOpenTheme = {}) }

    @Test
    fun theme() = measure("settings-theme", Top.Title) {
        RvThemeScreen(rvSampleAppearance(), onChange = {}, light = seedColorScheme(Seed.Cyan.argb, false), dark = seedColorScheme(Seed.Cyan.argb, true), onBack = {})
    }

    @Test
    fun dns() = measure("settings-dns", Top.Title) { RvDnsScreen(RvDnsUpstream.Cloudflare, onSelect = {}, onBack = {}) }

    @Test
    fun report() = measure("report", Top.Title) { RvReportScreen(RvLogLevel.Info, RvExport.Failed, onLevel = {}, onExport = {}, onBack = {}) }

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

    private fun measure(page: String, top: Top, content: @Composable () -> Unit) {
        var barBottom = 0f
        compose.setContent {
            DoonaTheme(false) {
                PhoneFrame {
                    val insets = LocalFrameInsets.current ?: WindowInsets(0)
                    // A tab home's bar sits higher than a pushed page's.
                    val barTop = if (top == Top.Tab) RvChrome.tabBarTop else RvChrome.barTop
                    barBottom = insets.getTop(LocalDensity.current) / LocalDensity.current.density + rvBarHeight(barTop).value
                    RvTheme(seedColorScheme(Seed.Cyan.argb, false), false, content)
                }
            }
        }
        compose.waitForIdle()
        val drift = Measure(compose.parts(), barBottom).run(top)
        printHeight(page)
        assertTrue("$page drifts from the tokens:\n" + drift.joinToString("\n"), drift.isEmpty())
    }

    /** The page's full height, for the whole-page goldens' windows (the page is taller than this window, so it scrolls). */
    private fun printHeight(page: String) {
        val scroll = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)).fetchSemanticsNodes().firstOrNull() ?: return
        val dp = scroll.layoutInfo.density.density
        println("RvLayoutTest $page: ${(scroll.size.height / dp + scroll.config[SemanticsProperties.VerticalScrollAxisRange].maxValue() / dp)} dp")
    }

    private fun ComposeContentTestRule.parts(): Map<String, List<Rect>> {
        val tags = listOf(RvPart.MARK, RvPart.TITLE, RvPart.CAPTION, RvPart.CONTROL, RvPart.CARD, RvPart.SECTION, RvPart.FOOTER, RvPart.BLOCK, RvPart.ROW, RvPart.SEGMENTED)
        // The merged tree: a picture of a page (主題's preview) clears its semantics, so its cards are not the page's.
        return tags.associateWith { tag ->
            onAllNodesWithTag(tag).fetchSemanticsNodes().map { node ->
                val d = node.layoutInfo.density.density
                // Unclipped: the scroll's viewport clips boundsInRoot to the window.
                Rect(node.positionInRoot / d, node.size.toSize() / d)
            }
        }
    }

    private companion object {
        /** YouTube's connection, whose host wraps. */
        const val YOUTUBE = 3

        /** github.com, the second of the user's rules, as `rule-edit` shows it. */
        const val GITHUB = 5
    }
}

/**
 * How a page starts under the bar: a large title, a centred header (a detail page's, as a block) where a large title
 * would start, its own hero (not measured), or a tab home's first content, [RvSpace.tabTop] under its bar
 * ([RvChrome.tabBarTop]).
 */
private enum class Top { Title, Header, Hero, Tab }

/** One page's parts in dp, and the drifts found in them. */
private class Measure(private val parts: Map<String, List<Rect>>, private val barBottom: Float) {
    private val width = SCREEN
    private val drift = mutableListOf<String>()
    private val cards = parts.getValue(RvPart.CARD).filter { it.left >= 0f && it.right <= width }
    private val controls = parts.getValue(RvPart.CONTROL)
    private val holders = controls + parts.getValue(RvPart.BLOCK)

    fun run(top: Top): List<String> {
        top(top)
        column(top)
        rows()
        segmented()
        return drift
    }

    private fun top(top: Top) {
        // The title, or the mark above it, is the header's first part.
        val head = (parts.getValue(RvPart.MARK) + parts.getValue(RvPart.TITLE)).minByOrNull { it.top }
        when (top) {
            Top.Title -> head?.let { expect("header's top", it.top, barBottom + RvChrome.largeTitleTop.value) } ?: drift.add("no large title")
            Top.Header -> blocks().firstOrNull()?.let { expect("header's top", it.second.top, barBottom + RvChrome.largeTitleTop.value) } ?: drift.add("no header")
            Top.Hero -> Unit
            Top.Tab -> blocks().firstOrNull()?.let { expect("tab's first content", it.second.top, barBottom + RvSpace.tabTop.value) } ?: drift.add("no content")
        }
    }

    /**
     * The page's column: each block on the gutter, and the gap to the block under it by what the two are. A centred
     * header (a detail page's, measured as a block) is a header: what follows it keeps the header's gap.
     */
    private fun column(top: Top) {
        val blocks = blocks()
        val header = blocks.firstOrNull()?.takeIf { top == Top.Header }
        val end = width - GUTTER.value
        for ((kind, rect) in blocks) {
            when {
                // A pager runs edge to edge; its tiles keep to the gutter by its content padding.
                kind == RvPart.BLOCK -> Unit

                kind != RvPart.CARD -> expect("$kind's start at ${rect.top} dp", rect.left, GUTTER)

                // A tile beside another keeps one edge on the gutter.
                rect.width < width / 2 -> if (abs(rect.left - GUTTER.value) > TOLERANCE && abs(rect.right - end) > TOLERANCE) drift.add("tile at ${rect.top} dp is off the gutter")

                else -> {
                    expect("card's start at ${rect.top} dp", rect.left, GUTTER)
                    expect("card's end at ${rect.top} dp", rect.right, end)
                }
            }
        }
        for ((upper, lower) in pairs(blocks)) {
            val gap = (if (upper == header) RvSpace.headerGap else GAPS[upper.first.asCard() to lower.first.asCard()]) ?: continue
            expect("gap ${upper.first} to ${lower.first} at ${lower.second.top} dp", lower.second.top - upper.second.bottom, gap)
        }
    }

    /** The column's top-level blocks, top first: a card inside a control or another card is part of it. */
    private fun blocks(): List<Pair<String, Rect>> {
        val kinds = listOf(RvPart.MARK, RvPart.TITLE, RvPart.CAPTION, RvPart.CONTROL, RvPart.SECTION, RvPart.FOOTER)
        val loose = kinds.flatMap { kind -> parts.getValue(kind).map { kind to it } }
        // A block is measured as a card; the cards in a block or a control are part of it.
        val outer = cards.filter { card -> (holders + cards).none { it != card && it.contains(card) } }.map { RvPart.CARD to it }
        val composite = parts.getValue(RvPart.BLOCK).map { RvPart.BLOCK to it }
        val segmented = parts.getValue(RvPart.SEGMENTED).filter { s -> (holders + cards).none { it.contains(s) } }.map { RvPart.SEGMENTED to it }
        return (loose + outer + composite + segmented).sortedBy { it.second.top }
    }

    /** Each block with the block straight under it in the same column (they overlap across). */
    private fun pairs(blocks: List<Pair<String, Rect>>): List<Pair<Pair<String, Rect>, Pair<String, Rect>>> = blocks.mapNotNull { lower ->
        blocks.filter { it.second.bottom <= lower.second.top + TOLERANCE && it.second.overlapsAcross(lower.second) }.maxByOrNull { it.second.bottom }?.let { it to lower }
    }

    /** Rows run edge to edge in their card at 58 or 82 dp, or taller where their text wraps (the one row rule). */
    private fun rows() {
        for (row in parts.getValue(RvPart.ROW)) {
            val card = cards.firstOrNull { it.contains(row) } ?: continue
            val grown = row.height > RvSize.rowTwoLine.value + TOLERANCE
            if (!grown && ROW_HEIGHTS.none { abs(row.height - it.value) <= TOLERANCE }) drift.add("row at ${row.top} dp is ${row.height} dp, not 58 or 82")
            expect("row's start at ${row.top} dp", row.left, card.left)
            expect("row's end at ${row.top} dp", row.right, card.right)
        }
    }

    /** A segmented control is 36 dp tall, on the gutter or 16 dp inside its card. */
    private fun segmented() {
        for (s in parts.getValue(RvPart.SEGMENTED)) {
            expect("segmented control's height", s.height, RvChartSize.segment)
            val card = cards.firstOrNull { it.contains(s) }
            expect("segmented control's start", s.left, card?.let { it.left + RvSpace.rowPadding.value } ?: GUTTER.value)
            expect("segmented control's end", s.right, card?.let { it.right - RvSpace.rowPadding.value } ?: (width - GUTTER.value))
        }
    }

    private fun String.asCard() = if (this == RvPart.BLOCK) RvPart.CARD else this

    private fun expect(what: String, actual: Float, expected: Dp) = expect(what, actual, expected.value)

    private fun expect(what: String, actual: Float, expected: Float) {
        if (abs(actual - expected) > TOLERANCE) drift.add("$what: $actual dp, the tokens say $expected dp")
    }

    private fun Rect.contains(other: Rect) = other.left >= left - TOLERANCE && other.right <= right + TOLERANCE && other.top >= top - TOLERANCE && other.bottom <= bottom + TOLERANCE

    private fun Rect.overlapsAcross(other: Rect) = left < other.right && other.left < right

    private companion object {
        const val SCREEN = 412f
        const val TOLERANCE = 0.5f
        val GUTTER = RvSpace.gutter
        val ROW_HEIGHTS = listOf(RvSize.rowOneLine, RvSize.rowTwoLine)

        /** The gap the tokens set between two kinds of block, upper to lower; pairs not listed have other content between. */
        val GAPS = mapOf(
            (RvPart.MARK to RvPart.TITLE) to RvSpace.markToTitle,
            (RvPart.TITLE to RvPart.CAPTION) to RvSpace.captionGap,
            (RvPart.TITLE to RvPart.CONTROL) to RvSpace.controlGap,
            (RvPart.CAPTION to RvPart.CONTROL) to RvSpace.controlGap,
            (RvPart.TITLE to RvPart.CARD) to RvSpace.headerGap,
            (RvPart.CAPTION to RvPart.CARD) to RvSpace.headerGap,
            (RvPart.CONTROL to RvPart.CARD) to RvSpace.controlGap,
            (RvPart.TITLE to RvPart.SECTION) to RvSpace.headerGap,
            (RvPart.CAPTION to RvPart.SECTION) to RvSpace.headerGap,
            (RvPart.CONTROL to RvPart.SECTION) to RvSpace.controlGap,
            (RvPart.CARD to RvPart.CARD) to RvSpace.cardGap,
            (RvPart.CARD to RvPart.SECTION) to RvSpace.sectionAbove,
            (RvPart.CARD to RvPart.CONTROL) to RvSpace.controlGap,
            (RvPart.CARD to RvPart.FOOTER) to RvSpace.footnoteAbove,
            (RvPart.FOOTER to RvPart.SECTION) to RvSpace.sectionAbove,
            // A footer ends its group; a card with no section title over it starts the next as a section title would.
            (RvPart.FOOTER to RvPart.CARD) to RvSpace.sectionAbove,
            (RvPart.SECTION to RvPart.CARD) to RvSpace.sectionBelow,
        )
    }
}
