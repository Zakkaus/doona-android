package dev.doona.android.designsystem

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.doona.android.designsystem.theme.DoonaTheme
import dev.doona.android.designsystem.theme.LocalFrameInsets
import dev.doona.android.testing.FrameInsets
import dev.doona.android.testing.Landscape
import dev.doona.android.testing.Light
import dev.doona.android.testing.SpacingRules
import dev.doona.android.testing.Variant
import dev.doona.android.testing.Wide
import dev.doona.android.testing.layoutTree
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
 * and asserts [SpacingRules] on its layout.
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
            CompositionLocalProvider(LocalFrameInsets provides FrameInsets) { DoonaTheme { case.content() } }
        }
        val violations = SpacingRules.check(compose.layoutTree())
        assertTrue("$name in the ${window.name} window breaks the spacing rules:\n" + violations.joinToString("\n"), violations.isEmpty())
    }

    companion object {
        @JvmStatic
        @Parameters(name = "{0}-{1}")
        fun parameters(): List<Array<Any>> = screenCases().flatMap { case -> listOf(Light, Wide, Landscape).map { arrayOf(case.name, it) } }
    }
}
