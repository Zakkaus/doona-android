package dev.doona.android.designsystem

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.doona.android.designsystem.components.DoonaSearchField
import dev.doona.android.designsystem.components.DoonaTextField
import dev.doona.android.testing.setDoonaContent
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

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
        compose.onNode(field).performImeAction()
        assertEquals(1, searched)
        compose.onAllNodes(field).assertCountEquals(1)
    }
}
