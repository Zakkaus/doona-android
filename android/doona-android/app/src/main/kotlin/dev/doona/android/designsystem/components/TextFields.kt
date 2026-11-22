package dev.doona.android.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.KeyboardActionHandler
import androidx.compose.foundation.text.input.TextFieldDecorator
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import dev.doona.android.R
import dev.doona.android.designsystem.theme.DoonaTheme
import androidx.compose.ui.semantics.error as semanticsError

/*
 * The text is a TextFieldState, which Compose's text guide now recommends over a value and a callback: the state holds
 * the text, the selection and the composition together and the field edits it in place, so an input method's
 * composing text and the cursor survive a recomposition and a state holder that updates asynchronously cannot move the
 * cursor or drop a keystroke, as the value form does. A screen creates it with `rememberTextFieldState()` and reads
 * `state.text`, or a view model holds one and reads it with `snapshotFlow { state.text }`.
 */

/**
 * Labelled single-line field (§7, `.rp-field`): the label above in the caption style, then a [DoonaTheme] control-fill
 * box at least 48 dp high with 12 of padding at the sides, where the typed text, or the [placeholder] while there is
 * none, sits; then the [error] line, if any. Focus draws a 2 dp accent ring, an error a 2 dp negative one, and the error
 * line names the error in words with a dot, so colour is not all it says. The label, the text and the error are one
 * node to a screen reader. [keyboardOptions] and [onImeAction] pass the keyboard's type and action through; the
 * action's default (hide the keyboard, move the focus) runs after [onImeAction].
 */
@Composable
fun DoonaTextField(
    state: TextFieldState,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    error: String? = null,
    enabled: Boolean = true,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    onImeAction: (() -> Unit)? = null,
) {
    val c = DoonaTheme.colors
    FieldInput(state, modifier, enabled, keyboardOptions, onImeAction, error) { inner, focused ->
        Column(verticalArrangement = Arrangement.spacedBy(DoonaTheme.spacing.tight)) {
            Text(label, style = DoonaTheme.type.caption, color = c.text.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            FieldBox(error != null, focused) {
                Box(Modifier.fillMaxWidth().padding(horizontal = DoonaTheme.spacing.fillInset)) { Typed(state, placeholder, enabled, inner) }
            }
            if (error != null) FieldError(error)
        }
    }
}

/**
 * Search field: the same box with a leading search icon and, once there is text, a clear action at its end that empties
 * the state. [placeholder] says what is searched and names the field to a screen reader. The keyboard's action is
 * Search unless [keyboardOptions] says otherwise.
 */
@Composable
fun DoonaSearchField(
    state: TextFieldState,
    placeholder: String,
    modifier: Modifier = Modifier,
    keyboardOptions: KeyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
    onImeAction: (() -> Unit)? = null,
) {
    val c = DoonaTheme.colors
    FieldInput(state, modifier, enabled = true, keyboardOptions, onImeAction, error = null) { inner, focused ->
        FieldBox(invalid = false, focused) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.width(DoonaTheme.spacing.fillInset))
                // The icon grows with the text beside it, as a button's glyph does.
                DoonaIcon(R.drawable.ic_search, c.icon, size = dotSizeFor(DoonaTheme.type.body, DoonaTheme.size.iconSmall))
                Spacer(Modifier.width(DoonaTheme.spacing.item))
                Box(Modifier.weight(1f).padding(end = DoonaTheme.spacing.item)) { Typed(state, placeholder, enabled = true, inner) }
                if (state.text.isNotEmpty()) DoonaIconAction(R.drawable.ic_close, stringResource(R.string.ui_clear), onClick = { state.clearText() })
            }
        }
    }
}

/** The text of a field, or its placeholder while there is none; [inner] is the field's own editable text. */
@Composable
private fun Typed(state: TextFieldState, placeholder: String?, enabled: Boolean, inner: @Composable () -> Unit) {
    val c = DoonaTheme.colors
    Box(contentAlignment = Alignment.CenterStart) {
        if (state.text.isEmpty() && placeholder != null) {
            Text(placeholder, style = DoonaTheme.type.body, color = if (enabled) c.text.secondary else c.text.disabled, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        inner()
    }
}

/** The filled box of a field at least 48 dp high, with the ring of its state; [content] fills it. */
@Composable
private fun FieldBox(invalid: Boolean, focused: Boolean, content: @Composable () -> Unit) {
    val c = DoonaTheme.colors
    val shape = DoonaTheme.shape.control
    val ring = if (invalid) {
        c.negative
    } else if (focused) {
        c.accent
    } else {
        null
    }
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = DoonaTheme.size.touch)
            .clip(shape)
            .background(c.control)
            .then(if (ring != null) Modifier.border(DoonaTheme.size.borderStrong, ring, shape) else Modifier),
        contentAlignment = Alignment.CenterStart,
    ) { content() }
}

@Composable
private fun FieldError(message: String) {
    val style = DoonaTheme.type.fieldError
    Row(verticalAlignment = Alignment.Top) {
        LineDot(Tone.Err, style, DoonaTheme.size.dotSmall)
        Spacer(Modifier.width(DoonaTheme.spacing.item))
        Text(message, style = style, color = DoonaTheme.colors.text.negative)
    }
}

/** The editable text over a state, in the body style on one line, with [decoration] drawn around it. */
@Composable
private fun FieldInput(
    state: TextFieldState,
    modifier: Modifier,
    enabled: Boolean,
    keyboardOptions: KeyboardOptions,
    onImeAction: (() -> Unit)?,
    error: String?,
    decoration: @Composable (inner: @Composable () -> Unit, focused: Boolean) -> Unit,
) {
    val c = DoonaTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    BasicTextField(
        state = state,
        modifier = modifier.semantics { if (error != null) semanticsError(error) },
        enabled = enabled,
        textStyle = DoonaTheme.type.body.copy(color = if (enabled) c.text.primary else c.text.disabled),
        keyboardOptions = keyboardOptions,
        onKeyboardAction = onImeAction?.let { act ->
            KeyboardActionHandler { performDefault ->
                act()
                performDefault()
            }
        },
        lineLimits = TextFieldLineLimits.SingleLine,
        interactionSource = interaction,
        cursorBrush = SolidColor(c.accent),
        decorator = TextFieldDecorator { inner -> decoration(inner, focused) },
    )
}
