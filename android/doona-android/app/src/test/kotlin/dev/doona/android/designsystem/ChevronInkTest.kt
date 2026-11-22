package dev.doona.android.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.doona.android.R
import dev.doona.android.designsystem.components.ChevronDirection
import dev.doona.android.designsystem.components.DoonaChevron
import dev.doona.android.designsystem.components.DoonaIcon
import dev.doona.android.designsystem.components.LayoutRole
import dev.doona.android.designsystem.components.LayoutRoleKey
import dev.doona.android.designsystem.theme.DoonaTheme
import dev.doona.android.designsystem.theme.LocalFrameInsets
import dev.doona.android.testing.FrameInsets
import dev.doona.android.testing.InkInsets
import dev.doona.android.testing.drawFully
import dev.doona.android.testing.inkInsets
import dev.doona.android.testing.screenCases
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

private const val DENSITY = 3f
private const val INK_TOLERANCE = 0.67f

/**
 * A chevron is drawn to the edges of its box, so that the box can sit on a content edge or a padding, and its box holds
 * the whole glyph: ink at a box edge is not enough, since a glyph cut off by a box that is too small also touches it.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rTW-w412dp-h915dp-notnight-xxhdpi")
class ChevronInkTest {
    @get:Rule
    val compose = createComposeRule()

    private fun checkChevrons(name: String) {
        compose.setContent {
            CompositionLocalProvider(LocalFrameInsets provides FrameInsets) { DoonaTheme { screenCases().first { it.name == name }.content() } }
        }
        compose.drawFully()
        val chevrons = compose.onAllNodes(SemanticsMatcher("is a chevron") { it.config.getOrNull(LayoutRoleKey) == LayoutRole.Chevron }, useUnmergedTree = true)
        val count = chevrons.fetchSemanticsNodes().size
        assertTrue("$name shows chevrons", count > 0)
        val insets = (0 until count).mapNotNull { chevrons[it].inkInsets(DENSITY)?.let { ink -> "chevron $it: ${ink.describe()}" to ink.max() } }
        assertTrue("$name: only ${insets.size} of $count chevrons drew anything", insets.size == count)
        val off = insets.filter { it.second > INK_TOLERANCE }.map { it.first }
        assertTrue("chevrons of $name draw inside their box:\n" + off.joinToString("\n"), off.isEmpty())
    }

    @Test fun pickerChevronsHaveNoInset() = checkChevrons("group")

    @Test fun cardChevronsHaveNoInset() = checkChevrons("policies")

    /** The chevron's box and the same glyph drawn whole, as an icon of the chevron's size, for each direction. */
    private fun render(direction: ChevronDirection, chevron: Modifier = Modifier): Pair<InkInsets, InkInsets> {
        compose.setContent {
            DoonaTheme {
                val tint = DoonaTheme.colors.text.primary
                val size = if (direction == ChevronDirection.Down) DoonaTheme.size.iconInline else DoonaTheme.size.iconSmall
                val whole = if (direction == ChevronDirection.Down) R.drawable.ic_chevron_down else R.drawable.ic_chevron_right
                Column(Modifier.background(DoonaTheme.colors.window).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    DoonaChevron(direction, tint, chevron)
                    Box(Modifier.testTag("whole")) { DoonaIcon(whole, tint, size = size) }
                }
            }
        }
        compose.drawFully()
        val chevron = compose.onNode(SemanticsMatcher("is a chevron") { it.config.getOrNull(LayoutRoleKey) == LayoutRole.Chevron }, useUnmergedTree = true)
        return requireNotNull(chevron.inkInsets(DENSITY)) { "the chevron drew nothing" } to requireNotNull(compose.onNodeWithTag("whole", useUnmergedTree = true).inkInsets(DENSITY)) { "the icon drew nothing" }
    }

    private fun chevronHoldsTheWholeGlyph(direction: ChevronDirection) {
        val (box, whole) = render(direction)
        assertTrue("$direction chevron: ${box.describe()}", box.max() <= INK_TOLERANCE)
        assertFalse("$direction chevron box ${box.inkWidth} x ${box.inkHeight} cuts the glyph, which is ${whole.inkWidth} x ${whole.inkHeight}", box.clippedAgainst(whole, INK_TOLERANCE))
    }

    @Test fun theDownChevronHoldsTheWholeGlyph() = chevronHoldsTheWholeGlyph(ChevronDirection.Down)

    @Test fun theRightChevronHoldsTheWholeGlyph() = chevronHoldsTheWholeGlyph(ChevronDirection.Right)

    /** A glyph cut off at its box edge has no ink inside the box, so only the comparison with the whole glyph sees it. */
    @Test
    fun aChevronInABoxTooSmallIsClipped() {
        val (box, whole) = render(ChevronDirection.Down, Modifier.size(width = 8.dp, height = 4.dp))
        assertTrue("the clipped box has no ink inside its edges: ${box.describe()}", box.max() <= INK_TOLERANCE)
        assertTrue("a ${box.inkWidth} x ${box.inkHeight} box against ${whole.inkWidth} x ${whole.inkHeight}", box.clippedAgainst(whole, INK_TOLERANCE))
    }
}
