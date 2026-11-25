package dev.doona.android.designsystem

import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.v2.createComposeRule
import dev.doona.android.designsystem.components.LayoutRole
import dev.doona.android.designsystem.components.LayoutRoleKey
import dev.doona.android.designsystem.theme.DoonaTheme
import dev.doona.android.testing.Light
import dev.doona.android.testing.drawFully
import dev.doona.android.testing.inkInsets
import dev.doona.android.testing.screenCases
import dev.doona.android.testing.setVariantContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.ParameterizedRobolectricTestRunner.Parameters
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The first and the last thing the top bar draws sit on the content gutter by their ink, as the content below them
 * does: the glyphs are cut out of boxes with different margins (the back arrow starts 3 dp inside its box, the dots of
 * the menu end 10 dp inside theirs), so a layout that aligns the boxes leaves the visible edges ragged.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rTW-w412dp-h915dp-notnight-xhdpi")
class TopBarInkTest(private val name: String, private val hasActions: Boolean) {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun theVisibleEdgesOfTheBarSitOnTheGutter() {
        val case = screenCases().first { it.name == name }
        compose.setVariantContent(Light) { case.content() }
        compose.drawFully()
        val ink = compose.onNode(SemanticsMatcher.expectValue(LayoutRoleKey, LayoutRole.TopBar)).inkInsets(compose.density.density)
        assertNotNull("$name: the top bar draws nothing", ink)
        val gutter = DoonaTheme.spacing.gutter.value
        assertEquals("$name: the first glyph's ink starts this far from the bar's start", gutter, ink!!.left, 0.6f)
        if (hasActions) assertEquals("$name: the last glyph's ink ends this far from the bar's end", gutter, ink.right, 0.6f)
    }

    companion object {
        @JvmStatic
        @Parameters(name = "{0}")
        fun parameters(): List<Array<Any>> = listOf(arrayOf("activity", true), arrayOf("policies", true), arrayOf("group", false), arrayOf("subscriptions", true))
    }
}
