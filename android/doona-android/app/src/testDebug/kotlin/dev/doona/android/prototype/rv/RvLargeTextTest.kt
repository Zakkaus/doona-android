package dev.doona.android.prototype.rv

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import dev.doona.android.R
import dev.doona.android.prototype.rv.nodes.SEPARATOR
import dev.doona.android.prototype.rv.policy.RvPolicyData
import dev.doona.android.prototype.rv.policy.RvPolicyRulePage
import dev.doona.android.prototype.rv.policy.RvQuickRule
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
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
