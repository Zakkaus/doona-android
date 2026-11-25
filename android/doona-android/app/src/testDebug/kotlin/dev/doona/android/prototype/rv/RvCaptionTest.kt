package dev.doona.android.prototype.rv

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import dev.doona.android.R
import dev.doona.android.designsystem.rv.Rv
import dev.doona.android.designsystem.rv.RvCaption
import dev.doona.android.designsystem.rv.RvText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A page header's caption wraps instead of cutting at one line (owner, 2026-11-25, q-a11y-caption, LAYOUT-RULES.md 2026-11-26).
 * The gate reads each caption's text layout: a caption longer than its width must take more than one line, and its last
 * line must not end in an ellipsis. [aCaptionCutAtOneLineFailsTheGate] plants the old caption (RvText's one-line default)
 * and requires the same reading to flag it, so the green test cannot pass on a reading that sees nothing.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RV_EN)
class RvCaptionTest {
    @get:Rule
    val compose = createComposeRule()

    /** What each caption of [TEXT] drew: its line count, whether its last line ends in an ellipsis, and whether it shows all of [TEXT]. */
    private data class Drawn(val lines: Int, val ellipsized: Boolean, val whole: Boolean) {
        val wraps get() = lines > 1 && !ellipsized && whole
    }

    private fun drawn(fontScale: Float, content: @Composable () -> Unit): List<Drawn> {
        compose.rvContent(dark = false, fontScale = fontScale) { Box(Modifier.size(300.dp, 600.dp)) { Column { content() } } }
        // A caption cut at a word shows a shorter text, so the nodes are found by the text's start.
        return compose.onAllNodes(hasText(TEXT.take(17), substring = true), useUnmergedTree = true).fetchSemanticsNodes().map { node ->
            val layouts = mutableListOf<TextLayoutResult>()
            node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
            val layout = layouts.single()
            val shown = node.config[SemanticsProperties.Text].joinToString("") { it.text }
            Drawn(layout.lineCount, layout.isLineEllipsized(layout.lineCount - 1), shown == TEXT)
        }
    }

    private fun captions(scale: Float) = drawn(scale) {
        RvCaption(TEXT)
        RvCaption(TEXT, R.drawable.rv_sym_info)
    }

    private fun assertWrapped(scale: Float) {
        val lines = captions(scale)
        assertEquals(2, lines.size)
        lines.forEach { assertTrue("a long caption wraps and is never cut at $scale: $it", it.wraps) }
    }

    @Test
    fun aCaptionWrapsInsteadOfCuttingAtOneLine() = assertWrapped(1f)

    @Test
    fun aCaptionWrapsInsteadOfCuttingAtOneLineAtTwiceTheFontSize() = assertWrapped(2f)

    @Test
    fun aCaptionCutAtOneLineFailsTheGate() {
        val planted = drawn(1f) { RvText(TEXT, Rv.type.rowSub, color = Rv.colors.textSecondary) }
        assertEquals(1, planted.size)
        assertFalse("a caption on RvText's one-line default must be flagged: $planted", planted.single().wraps)
        assertEquals("it is the one-line cut that is flagged: $planted", 1, planted.single().lines)
    }

    private companion object {
        const val TEXT = "Based on revision 123456, applied on a Wednesday afternoon over the home network, by the owner's phone"
    }
}
