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
import dev.doona.android.designsystem.components.DoonaIcon
import dev.doona.android.designsystem.components.DropDownGlyph
import dev.doona.android.designsystem.components.LayoutRole
import dev.doona.android.designsystem.components.LayoutRoleKey
import dev.doona.android.designsystem.theme.DoonaTheme
import dev.doona.android.designsystem.theme.LocalFrameInsets
import dev.doona.android.testing.FrameInsets
import dev.doona.android.testing.InkInsets
import dev.doona.android.testing.SeedTheme
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
import dev.doona.android.icons.R as IconsR

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
            CompositionLocalProvider(LocalFrameInsets provides FrameInsets) { SeedTheme { screenCases().first { it.name == name }.content() } }
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

    @Test fun pickerChevronsHaveNoInset() = checkChevrons("activity")

    /** The picker's arrow box and the same glyph drawn whole, as an icon of the arrow's size. */
    private fun render(chevron: Modifier = Modifier): Pair<InkInsets, InkInsets> {
        compose.setContent {
            SeedTheme {
                val tint = DoonaTheme.colors.text
                val size = DoonaTheme.size.inlineGlyph
                val whole = IconsR.drawable.sym_keyboard_arrow_down
                Column(Modifier.background(DoonaTheme.colors.page).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    DropDownGlyph(tint, chevron)
                    Box(Modifier.testTag("whole")) { DoonaIcon(whole, tint, size = size) }
                }
            }
        }
        compose.drawFully()
        val chevron = compose.onNode(SemanticsMatcher("is a chevron") { it.config.getOrNull(LayoutRoleKey) == LayoutRole.Chevron }, useUnmergedTree = true)
        return requireNotNull(chevron.inkInsets(DENSITY)) { "the chevron drew nothing" } to requireNotNull(compose.onNodeWithTag("whole", useUnmergedTree = true).inkInsets(DENSITY)) { "the icon drew nothing" }
    }

    @Test
    fun theDownChevronHoldsTheWholeGlyph() {
        val (box, whole) = render()
        assertTrue("down chevron: ${box.describe()}", box.max() <= INK_TOLERANCE)
        assertFalse("down chevron box ${box.inkWidth} x ${box.inkHeight} cuts the glyph, which is ${whole.inkWidth} x ${whole.inkHeight}", box.clippedAgainst(whole, INK_TOLERANCE))
    }

    /** A glyph cut off at its box edge has no ink inside the box, so only the comparison with the whole glyph sees it. */
    @Test
    fun aChevronInABoxTooSmallIsClipped() {
        val (box, whole) = render(Modifier.size(width = 6.dp, height = 3.dp))
        assertTrue("the clipped box has no ink inside its edges: ${box.describe()}", box.max() <= INK_TOLERANCE)
        assertTrue("a ${box.inkWidth} x ${box.inkHeight} box against ${whole.inkWidth} x ${whole.inkHeight}", box.clippedAgainst(whole, INK_TOLERANCE))
    }
}
