package dev.doona.android.prototype.rv

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasParent
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.toSize
import dev.doona.android.R
import dev.doona.android.designsystem.rv.LocalRvTesting
import dev.doona.android.designsystem.rv.RvChartSize
import dev.doona.android.designsystem.rv.RvFormat
import dev.doona.android.designsystem.rv.RvPart
import dev.doona.android.designsystem.rv.RvSize
import dev.doona.android.designsystem.rv.RvTheme
import dev.doona.android.designsystem.rv.rvReadsWell
import dev.doona.android.engine.Mode
import dev.doona.android.prototype.rv.data.RvData
import dev.doona.android.prototype.rv.data.RvNodeSort
import dev.doona.android.prototype.rv.data.RvRouteMode
import dev.doona.android.prototype.rv.nodes.RvNodePicker
import dev.doona.android.prototype.rv.nodes.RvNodePickerPage
import dev.doona.android.prototype.rv.nodes.SEPARATOR
import dev.doona.android.prototype.rv.policy.RvPolicyData
import dev.doona.android.prototype.rv.policy.RvPolicyGroupPage
import dev.doona.android.prototype.rv.policy.RvPolicyRulePage
import dev.doona.android.prototype.rv.policy.RvPolicyScreen
import dev.doona.android.prototype.rv.policy.RvPolicyState
import dev.doona.android.prototype.rv.policy.RvQuickRule
import dev.doona.android.prototype.rv.settings.RvSettingsRow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

/**
 * The library's parts at the phone's largest font size (2.0), where a layout fixed for the default size cuts what it
 * shows. Each test names the audit finding (`plans/briefs/ui-audit-4e.md`) it guards and fails on the part as it was at
 * Step 4e.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rTW-w412dp-h915dp-notnight-xhdpi")
class RvLargeTextTest {
    @get:Rule
    val compose = createComposeRule()

    @After
    fun restoreScale() = rvAnimatorScale(1f)

    /** 新增規則's facts (X1): every label kept, the target's host:port and the route's group and node whole. */
    @Test
    fun ruleFactsAreWhole() {
        val connection = RvPolicyData.connection
        val group = connection.rule.group ?: RvPolicyData.main
        large { RvPolicyRulePage(connection, RvQuickRule(), onChange = {}, onBack = {}, onApply = {}) }
        val labels = listOf(R.string.rv_policy_target, R.string.rv_policy_app, R.string.rv_policy_matched, R.string.rv_policy_via).map(::rvText)
        val values = listOf(connection.host + ":" + connection.port, connection.app, rvText(group.name) + SEPARATOR + group.selected.name)
        (labels + values).forEach(::assertWhole)
    }

    /** 活動's main node (X3): its whole name beside its latency. */
    @Test
    fun activityNodeIsWhole() {
        large { RvActivityScreen(rvSampleActivity(), onAction = {}, onConnect = {}) }
        assertWhole(RvData.current.name)
    }

    /** 策略's main node card (X3): its whole name beside its latency. */
    @Test
    fun policyMainNodeIsWhole() {
        large { RvPolicyScreen(RvPolicyState(mode = RvRouteMode.Rule), onChange = {}) }
        assertWhole(RvPolicyData.main.selected.name)
    }

    /** The pull-down picker (X3): each node it shows has its whole name beside its latency and info mark. */
    @Test
    fun pickerNodesAreWhole() {
        large { RvNodePicker(RvData.nodes, RvData.subscriptions, RvData.CURRENT_ID, onPick = {}, onDetail = {}, onClose = {}) }
        assertShownNodesWhole()
    }

    /** The main node's picker (X3): the same, its rows the pull-down picker's. */
    @Test
    fun policyPickerNodesAreWhole() {
        large { RvNodePickerPage(RvData.nodes, RvData.subscriptions, RvData.CURRENT_ID, RvNodeSort.Region, onSort = {}, onPick = {}, onBack = {}, onTest = {}, onSearch = {}) }
        assertShownNodesWhole()
    }

    private fun assertShownNodesWhole() {
        val shown = RvData.nodes.map { it.name }.distinct().filter { compose.onAllNodes(shows(it), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        assertTrue("no node is shown", shown.isNotEmpty())
        shown.forEach(::assertWhole)
    }

    /** While a test runs, 活動's node row keeps the room of its latency (B1): the name keeps its width and lines. */
    @Test
    fun activityTestKeepsTheRow() = assertTestKeepsRows(listOf(RvData.current.name)) { RvActivityScreen(rvSampleActivity(), onAction = {}, onConnect = {}) }

    /** The same on the main node's picker (B1), whose rows had their latency line by line at rest and a bar while testing. */
    @Test
    fun pickerTestKeepsTheRows() = assertTestKeepsRows(RvData.nodes.map { it.name }.distinct()) {
        RvNodePickerPage(RvData.nodes, RvData.subscriptions, RvData.CURRENT_ID, RvNodeSort.Region, onSort = {}, onPick = {}, onBack = {}, onTest = {}, onSearch = {})
    }

    /** 策略's line under its mode (X2): the whole of what Rule does, its main-node clause included. */
    @Test
    fun modeLineIsWhole() {
        large { RvPolicyScreen(RvPolicyState(mode = RvRouteMode.Rule), onChange = {}) }
        assertWhole(rvText(R.string.rv_policy_mode_rule))
    }

    /** 活動's line under Direct (X2): the whole note, its qualifier on the proxy included. */
    @Test
    fun directLineIsWhole() {
        large { RvActivityScreen(rvSampleActivity().copy(mode = Mode.Direct), onAction = {}, onConnect = {}) }
        assertWhole(rvText(R.string.rv_direct_note))
    }

    /** 新增規則's line under its match (X1): the domain it covers whole. */
    @Test
    fun matchLineIsWhole() {
        val connection = RvPolicyData.connection
        large { RvPolicyRulePage(connection, RvQuickRule(), onChange = {}, onBack = {}, onApply = {}) }
        assertWhole(RuntimeEnvironment.getApplication().getString(R.string.rv_policy_match_full_line, connection.host))
    }

    /** 流量's range chips (B3, X7): one label size for the row, each label whole and inside its pill. */
    @Test
    fun rangeChipsFitTheirRow() {
        large { RvTrafficScreen(rvSampleActivity(), RvData.session, onBack = {}) }
        assertOneWholeSize(listOf(R.string.rv_range_1m, R.string.rv_range_5m, R.string.rv_range_15m, R.string.rv_range_1h).map(::rvText))
    }

    /** The node list's order chips (B3, X7): the same. */
    @Test
    fun orderChipsFitTheirRow() {
        large { RvNodePickerPage(RvData.nodes, RvData.subscriptions, RvData.CURRENT_ID, RvNodeSort.Region, onSort = {}, onPick = {}, onBack = {}, onTest = {}, onSearch = {}) }
        assertOneWholeSize(RvNodeSort.entries.map { rvText(it.label) })
    }

    /**
     * 活動's traffic tiles (X4): each tile's every text whole, its figure on one line, both figures at one size, the two
     * tiles one height, and the sparkline under the note inside the tile.
     */
    @Test
    fun trafficTilesAreWhole() {
        val state = rvSampleActivity()
        large { RvActivityScreen(state, onAction = {}, onConnect = {}) }
        val format = RvFormat(RuntimeEnvironment.getApplication().resources.configuration.locales[0])
        val figures = listOf(state.downRate, state.upRate).map { format.rate(it.toDouble()).toString() }
        val tiles = listOf(R.string.ui_download, R.string.ui_upload).map { caption ->
            compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button).and(hasAnyDescendant(shows(rvText(caption)))), useUnmergedTree = true)
                .fetchSemanticsNodes().single()
        }
        val sizes = tiles.zip(figures).map { (tile, figure) ->
            val texts = texts(tile)
            texts.forEach { assertWhole(it, it.config[SemanticsProperties.Text].joinToString()) }
            val shown = texts.single { it.config[SemanticsProperties.Text].any { line -> line.text == figure } }
            assertEquals("$figure takes one line", 1, layout(shown).lineCount)
            val room = (RvChartSize.spark + RvChartSize.tilePad).value * shown.layoutInfo.density.density
            val under = texts.maxOf(::bottom) + room
            assertTrue("$figure's tile ends at ${bottom(tile)}, its sparkline at $under", under <= bottom(tile) + 1)
            layout(shown).layoutInput.style.fontSize
        }
        assertEquals("one figure size for the row: $sizes", 1, sizes.toSet().size)
        assertEquals("the tiles' heights", 1, tiles.map { it.size.height }.toSet().size)
    }

    /** Where [node] ends, in px from the top of the page, unclipped: the tiles lie below the window at 2.0. */
    private fun bottom(node: SemanticsNode) = node.positionInRoot.y + node.size.height

    /** 活動's 今日用量 at 2.0 (r12): the card grows under its text, and both legend entries are whole. */
    @Test
    fun usageLegendIsWhole() {
        large { RvActivityScreen(rvSampleActivity(), onAction = {}, onConnect = {}) }
        val format = RvFormat(RuntimeEnvironment.getApplication().resources.configuration.locales[0])
        val day = RvData.today
        listOf(R.string.ui_download to day.downTotal, R.string.ui_upload to day.upTotal).forEach { (label, bytes) -> assertWhole(rvText(label) + SEPARATOR + format.bytes(bytes)) }
    }

    /**
     * Every wrapped text on the pages of the r12 panels reads well at 2.0 ([rvReadsWell]): no last line of one or two
     * letters (`代理`, `臺灣`, `43`), no word broken (`Hysteria` over `2`), a meta line broken between its parts only.
     */
    @Test
    fun wrappedTextsReadWell() = largePages { page ->
        val texts = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text), useUnmergedTree = true).fetchSemanticsNodes()
        texts.filter { SemanticsActions.GetTextLayoutResult in it.config }.map(::layout).filter { it.lineCount > 1 }.forEach { layout ->
            val shown = layout.layoutInput.text.text
            val lines = (0 until layout.lineCount).joinToString(" / ") { shown.substring(layout.getLineStart(it), layout.getLineEnd(it)).trim() }
            assertTrue("page $page: '$lines' does not read well", rvReadsWell(layout))
        }
    }

    /**
     * In every row of those pages with one end for its whole text, the end sits beside the text or under it, never beside
     * a part of it (r12): a text the end overlaps has no line above it that runs into the end's column.
     */
    @Test
    fun rowEndsSitBesideOrUnderTheirText() = largePages { page ->
        // The pages whose rows have one end beside the whole text: 活動's, the pull-down picker's (a line-wise end
        // belongs to its own line, so a title with no end may run above it).
        if (page !in ONE_END_PAGES) return@largePages
        compose.onAllNodes(hasTestTag(RvPart.ROW), useUnmergedTree = true).fetchSemanticsNodes().forEach { row ->
            // Unclipped: the page scrolls, and the scroll's viewport clips boundsInRoot to the window.
            val all = texts(row).map { Rect(it.positionInRoot, it.size.toSize()) }
            val start = all.minOfOrNull { it.left } ?: return@forEach
            val (text, ends) = all.partition { it.left - start < 1f }
            for (end in ends) {
                for (beside in text.filter { it.top < end.bottom && end.top < it.bottom }) {
                    val above = text.filter { it.bottom <= beside.top + 1f && it.right > end.left }
                    assertTrue("page $page: an end at $end sits beside $beside under $above", above.isEmpty())
                }
            }
        }
    }

    /**
     * In every row of those pages whose end goes under its text (item 9), the end starts where the text's last line ends
     * (no gap between their line boxes, as between title and subtitle), and the info mark of the pull-down picker's rows
     * sits centred on the end's first line, its figure.
     */
    @Test
    fun endsUnderTheirTextStartRightUnderIt() = largePages { page ->
        rows().forEach { row ->
            val figure = underFigure(row) ?: return@forEach
            val (text, _) = rowTexts(row)
            val above = text.filter { it.bottom <= figure.top + 1f }.maxOf { it.bottom }
            assertEquals("page $page: the end at $figure under a line ending at $above", above, figure.top, 1f)
            marks(row).forEach { assertEquals("page $page: the info mark's middle on the figure at $figure", figure.center.y, it.center.y, 1f) }
        }
    }

    /**
     * While a test runs at 2.0, the busy bar of an end under its text stands in the middle of the line its figure takes at
     * rest, and the row's info mark with it (item 9; the bar hung in the middle of the latency's two lines, 80 px under
     * the text).
     */
    @Test
    fun busyBarsStandOnTheirFiguresLine() {
        rvAnimatorScale(0f)
        var testing by mutableStateOf(false)
        largePages({ testing }) { page ->
            testing = false
            compose.waitForIdle()
            val figures = rows().map { row -> underFigure(row)?.let { it.center.y - top(row) } }
            testing = true
            compose.waitForIdle()
            rows().zip(figures).forEach { (row, figure) ->
                if (figure == null) return@forEach
                val bars = descendants(row).filter { it.config.getOrNull(SemanticsProperties.TestTag) == RvPart.BUSY }.map { centreY(it) - top(row) }
                // A page whose own state runs no test here (策略 inside its pull) shows no bar.
                if (bars.isEmpty()) return@forEach
                assertTrue("page $page: no busy bar on the figure's line at $figure: $bars", bars.any { abs(it - figure) <= 1f })
                marks(row).forEach { assertEquals("page $page: the info mark's middle on the bar's line", figure, it.center.y - top(row), 1f) }
            }
        }
    }

    /**
     * At 1.0, while a test runs, the pull-down picker's names are whole on one line beside the busy bar, as they were
     * before the rows kept their latency's room (item 9: `Hong Kong 02 IPLC`, `Hong Kong 05 IEPL`).
     */
    @Test
    fun pickerNamesKeepOneLineWhileTesting() {
        rvAnimatorScale(0f)
        compose.setContent {
            RvTheme(seedColorScheme(Seed.Cyan.argb, false), false) {
                CompositionLocalProvider(LocalRvTesting provides true) {
                    RvNodePicker(RvData.nodes, RvData.subscriptions, RvData.CURRENT_ID, onPick = {}, onDetail = {}, onClose = {})
                }
            }
        }
        val lines = RvData.nodes.map { it.name }.distinct().flatMap { name -> compose.onAllNodes(shows(name), useUnmergedTree = true).fetchSemanticsNodes().map { name to layout(it).lineCount } }
        assertTrue("the picker shows Hong Kong 02 IPLC", lines.any { it.first == "Hong Kong 02 IPLC" })
        assertEquals("names on more than one line while testing", emptyList<Pair<String, Int>>(), lines.filter { it.second > 1 })
    }

    private fun rows() = compose.onAllNodes(hasTestTag(RvPart.ROW), useUnmergedTree = true).fetchSemanticsNodes()

    private fun top(node: SemanticsNode) = node.positionInRoot.y

    private fun centreY(node: SemanticsNode) = node.positionInRoot.y + node.size.height / 2f

    /** A row's texts, unclipped, split into its start column's and its ends'. */
    private fun rowTexts(row: SemanticsNode): Pair<List<Rect>, List<Rect>> {
        val all = texts(row).map { Rect(it.positionInRoot, it.size.toSize()) }
        val start = all.minOfOrNull { it.left } ?: return emptyList<Rect>() to emptyList()
        return all.partition { it.left - start < 1f }
    }

    /** The row's first end text (its figure: a latency) where it went under the row's text: beside none of its lines, the text's last line above it. */
    private fun underFigure(row: SemanticsNode): Rect? {
        val (text, ends) = rowTexts(row)
        val end = ends.minByOrNull { it.top } ?: return null
        if (text.any { it.top < end.bottom - 1f && end.top < it.bottom - 1f }) return null
        val above = text.filter { it.bottom <= end.top + 1f }
        return end.takeIf { above.isNotEmpty() }
    }

    /** The row's info marks (the pull-down picker's), unclipped. */
    private fun marks(row: SemanticsNode): List<Rect> {
        val details = rvText(R.string.rv_nodes_details)
        return descendants(row).filter { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(details) == true }
            .map { Rect(it.positionInRoot, it.size.toSize()) }
    }

    private fun descendants(node: SemanticsNode): List<SemanticsNode> = node.children.flatMap { listOf(it) + descendants(it) }

    /**
     * A settings row (A4): at 2.0 its end control centres on the title's first line, with a subtitle under the title and
     * with a title of two lines, as at 1.0.
     */
    @Test
    fun settingsRowCentresOnItsFirstLine() {
        val title = rvText(R.string.rv_settings_always_on)
        val rows = listOf(title to rvText(R.string.rv_settings_always_on_sub), title + " " + rvText(R.string.rv_settings_always_on_sub) to null)
        large {
            Column {
                rows.forEachIndexed { i, (text, subtitle) ->
                    RvSettingsRow(text, icon = R.drawable.rv_sym_settings, subtitle = subtitle) { Box(Modifier.size(RvSize.actionGlyph).testTag("end$i")) }
                }
            }
        }
        rows.forEachIndexed { i, (text, _) ->
            val node = compose.onAllNodes(shows(text), useUnmergedTree = true).fetchSemanticsNodes().single()
            val layout = layout(node)
            if (i == 1) assertEquals("$text takes two lines", 2, layout.lineCount)
            val line = node.positionInRoot.y + (layout.getLineTop(0) + layout.getLineBottom(0)) / 2
            val end = compose.onNodeWithTag("end$i", useUnmergedTree = true).fetchSemanticsNode()
            assertEquals("$text: the end's centre on the first line", line, end.positionInRoot.y + end.size.height / 2f, 1f)
        }
    }

    /** The text nodes under [node], in the unmerged tree. */
    private fun texts(node: SemanticsNode): List<SemanticsNode> =
        node.children.flatMap { child -> listOfNotNull(child.takeIf { SemanticsProperties.Text in it.config }) + texts(child) }

    private fun layout(node: SemanticsNode): TextLayoutResult {
        val layouts = mutableListOf<TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
        return layouts.single()
    }

    /**
     * Each of the chip [labels] whole and inside its pill, [RvChartSize.rangeInset] clear of its top and bottom, and all of
     * them laid out at one size (one label size per row).
     */
    private fun assertOneWholeSize(labels: List<String>) {
        val sizes = labels.associateWith { label ->
            val text = compose.onAllNodes(shows(label).and(hasParent(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab))), useUnmergedTree = true)
                .fetchSemanticsNodes().single()
            val pill = text.parent!!.size.height
            val room = text.size.height + 2 * RvChartSize.rangeInset.value * text.layoutInfo.density.density
            assertTrue("$label: its pill is $pill px for a $room px label and its room", pill >= room - 1)
            layout(text).layoutInput.style.fontSize
        }
        labels.forEach(::assertWhole)
        assertEquals("one label size for the row: $sizes", 1, sizes.values.toSet().size)
    }

    /** Each of [names] the [page] shows keeps its size when a latency test of every node starts. */
    private fun assertTestKeepsRows(names: List<String>, page: @Composable () -> Unit) {
        rvAnimatorScale(0f)
        var testing by mutableStateOf(false)
        large { CompositionLocalProvider(LocalRvTesting provides testing, page) }
        val rest = sizes(names)
        assertTrue("the page shows none of the names", rest.isNotEmpty())
        testing = true
        compose.waitForIdle()
        assertEquals("the names' sizes at rest, then while testing", rest, sizes(names))
    }

    private fun sizes(names: List<String>): List<Pair<String, IntSize>> =
        names.flatMap { name -> compose.onAllNodes(shows(name), useUnmergedTree = true).fetchSemanticsNodes().map { name to it.size } }

    /** Runs [check] on each page the r12 panels flagged, at 2.0, one after another in one composition. */
    private fun largePages(testing: () -> Boolean = { false }, check: (Int) -> Unit) {
        val connection = RvPolicyData.connection
        val pages: List<@Composable () -> Unit> = listOf(
            { RvActivityScreen(rvSampleActivity(), onAction = {}, onConnect = {}) },
            { RvActivityScreen(rvSampleActivity().copy(mode = Mode.Direct), onAction = {}, onConnect = {}) },
            { RvPolicyScreen(RvPolicyState(mode = RvRouteMode.Rule), onChange = {}) },
            { RvPolicyRulePage(connection, RvQuickRule(), onChange = {}, onBack = {}, onApply = {}) },
            { RvPolicyGroupPage(RvPolicyData.streaming, onBack = {}, onSelect = {}, onTest = {}) },
            { RvNodePicker(RvData.nodes, RvData.subscriptions, RvData.CURRENT_ID, onPick = {}, onDetail = {}, onClose = {}) },
            { RvNodePickerPage(RvData.nodes, RvData.subscriptions, RvData.CURRENT_ID, RvNodeSort.Region, onSort = {}, onPick = {}, onBack = {}, onTest = {}, onSearch = {}) },
        )
        var page by mutableStateOf(0)
        large { CompositionLocalProvider(LocalRvTesting provides testing(), pages[page]) }
        pages.indices.forEach {
            page = it
            compose.waitForIdle()
            check(it)
        }
    }

    private fun large(content: @Composable () -> Unit) = compose.setContent {
        CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, RV_FS200)) {
            RvTheme(seedColorScheme(Seed.Cyan.argb, false), false, content)
        }
    }

    /**
     * Each text node showing [text] shows it whole: no line ends in an ellipsis, nothing runs past its box, and it reads
     * well ([rvReadsWell]: no word broken, no last line of one or two letters; rule 14).
     */
    private fun assertWhole(text: String) {
        val nodes = compose.onAllNodes(shows(text), useUnmergedTree = true).fetchSemanticsNodes()
        assertTrue("$text is not shown", nodes.isNotEmpty())
        nodes.forEach { assertWhole(it, text) }
    }

    private fun assertWhole(node: SemanticsNode, text: String) {
        val layout = layout(node)
        val cut = (0 until layout.lineCount).any(layout::isLineEllipsized) || layout.hasVisualOverflow
        assertFalse("$text is cut: ${layout.lineCount} lines in ${node.size.width} px", cut)
        val last = layout.lineCount - 1
        val line = layout.layoutInput.text.text.substring(layout.getLineStart(last), layout.getLineEnd(last))
        assertTrue("$text does not read well, its last line '$line'", rvReadsWell(layout))
    }

    /** A text node showing [text], whatever word joiner the widow rule put in it. */
    private fun shows(text: String) = SemanticsMatcher("shows $text") { node ->
        node.config.getOrNull(SemanticsProperties.Text)?.any { it.text.replace(WORD_JOINER, "").replace(ZERO_WIDTH_SPACE, "") == text } == true
    }

    private companion object {
        const val WORD_JOINER = "\u2060"

        /** Where RvText lets a value break at its punctuation. */
        const val ZERO_WIDTH_SPACE = "\u200B"

        /** [largePages]' 活動 (both modes) and pull-down picker. */
        val ONE_END_PAGES = setOf(0, 1, 5)
    }
}
