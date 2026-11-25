package dev.doona.android.prototype.rv

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.doona.android.R
import dev.doona.android.designsystem.rv.Rv
import dev.doona.android.designsystem.rv.RvCaption
import dev.doona.android.designsystem.rv.RvChrome
import dev.doona.android.designsystem.rv.RvPart
import dev.doona.android.designsystem.rv.RvRangeRow
import dev.doona.android.designsystem.rv.RvRowPart
import dev.doona.android.designsystem.rv.RvSegmented
import dev.doona.android.designsystem.rv.rvPress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The chosen segment or range chip is bold after a check, and choosing moves no pill (owner, 2026-11-25, q-a11y-selected). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RV_EN)
class RvChosenTest {
    @get:Rule
    val compose = createComposeRule()

    private val options = listOf("Both", "TCP", "UDP")
    private val ranges = listOf("1H", "1D", "1W", "1M", "1Y")

    /** The choice (a tab of the merged tree) that holds a label of [text]. */
    private fun tab(text: String): SemanticsNode =
        compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab) and hasText(text)).fetchSemanticsNode()

    private fun weightOf(text: String): FontWeight? {
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNode(hasText(text), useUnmergedTree = true).fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
        return layouts.single().layoutInput.style.fontWeight
    }

    /** How many glyphs (the check, or a segment's own glyph) sit inside the choice that holds [text]. */
    private fun checks(text: String): Int = compose.onAllNodesWithTag(RvRowPart.GLYPH, useUnmergedTree = true).fetchSemanticsNodes().count { glyph ->
        val box = tab(text).boundsInRoot
        box.contains(glyph.boundsInRoot.center)
    }

    @Test
    fun theChosenSegmentIsBoldAfterACheckAndNoSegmentMoves() {
        var chosen by mutableIntStateOf(0)
        compose.rvContent(dark = false) { RvSegmented(options, chosen, { chosen = it }) }
        val before = options.map { tab(it).boundsInRoot }
        assertEquals(FontWeight.Bold, weightOf(options[0]))
        assertNotEquals("the others are not bold", FontWeight.Bold, weightOf(options[1]))
        assertEquals(listOf(1, 0, 0), options.map { checks(it) })
        compose.runOnIdle { chosen = 2 }
        compose.waitForIdle()
        assertEquals("no segment changes its size or place", before, options.map { tab(it).boundsInRoot })
        assertEquals(FontWeight.Bold, weightOf(options[2]))
        assertNotEquals(FontWeight.Bold, weightOf(options[0]))
        assertEquals(listOf(0, 0, 1), options.map { checks(it) })
    }

    @Test
    fun theChosenRangeChipIsBoldAfterACheckAndNoChipMoves() {
        var chosen by mutableIntStateOf(1)
        compose.rvContent(dark = false) { RvRangeRow(ranges, chosen, { chosen = it }) }
        val before = compose.onAllNodesWithTag(RvPart.FRAME, useUnmergedTree = true).fetchSemanticsNodes().map { it.boundsInRoot }
        assertEquals(ranges.size, before.size)
        assertEquals(FontWeight.Bold, weightOf(ranges[1]))
        assertEquals(listOf(0, 1, 0, 0, 0), ranges.map { checks(it) })
        compose.runOnIdle { chosen = 4 }
        compose.waitForIdle()
        val after = compose.onAllNodesWithTag(RvPart.FRAME, useUnmergedTree = true).fetchSemanticsNodes().map { it.boundsInRoot }
        assertEquals("no chip changes its width or place", before, after)
        assertEquals(listOf(0, 0, 0, 0, 1), ranges.map { checks(it) })
    }
}
