package dev.doona.android.designsystem

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.doona.android.designsystem.rv.LocalFrameInsets
import dev.doona.android.testing.FrameInsets
import dev.doona.android.testing.Landscape
import dev.doona.android.testing.Light
import dev.doona.android.testing.SeedTheme
import dev.doona.android.testing.SpacingRules
import dev.doona.android.testing.Variant
import dev.doona.android.testing.Wide
import dev.doona.android.testing.readWhileScrolling
import dev.doona.android.testing.screenCases
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.ParameterizedRobolectricTestRunner.Parameters
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders each screen on [dev.doona.android.engine.fake.FakeEngine] data, on a phone, in a wide window and on its side,
 * and asserts [SpacingRules] on its layout at every scroll offset, so what lies below the fold is checked as well.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rTW-w412dp-h915dp-notnight-xhdpi")
class SpacingCheckTest(private val name: String, private val window: Variant) {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun screenKeepsTheSpacingRules() {
        RuntimeEnvironment.setQualifiers(window.qualifiers)
        val case = screenCases().first { it.name == name }
        compose.setContent {
            CompositionLocalProvider(LocalFrameInsets provides FrameInsets) { SeedTheme { case.content() } }
        }
        val page = requireNotNull(case.layout)
        val reads = compose.readWhileScrolling { tree -> SpacingRules.check(tree, page) to SpacingRules.touch(tree) }
        val touch = reads.map { it.second }
        val violations = reads.first().first + touch.flatMap { it.violations }.distinct() + SpacingRules.unseen(touch)
        assertTrue("$name in the ${window.name} window breaks the spacing rules:\n" + violations.joinToString("\n"), violations.isEmpty())
    }

    companion object {
        @JvmStatic
        @Parameters(name = "{0}-{1}")
        fun parameters(): List<Array<Any>> = screenCases().flatMap { case -> listOf(Light, Wide, Landscape).map { arrayOf(case.name, it) } }
    }
}
