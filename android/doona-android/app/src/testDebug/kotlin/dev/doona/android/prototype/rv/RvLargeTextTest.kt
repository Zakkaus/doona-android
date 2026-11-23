package dev.doona.android.prototype.rv

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import dev.doona.android.R
import dev.doona.android.engine.Mode
import dev.doona.android.prototype.rv.data.RvData
import dev.doona.android.prototype.rv.data.RvNodeSort
import dev.doona.android.prototype.rv.data.RvRouteMode
import dev.doona.android.prototype.rv.nodes.RvNodePicker
import dev.doona.android.prototype.rv.nodes.RvNodePickerPage
import dev.doona.android.prototype.rv.nodes.SEPARATOR
import dev.doona.android.prototype.rv.policy.RvPolicyData
import dev.doona.android.prototype.rv.policy.RvPolicyRulePage
import dev.doona.android.prototype.rv.policy.RvPolicyScreen
import dev.doona.android.prototype.rv.policy.RvPolicyState
import dev.doona.android.prototype.rv.policy.RvQuickRule
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
        large { RvNodePickerPage(RvData.nodes, RvData.subscriptions, RvData.CURRENT_ID, RvNodeSort.Region, onSort = {}, onPick = {}, onBack = {}, onTest = {}) }
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
        RvNodePickerPage(RvData.nodes, RvData.subscriptions, RvData.CURRENT_ID, RvNodeSort.Region, onSort = {}, onPick = {}, onBack = {}, onTest = {})
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

    private fun large(content: @Composable () -> Unit) = compose.setContent {
        CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, RV_FS200)) {
            RvTheme(seedColorScheme(Seed.Cyan.argb, false), false, content)
        }
    }

    /**
     * Each text node showing [text] shows it whole: no line ends in an ellipsis, nothing runs past its box, and a text of
     * two lines or more does not end on one letter alone (rule 14).
     */
    private fun assertWhole(text: String) {
        val nodes = compose.onAllNodes(shows(text), useUnmergedTree = true).fetchSemanticsNodes()
        assertTrue("$text is not shown", nodes.isNotEmpty())
        for (node in nodes) {
            val layouts = mutableListOf<TextLayoutResult>()
            node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
            val layout = layouts.single()
            val cut = (0 until layout.lineCount).any(layout::isLineEllipsized) || layout.hasVisualOverflow
            assertFalse("$text is cut: ${layout.lineCount} lines in ${node.size.width} px", cut)
            val last = layout.lineCount - 1
            val line = layout.layoutInput.text.text.substring(layout.getLineStart(last), layout.getLineEnd(last))
            assertFalse("$text ends on one letter: '$line'", last > 0 && line.count(Char::isLetterOrDigit) == 1)
        }
    }

    /** A text node showing [text], whatever word joiner the widow rule put in it. */
    private fun shows(text: String) = SemanticsMatcher("shows $text") { node ->
        node.config.getOrNull(SemanticsProperties.Text)?.any { it.text.replace(WORD_JOINER, "") == text } == true
    }

    private companion object {
        const val WORD_JOINER = "⁠"
    }
}
