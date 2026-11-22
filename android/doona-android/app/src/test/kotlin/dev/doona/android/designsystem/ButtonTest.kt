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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
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
import dev.doona.android.designsystem.tokens.DoonaPalette
import dev.doona.android.designsystem.tokens.Moon
import dev.doona.android.testing.Dark
import dev.doona.android.testing.Light
import dev.doona.android.testing.SpacingRules
import dev.doona.android.testing.Variant
import dev.doona.android.testing.drawFully
import dev.doona.android.testing.inkColour
import dev.doona.android.testing.layoutTree
import dev.doona.android.testing.setDoonaContent
import dev.doona.android.testing.setVariantContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs
import kotlin.math.hypot

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
        compose.drawFully()
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

    /**
     * A disabled secondary button draws its outline plainly fainter than an enabled one's, in both themes, where the
     * strong line is only a step off the fill: not at the same strength beside a muted label, and not gone.
     */
    private fun disabledOutlineAgainstEnabled(variant: Variant, palette: DoonaPalette) {
        val c = DoonaColors(palette)
        compose.setVariantContent(variant) {
            Column(Modifier.fillMaxSize().background(c.surface).padding(space.gutter), verticalArrangement = Arrangement.spacedBy(space.block)) {
                DoonaButton("Enabled", onClick = {}, style = DoonaButtonStyle.Secondary)
                DoonaButton("Disabled", onClick = {}, style = DoonaButtonStyle.Secondary, enabled = false)
            }
        }
        val enabled = fillAt("Enabled", OUTLINE_INSET)
        val disabled = fillAt("Disabled", OUTLINE_INSET)
        assertEquals(hex(c.strongLine), hex(enabled))
        assertTrue("the dimmed outline still shows against the sheet, %.3f from it".format(disabled.apart(c.surface)), disabled.apart(c.surface) >= OUTLINE_SHOWS)
        assertTrue("and is plainly fainter than the enabled one: %.3f against %.3f".format(disabled.apart(c.surface), enabled.apart(c.surface)), disabled.apart(c.surface) <= OUTLINE_FAINTER * enabled.apart(c.surface))
    }

    @Test
    fun aDisabledSecondaryButtonHasADimmedOutlineInLight() = disabledOutlineAgainstEnabled(Light, Dawn)

    @Test
    fun aDisabledSecondaryButtonHasADimmedOutlineInDark() = disabledOutlineAgainstEnabled(Dark, Moon)

    /** What a text button draws its label in is the accent text role in both themes, read off the rendered pixels. */
    private fun textButtonInk(variant: Variant, palette: DoonaPalette) {
        val c = DoonaColors(palette)
        compose.setVariantContent(variant) {
            Column(Modifier.fillMaxSize().background(c.surface).padding(space.gutter)) { DoonaButton("HIH", onClick = {}, style = DoonaButtonStyle.Text) }
        }
        compose.drawFully()
        val label = compose.onNodeWithText("HIH", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val ink = compose.onRoot().captureToImage().toPixelMap().inkColour(label, c.surface)
        assertTrue("the label is drawn in ${hex(ink)}, not the accent text ${hex(c.text.accent)}", ink.apart(c.text.accent) <= INK_MATCH)
        assertTrue("and that colour holds 4.5:1 on the sheet", contrast(ink, c.surface) >= 4.5f)
    }

    @Test
    fun aTextButtonDrawsItsLabelInTheAccentTextInLight() = textButtonInk(Light, Dawn)

    @Test
    fun aTextButtonDrawsItsLabelInTheAccentTextInDark() = textButtonInk(Dark, Moon)

    private fun contrast(a: Color, b: Color): Float {
        val (hi, lo) = listOf(a.luminance(), b.luminance()).sortedDescending()
        return (hi + 0.05f) / (lo + 0.05f)
    }

    private fun Color.apart(o: Color) = maxOf(abs(red - o.red), abs(green - o.green), abs(blue - o.blue))

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

    private fun inDp(rect: Rect) = Rect(rect.left / compose.density.density, rect.top / compose.density.density, rect.right / compose.density.density, rect.bottom / compose.density.density)

    /** The air a label has inside its button: above, below, and from the start edge, in dp. */
    private class Air(val top: Float, val bottom: Float, val start: Float, val height: Float) {
        /** How far the label's top corner is from the arc of a pill's round end: the pill's half height less the corner's distance from the arc's centre. */
        val cornerGap get() = (height / 2) - hypot(height / 2 - start, height / 2 - top)
    }

    private fun airOf(label: String): Air {
        val button = inDp(compose.onNodeWithText(label).fetchSemanticsNode().boundsInRoot)
        val text = inDp(compose.onNodeWithText(label, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot)
        return Air(text.top - button.top, button.bottom - text.bottom, text.left - button.left, button.height)
    }

    /**
     * A label that wraps has the air above and below it that one line has, so a two-line label does not sit 4 dp from
     * the border where a single line has 8; a pill's wrapped label keeps its corner as far from the round end as a
     * single line's, which takes more room at the start than the 12 dp of a single line, and an outlined button, whose
     * corners are not round, keeps 12.
     */
    @Test
    fun aWrappedLabelKeepsTheAirOfASingleLine() {
        val two = "Add the subscription and test every node in it"
        val other = "Pick the subscription and test every node in it"
        compose.setDoonaContent {
            Column(Modifier.width(CROWDED)) {
                DoonaButton("Short", onClick = {})
                DoonaButton(two, onClick = {}, Modifier.fillMaxWidth())
                DoonaButton(other, onClick = {}, Modifier.fillMaxWidth(), style = DoonaButtonStyle.Secondary)
            }
        }
        val single = airOf("Short")
        val pill = airOf(two)
        val outlined = airOf(other)
        assertEquals("a single line has 8 dp above", 8f, single.top, 0.5f)
        assertEquals("a wrapped pill has the same above", single.top, pill.top, 0.5f)
        assertEquals("and below", single.bottom, pill.bottom, 0.5f)
        assertEquals("a wrapped outlined button has the same above", single.top, outlined.top, 0.5f)
        assertEquals("and below", single.bottom, outlined.bottom, 0.5f)
        assertTrue("the wrapped pill's corner is as far from the round end (%.1f dp) as a single line's (%.1f)".format(pill.cornerGap, single.cornerGap), pill.cornerGap >= single.cornerGap - 0.5f)
        assertTrue("which takes more than the single line's start inset: ${pill.start} dp", pill.start > single.start + 1f)
        assertEquals("an outlined button's corners are not round, so it keeps the start inset", single.start, outlined.start, 0.5f)
        assertEquals(emptyList<String>(), SpacingRules.controls(compose.layoutTree()))
    }

    private companion object {
        /** On the outline, 2 dp wide, at the middle of the button's start edge. */
        const val OUTLINE_INSET = 1f

        /** Narrow enough for a long label to need a second line. */
        val CROWDED = 240.dp

        /** Inside a pill's round end, where the fill is: a primary button is 36 high with a 18 dp radius. */
        const val FILL_INSET = 6f

        /** The most a pixel of a label's ink may differ from its colour: two levels of 255. */
        const val INK_MATCH = 0.01f

        /** The disabled outline is at most this part of the enabled one's distance from the fill, and at least [OUTLINE_SHOWS] of the range from it. */
        const val OUTLINE_FAINTER = 0.4f
        const val OUTLINE_SHOWS = 0.02f
    }
}
