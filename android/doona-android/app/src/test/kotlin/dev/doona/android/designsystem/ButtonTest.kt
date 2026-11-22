package dev.doona.android.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.doona.android.R
import dev.doona.android.designsystem.components.DoonaButton
import dev.doona.android.designsystem.components.DoonaButtonStyle
import dev.doona.android.designsystem.components.DoonaDestructiveButton
import dev.doona.android.designsystem.theme.DoonaColors
import dev.doona.android.designsystem.theme.DoonaTheme
import dev.doona.android.designsystem.tokens.Dawn
import dev.doona.android.testing.SpacingRules
import dev.doona.android.testing.layoutTree
import dev.doona.android.testing.setDoonaContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** What a button does and shows: its taps, its disabled and busy states, its size and the fill of each look. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rTW-w412dp-h915dp-notnight-xxhdpi")
class ButtonTest {
    @get:Rule
    val compose = createComposeRule()

    private val space = DoonaTheme.spacing

    private fun hasState(state: String) = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, state)

    @Test
    fun everyStyleTakesATap() {
        val taps = mutableListOf<String>()
        compose.setDoonaContent {
            Column { DoonaButtonStyle.entries.forEach { style -> DoonaButton(style.name, onClick = { taps += style.name }, style = style) } }
        }
        DoonaButtonStyle.entries.forEach { compose.onNodeWithText(it.name).assertIsEnabled().performClick() }
        assertEquals(DoonaButtonStyle.entries.map { it.name }, taps)
    }

    @Test
    fun aDisabledButtonIgnoresTaps() {
        var taps = 0
        compose.setDoonaContent { DoonaButton("Go", onClick = { taps++ }, enabled = false) }
        compose.onNodeWithText("Go").assertIsNotEnabled().performClick()
        assertEquals(0, taps)
    }

    /** Busy means the tap already happened: a second one is ignored, and a screen reader is told why the button is dead. */
    @Test
    fun aBusyButtonIgnoresTapsAndIsInProgress() {
        var taps = 0
        compose.setDoonaContent {
            Column {
                DoonaButton("Go", onClick = { taps++ }, busy = true)
                DoonaButton("Wait", onClick = {})
            }
        }
        compose.onNodeWithText("Go").assertIsNotEnabled().assert(hasState("處理中")).performClick()
        assertEquals(0, taps)
        compose.onNodeWithText("Wait").assert(!hasState("處理中"))
    }

    @Composable
    private fun Buttons(gap: Dp) = Column(verticalArrangement = Arrangement.spacedBy(gap)) {
        Row(horizontalArrangement = Arrangement.spacedBy(space.item)) {
            DoonaButton("One", onClick = {})
            DoonaButton("Two", onClick = {}, style = DoonaButtonStyle.Secondary, icon = R.drawable.ic_edit)
            DoonaButton("Three", onClick = {}, style = DoonaButtonStyle.Text, busy = true)
        }
        DoonaButton("Four", onClick = {}, enabled = false)
    }

    /** 36 dp tall as a control is, hit over 48 dp: side by side an item apart and stacked a block apart they reach into nothing. */
    @Test
    fun buttonsAreAControlHighAndHitOverFortyEight() {
        compose.setDoonaContent { Buttons(space.block) }
        val tree = compose.layoutTree()
        assertEquals(emptyList<String>(), SpacingRules.controls(tree))
        assertEquals(emptyList<String>(), SpacingRules.touch(tree).violations)
    }

    /** Stacked an item apart the 48 dp areas overlap by 4: the rule that keeps a block between stacked buttons sees it. */
    @Test
    fun buttonsStackedAnItemApartReachIntoEachOther() {
        compose.setDoonaContent { Buttons(space.item) }
        val violations = SpacingRules.touch(compose.layoutTree()).violations
        assertTrue(violations.toString(), violations.any { "touch area" in it })
    }

    private fun fillAt(label: String, inset: Float): Color {
        val node = compose.onNodeWithText(label).fetchSemanticsNode().boundsInRoot
        val density = compose.density.density
        return compose.onRoot().captureToImage().toPixelMap()[(node.left + inset * density).toInt(), node.center.y.toInt()]
    }

    private fun hex(c: Color) = "#%08X".format(c.toArgb())

    @Test
    fun eachLookHasItsFill() {
        val c = DoonaColors(Dawn)
        val fills = mutableMapOf<String, Color>()
        compose.setDoonaContent {
            Column(Modifier.fillMaxSize().background(c.surface).padding(space.gutter), verticalArrangement = Arrangement.spacedBy(space.block)) {
                DoonaButton("Primary", onClick = {})
                DoonaButton("Disabled", onClick = {}, enabled = false)
                DoonaButton("Secondary", onClick = {}, style = DoonaButtonStyle.Secondary)
                DoonaDestructiveButton("Destructive", onClick = {})
            }
        }
        listOf("Primary", "Disabled", "Secondary", "Destructive").forEach { fills[it] = fillAt(it, FILL_INSET) }
        assertEquals(hex(c.accent), hex(fills.getValue("Primary")))
        assertEquals("a disabled primary button has the control fill, not the accent", hex(c.control), hex(fills.getValue("Disabled")))
        assertEquals("a secondary button has no fill, only an outline", hex(c.surface), hex(fills.getValue("Secondary")))
        assertEquals(hex(c.destructive), hex(fills.getValue("Destructive")))
    }

    private companion object {
        /** Inside a pill's round end, where the fill is: a primary button is 36 high with a 18 dp radius. */
        const val FILL_INSET = 6f
    }
}
