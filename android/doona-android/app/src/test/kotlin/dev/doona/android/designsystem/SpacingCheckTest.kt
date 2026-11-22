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
import dev.doona.android.testing.SpacingRules
import dev.doona.android.testing.inkInsets
import dev.doona.android.testing.layoutTree
import dev.doona.android.testing.screenCases
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

private const val PHONE = "zh-rTW-w412dp-h915dp-notnight-xxhdpi"
private const val WIDTH = 412f
private const val DENSITY = 3f
private const val INK_TOLERANCE = 0.67f

/** Renders each screen on [dev.doona.android.engine.fake.FakeEngine] data and asserts [SpacingRules] on its layout. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = PHONE)
class SpacingCheckTest {
    @get:Rule
    val compose = createComposeRule()

    private fun check(name: String) {
        val case = screenCases().first { it.name == name }
        compose.setContent {
            CompositionLocalProvider(LocalFrameInsets provides FrameInsets) { DoonaTheme { case.content() } }
        }
        val violations = SpacingRules.check(compose.layoutTree(), WIDTH)
        assertTrue("$name breaks the spacing rules:\n" + violations.joinToString("\n"), violations.isEmpty())
    }

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

    /** A chevron is drawn to the edges of its box, so that the box can sit on a content edge or a padding. */
    @Test fun pickerChevronsHaveNoInset() = checkChevrons("group")

    @Test fun cardChevronsHaveNoInset() = checkChevrons("policies")

    @Test fun activity() = check("activity")

    @Test fun activityError() = check("activity-error")

    @Test fun policies() = check("policies")

    @Test fun group() = check("group")

    @Test fun settings() = check("settings")
}
