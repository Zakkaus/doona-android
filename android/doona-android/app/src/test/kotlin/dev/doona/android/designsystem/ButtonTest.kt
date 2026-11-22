package dev.doona.android.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.doona.android.R
import dev.doona.android.designsystem.components.DoonaButton
import dev.doona.android.designsystem.components.DoonaButtonStyle
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
                DoonaButton("Destructive", onClick = {}, style = DoonaButtonStyle.Destructive)
            }
        }
        listOf("Primary", "Disabled", "Secondary", "Destructive").forEach { fills[it] = fillAt(it, FILL_INSET) }
        assertEquals(hex(c.accent), hex(fills.getValue("Primary")))
        assertEquals("a disabled primary button has the control fill, not the accent", hex(c.control), hex(fills.getValue("Disabled")))
        assertEquals("a secondary button has no fill, only an outline", hex(c.surface), hex(fills.getValue("Secondary")))
        assertEquals(hex(c.destructive), hex(fills.getValue("Destructive")))
    }

    /** A disabled secondary button draws its outline in the muted label colour, not at the strength of an enabled one. */
    @Test
    fun aDisabledSecondaryButtonHasADimmedOutline() {
        val c = DoonaColors(Dawn)
        compose.setDoonaContent {
            Column(Modifier.fillMaxSize().background(c.surface).padding(space.gutter), verticalArrangement = Arrangement.spacedBy(space.block)) {
                DoonaButton("Enabled", onClick = {}, style = DoonaButtonStyle.Secondary)
                DoonaButton("Disabled", onClick = {}, style = DoonaButtonStyle.Secondary, enabled = false)
            }
        }
        assertEquals(hex(c.strongLine), hex(fillAt("Enabled", OUTLINE_INSET)))
        assertEquals(hex(c.text.disabled), hex(fillAt("Disabled", OUTLINE_INSET)))
    }

    private fun lines(label: String): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(label, useUnmergedTree = true).fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
        return results.single()
    }

    /** A label too long for one line takes a second, and ends in an ellipsis only past that: the button grows to hold both lines. */
    @Test
    fun aLongLabelWrapsOntoASecondLineBeforeItIsCut() {
        val two = "Add the subscription and test every node in it"
        val many = "Add the subscription and test every node in it, then remove the old one and every group that used it"
        compose.setDoonaContent {
            Column(Modifier.width(CROWDED)) {
                DoonaButton("Short", onClick = {}, Modifier.fillMaxWidth())
                DoonaButton(two, onClick = {}, Modifier.fillMaxWidth())
                DoonaButton(many, onClick = {}, Modifier.fillMaxWidth())
            }
        }
        assertEquals(1, lines("Short").lineCount)
        assertEquals(2, lines(two).lineCount)
        assertEquals("wrapped, not cut", false, lines(two).hasVisualOverflow)
        assertEquals(2, lines(many).lineCount)
        assertEquals("a third line is an ellipsis", true, lines(many).isLineEllipsized(1))
        compose.onNodeWithText(two).assertHeightIsAtLeast(40.dp)
        compose.onNodeWithText("Short").assertHeightIsEqualTo(DoonaTheme.size.control)
    }

    private companion object {
        /** On the outline, 2 dp wide, at the middle of the button's start edge. */
        const val OUTLINE_INSET = 1f

        /** Narrow enough for a long label to need a second line. */
        val CROWDED = 240.dp

        /** Inside a pill's round end, where the fill is: a primary button is 36 high with a 18 dp radius. */
        const val FILL_INSET = 6f
    }
}
