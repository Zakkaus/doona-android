package dev.doona.android.designsystem

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.doona.android.designsystem.components.LayoutRole
import dev.doona.android.designsystem.components.LayoutRoleKey
import dev.doona.android.designsystem.theme.DoonaTheme
import dev.doona.android.designsystem.theme.LocalFrameInsets
import dev.doona.android.testing.FrameInsets
import dev.doona.android.testing.inkInsets
import dev.doona.android.testing.screenCases
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

private const val DENSITY = 3f
private const val INK_TOLERANCE = 0.67f

/** A chevron is drawn to the edges of its box, so that the box can sit on a content edge or a padding. */
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
        val chevrons = compose.onAllNodes(SemanticsMatcher("is a chevron") { it.config.getOrNull(LayoutRoleKey) == LayoutRole.Chevron }, useUnmergedTree = true)
        val count = chevrons.fetchSemanticsNodes().size
        assertTrue("$name shows chevrons", count > 0)
        val insets = (0 until count).mapNotNull { chevrons[it].inkInsets(DENSITY)?.let { ink -> "chevron $it: ${ink.describe()}" to ink.max() } }
        val off = insets.filter { it.second > INK_TOLERANCE }.map { it.first }
        assertTrue("chevrons of $name draw inside their box:\n" + off.joinToString("\n"), off.isEmpty())
    }

    @Test fun pickerChevronsHaveNoInset() = checkChevrons("group")

    @Test fun cardChevronsHaveNoInset() = checkChevrons("policies")
}
