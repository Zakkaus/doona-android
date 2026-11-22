package dev.doona.android.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.doona.android.designsystem.components.DoonaIconAction
import dev.doona.android.designsystem.components.DoonaSearchField
import dev.doona.android.designsystem.components.DoonaTextField
import dev.doona.android.designsystem.components.LayoutRole
import dev.doona.android.designsystem.theme.DoonaColors
import dev.doona.android.designsystem.theme.DoonaTheme
import dev.doona.android.designsystem.tokens.Dawn
import dev.doona.android.designsystem.tokens.TypeScale
import dev.doona.android.testing.FontScale200
import dev.doona.android.testing.Light
import dev.doona.android.testing.Variant
import dev.doona.android.testing.drawFully
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
import dev.doona.android.icons.R as IconsR

/** What the text fields do and say: typing into the state, the label and error a reader hears, the keyboard action, the clear action. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rTW-w412dp-h915dp-notnight-xxhdpi")
class TextFieldTest {
    @get:Rule
    val compose = createComposeRule()

    private val field = hasSetTextAction()

    @Test
    fun typingEditsTheState() {
        val state = TextFieldState()
        compose.setDoonaContent { DoonaTextField(state, label = "URL") }
        compose.onNode(field).performTextInput("https://a.b")
        compose.waitForIdle()
        assertEquals("https://a.b", state.text.toString())
    }

    /** The label, the placeholder while the field is empty, and the error are one node to a reader, which also hears the error as an error. */
    @Test
    fun theLabelThePlaceholderAndTheErrorAreOneNode() {
        val state = TextFieldState()
        compose.setDoonaContent { DoonaTextField(state, label = "URL", placeholder = "https://", error = "Unreachable") }
        compose.onNode(field).assert(hasText("URL")).assert(hasText("https://")).assert(hasText("Unreachable"))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Error, "Unreachable"))
        compose.onNode(field).performTextInput("x")
        compose.onNode(field).assert(!hasText("https://"))
    }

    @Test
    fun aFieldWithoutAnErrorSaysNone() {
        compose.setDoonaContent { DoonaTextField(TextFieldState(), label = "URL") }
        compose.onNode(field).assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Error))
    }

    @Test
    fun theKeyboardActionReachesTheCaller() {
        var done = 0
        compose.setDoonaContent {
            DoonaTextField(TextFieldState(), label = "URL", keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), onImeAction = { done++ })
        }
        compose.onNode(field).performImeAction()
        assertEquals(1, done)
    }

    @Test
    fun aDisabledFieldTakesNoText() {
        val state = TextFieldState("kept")
        compose.setDoonaContent { DoonaTextField(state, label = "URL", enabled = false) }
        compose.onNode(hasText("URL")).assertIsNotEnabled()
        compose.onAllNodes(field).assertCountEquals(0)
        assertEquals("kept", state.text.toString())
    }

    /** The search field is its 48 dp box and no more: it has no label and no error line. */
    @Test
    fun aSearchFieldIsFortyEightHigh() {
        compose.setDoonaContent { DoonaSearchField(TextFieldState("abc"), placeholder = "Search") }
        compose.onNode(field).assertHeightIsEqualTo(48.dp)
    }

    @Test
    fun aLabelledFieldIsAtLeastAsHighAsItsBoxAndLabel() {
        compose.setDoonaContent { DoonaTextField(TextFieldState(), label = "URL") }
        compose.onNode(field).assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun theClearActionAppearsWithTextAndEmptiesTheField() {
        val state = TextFieldState()
        compose.setDoonaContent { Column { DoonaSearchField(state, placeholder = "Search") } }
        compose.onNodeWithContentDescription("清除").assertDoesNotExist()
        compose.onNode(field).performTextInput("abc")
        compose.onNodeWithContentDescription("清除").performClick()
        compose.waitForIdle()
        assertEquals("", state.text.toString())
        compose.onNodeWithContentDescription("清除").assertDoesNotExist()
    }

    @Test
    fun theSearchKeyboardActionIsSearchByDefault() {
        var searched = 0
        compose.setDoonaContent { DoonaSearchField(TextFieldState("abc"), placeholder = "Search", onImeAction = { searched++ }) }
        compose.onNode(field).assert(SemanticsMatcher.expectValue(SemanticsProperties.ImeAction, ImeAction.Search))
        compose.onNode(field).performImeAction()
        assertEquals(1, searched)
        compose.onAllNodes(field).assertCountEquals(1)
    }

    private fun hasHint(hint: String) = SemanticsMatcher.expectValue(SemanticsProperties.HintText, hint)

    /**
     * The placeholder of a search field is its only label. A reader hears it as the hint of the editable text, so it
     * stays when the user types and the visible placeholder goes; the picture of it is not text a second time, and the
     * clear action stays an action of its own.
     */
    @Test
    fun theSearchFieldKeepsItsLabelWhileTypingAndClearStaysItsOwnAction() {
        val state = TextFieldState()
        compose.setDoonaContent { DoonaSearchField(state, placeholder = "Search apps") }
        compose.onNode(field).assert(hasHint("Search apps")).assert(!hasText("Search apps"))
        compose.onAllNodesWithText("Search apps", useUnmergedTree = true).assertCountEquals(0)
        compose.onNode(field).performTextInput("abc")
        compose.onNode(field).assert(hasHint("Search apps")).assert(hasText("abc")).assert(!hasText("Search apps"))
        compose.onAllNodesWithText("Search apps", useUnmergedTree = true).assertCountEquals(0)
        val clear = compose.onNodeWithContentDescription("清除")
        clear.assertHasClickAction()
        assertTrue("the clear action is not the field's node", clear.fetchSemanticsNode().id != compose.onNode(field).fetchSemanticsNode().id)
        compose.onNode(field).assert(!hasContentDescription("清除"))
        clear.performClick()
        compose.waitForIdle()
        assertEquals("", state.text.toString())
        compose.onNode(field).assert(hasHint("Search apps"))
        compose.onNodeWithContentDescription("清除").assertDoesNotExist()
    }

    /** The supporting line is part of the field's node, so a reader hears it with the label; an error takes its place. */
    @Test
    fun theSupportingTextIsPartOfTheNodeAndAnErrorReplacesIt() {
        compose.setDoonaContent {
            Column {
                DoonaTextField(TextFieldState(), label = "A", supportingText = "Help for A")
                DoonaTextField(TextFieldState(), label = "B", supportingText = "Help for B", error = "Broken B")
            }
        }
        compose.onNode(field and hasText("A")).assert(hasText("Help for A")).assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Error))
        compose.onNode(field and hasText("B")).assert(hasText("Broken B")).assert(!hasText("Help for B"))
    }

    /** A trailing action sits in the field's box at its end, takes its own taps and leaves the text editable. */
    @Test
    fun aTrailingActionIsInsideTheBoxAndTakesItsTaps() {
        var taps = 0
        val state = TextFieldState()
        compose.setDoonaContent {
            DoonaTextField(state, label = "URL", trailing = { DoonaIconAction(IconsR.drawable.sym_more_vert, "Paste", onClick = { taps++ }) })
        }
        val density = compose.density.density
        val box = compose.layoutTree().descendants().single { it.role == LayoutRole.Field }
        val action = compose.onNodeWithContentDescription("Paste").fetchSemanticsNode().boundsInRoot
        assertEquals("the action ends at the box's end", box.right, action.right / density, 0.5f)
        assertTrue("the action is inside the box, not only inside the field with its label", action.top / density >= box.top - 0.5f && action.bottom / density <= box.bottom + 0.5f)
        assertTrue("the box is below the label, so the field is taller than the box", compose.onNode(field).fetchSemanticsNode().boundsInRoot.top / density < box.top - 1f)
        compose.onNodeWithContentDescription("Paste").performClick()
        assertEquals(1, taps)
        compose.onNode(field).performTextInput("x")
        assertEquals("x", state.text.toString())
    }

    private fun Color.apart(o: Color) = maxOf(abs(red - o.red), abs(green - o.green), abs(blue - o.blue))

    /**
     * Where the text runs on past the box it fades, at any font size: at the last column of the text area the glyphs are
     * all but gone, before the fade they show in full, and the glyph cut at the edge, which lies in the last em, shows
     * at no more than [FAINT] of that, so no half glyph is left at full strength.
     */
    private fun aLongValueFades(variant: Variant) {
        val c = DoonaColors(Dawn)
        compose.setVariantContent(variant) { Column(Modifier.background(c.surface)) { DoonaTextField(TextFieldState("W".repeat(LONG)), label = "URL") } }
        compose.drawFully()
        val page = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val density = compose.density.density
        val image = compose.onRoot().captureToImage().toPixelMap()
        val edge = (page.right - DoonaTheme.spacing.fillInset.value * density).toInt() - 1
        val rows = ((page.bottom - 48f * density).toInt() until page.bottom.toInt())
        val em = (TypeScale.body.size.value * variant.fontScale * density).toInt()
        val fade = (DoonaTheme.size.fieldFade.value * variant.fontScale * density).toInt()
        fun ink(from: Int, to: Int) = (from..to).maxOf { x -> rows.maxOf { y -> image[x, y].apart(c.control) } }
        val atEdge = ink(edge, edge)
        val full = ink(edge - fade - 2 * em, edge - fade)
        val lastGlyph = ink(edge - em, edge)
        assertTrue("the last column is %.2f from the fill, a half glyph".format(atEdge), atEdge < FADED)
        assertTrue("before the fade the glyphs show, %.2f from the fill".format(full), full > SHOWN)
        assertTrue("the glyph cut at the edge shows %.2f from the fill, over %.2f of the full %.2f".format(lastGlyph, FAINT, full), lastGlyph <= full * FAINT)
    }

    @Test
    fun aLongValueFadesAtTheEdgeInsteadOfBeingCut() = aLongValueFades(Light)

    @Test
    fun aLongValueFadesAtTheEdgeAtTheLargestFontToo() = aLongValueFades(FontScale200)

    private companion object {
        const val LONG = 80

        /** The most a pixel of the faded edge may differ from the fill: a few levels of 255. */
        const val FADED = 0.06f

        /** The least a pixel of a glyph differs from the fill. */
        const val SHOWN = 0.3f

        /** The most of a glyph's full strength that the glyph at the cut may show: a fade of 16 dp, an em long, failed this. */
        const val FAINT = 0.6f
    }
}
