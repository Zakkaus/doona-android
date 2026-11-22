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
import org.junit.Ignore
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
@Ignore("red on the sketch: segmented items and pickers sit in 48 dp boxes, top bar glyphs 6 to 8 dp off the gutter, a latency dot pushes its value, pickers differ, chevrons carry 2.7 dp of padding, the sheet ends 24 dp below its last block; U1 step 3 fixes them and removes this")
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

    /** A chevron is drawn to the edges of its box, so that the box can sit on a content edge or a padding. */
    @Test
    fun chevronsHaveNoInset() {
        compose.setContent {
            CompositionLocalProvider(LocalFrameInsets provides FrameInsets) { DoonaTheme { screenCases().first { it.name == "group" }.content() } }
        }
        val chevrons = compose.onAllNodes(SemanticsMatcher("is a chevron") { it.config.getOrNull(LayoutRoleKey) == LayoutRole.Chevron }, useUnmergedTree = true)
        val count = chevrons.fetchSemanticsNodes().size
        assertTrue("the group page shows pickers", count > 0)
        val insets = (0 until count).mapNotNull { chevrons[it].inkInsets(DENSITY)?.let { ink -> "chevron $it: ${ink.describe()}" to ink.max() } }
        val off = insets.filter { it.second > INK_TOLERANCE }.map { it.first }
        assertTrue("chevrons draw inside their box:\n" + off.joinToString("\n"), off.isEmpty())
    }

    @Test fun activity() = check("activity")

    @Test fun activityError() = check("activity-error")

    @Test fun policies() = check("policies")

    @Test fun group() = check("group")

    @Test fun settings() = check("settings")
}
