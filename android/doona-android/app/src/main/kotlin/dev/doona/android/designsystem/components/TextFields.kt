package dev.doona.android.designsystem.components

import androidx.compose.foundation.ScrollState
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
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.hintText
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import dev.doona.android.R
import dev.doona.android.designsystem.theme.DoonaTheme
import dev.doona.android.designsystem.theme.forInput
import dev.doona.android.designsystem.theme.scripted
import androidx.compose.ui.semantics.error as semanticsError
import dev.doona.android.icons.R as IconsR

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
 * none, sits, and the optional [trailing] content (an icon action, say) at its end; then the [error] line, or, when
 * there is no error, the [supportingText] line that tells what the field wants. Focus draws a 2 dp accent ring, an
 * error a 2 dp negative one, and the error line names the error in words with a dot, so colour is not all it says. The
 * label, the text, the placeholder and the line under the box are one node to a screen reader. Text that runs on past
 * the box fades out at the edge where it is cut off. [keyboardOptions] and [onImeAction] pass the keyboard's type and
 * action through; the action's default (hide the keyboard, move the focus) runs after [onImeAction].
 */
@Composable
fun DoonaTextField(
    state: TextFieldState,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    supportingText: String? = null,
    error: String? = null,
    enabled: Boolean = true,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    onImeAction: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val c = DoonaTheme.colors
    FieldInput(state, modifier, enabled, keyboardOptions, onImeAction, error) { inner, focused, scroll ->
        Column(verticalArrangement = Arrangement.spacedBy(DoonaTheme.spacing.tight)) {
            Text(scripted(label), style = DoonaTheme.type.footnote, color = c.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            FieldBox(error != null, focused) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    val end = if (trailing == null) DoonaTheme.spacing.fillInset else DoonaTheme.spacing.item
                    Box(Modifier.weight(1f).padding(start = DoonaTheme.spacing.fillInset, end = end)) { Typed(state, placeholder, enabled, readPlaceholder = true, scroll, inner) }
                    trailing?.invoke()
                }
            }
            if (error != null) {
                FieldError(error)
            } else if (supportingText != null) {
                Text(scripted(supportingText), style = DoonaTheme.type.footnote, color = c.textSecondary)
            }
        }
    }
}

/**
 * Search field: the same box with a leading search icon and, once there is text, a clear action at its end that empties
 * the state. While the field is empty, [placeholder] shows in it and says what is searched. It is also the field's label
 * to a screen reader, as the hint of the editable text, so it is there after the user has typed, when the visible
 * placeholder is gone, and the picture of it is not read a second time. The clear action is an action of its own. The
 * text keeps clear of the clear action and fades out before it. The keyboard's action is Search unless
 * [keyboardOptions] says otherwise.
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
    FieldInput(state, modifier, enabled = true, keyboardOptions, onImeAction, error = null, hint = placeholder) { inner, focused, scroll ->
        FieldBox(invalid = false, focused) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.width(DoonaTheme.spacing.fillInset))
                // The icon grows with the text beside it, as a button's glyph does.
                DoonaIcon(IconsR.drawable.sym_search, c.textSecondary, size = dotSizeFor(DoonaTheme.type.body, DoonaTheme.size.iconSmall))
                Spacer(Modifier.width(DoonaTheme.spacing.item))
                Box(Modifier.weight(1f).padding(end = DoonaTheme.spacing.item)) { Typed(state, placeholder, enabled = true, readPlaceholder = false, scroll, inner) }
                if (state.text.isNotEmpty()) DoonaIconAction(IconsR.drawable.sym_close, stringResource(R.string.ui_clear), onClick = { state.clearText() })
            }
        }
    }
}

/**
 * The text of a field, or its placeholder while there is none; [inner] is the field's own editable text, which fades
 * where [scroll] has more of it out of sight. A screen reader hears the placeholder as text while the field is empty if
 * [readPlaceholder], and not at all otherwise, where the field says it in another way.
 */
@Composable
private fun Typed(state: TextFieldState, placeholder: String?, enabled: Boolean, readPlaceholder: Boolean, scroll: ScrollState, inner: @Composable () -> Unit) {
    val c = DoonaTheme.colors
    Box(Modifier.fadedAtEdges(scroll), contentAlignment = Alignment.CenterStart) {
        if (state.text.isEmpty() && placeholder != null) {
            Text(
                scripted(placeholder),
                style = DoonaTheme.type.body,
                color = if (enabled) c.textSecondary else c.textDisabled,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = if (readPlaceholder) Modifier else Modifier.clearAndSetSemantics {},
            )
        }
        inner()
    }
}

/**
 * Fades the edge of a scrolled line of text where more of it lies out of sight, over the width of `fieldFade` taken
 * at the size of the text, so a long value ends in a soft edge and no part of a glyph shows at full strength at the
 * cut, at any font size: the end when [scroll] has more to show after, the start when it has some before. The mask is
 * drawn into the content's own layer, so what is behind the text is untouched.
 */
@Composable
private fun Modifier.fadedAtEdges(scroll: ScrollState): Modifier {
    val fade = dotSizeFor(DoonaTheme.type.body, DoonaTheme.size.fieldFade)
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    return graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen).drawWithContent {
        drawContent()
        val width = minOf(fade.toPx(), size.width / 2)
        val moreBefore = scroll.value > 0
        val moreAfter = scroll.value < scroll.maxValue
        if (if (rtl) moreAfter else moreBefore) {
            drawRect(Brush.horizontalGradient(listOf(Color.Transparent, Color.Black), startX = 0f, endX = width), size = Size(width, size.height), blendMode = BlendMode.DstIn)
        }
        if (if (rtl) moreBefore else moreAfter) {
            drawRect(
                Brush.horizontalGradient(listOf(Color.Black, Color.Transparent), startX = size.width - width, endX = size.width),
                topLeft = Offset(size.width - width, 0f),
                size = Size(width, size.height),
                blendMode = BlendMode.DstIn,
            )
        }
    }
}

/** The filled box of a field at least 48 dp high, with the ring of its state; [content] fills it. */
@Composable
private fun FieldBox(invalid: Boolean, focused: Boolean, content: @Composable () -> Unit) {
    val c = DoonaTheme.colors
    val shape = DoonaTheme.shape.control
    val ring = if (invalid) {
        c.slow
    } else if (focused) {
        c.positive
    } else {
        null
    }
    Box(
        Modifier
            .layoutRole(LayoutRole.Field)
            .fillMaxWidth()
            .heightIn(min = DoonaTheme.size.touch)
            .clip(shape)
            .background(c.discOnCard)
            .then(if (ring != null) Modifier.border(DoonaTheme.size.borderStrong, ring, shape) else Modifier),
        contentAlignment = Alignment.CenterStart,
    ) { content() }
}

@Composable
private fun FieldError(message: String) {
    val style = DoonaTheme.type.fieldError
    Row(verticalAlignment = Alignment.Top) {
        LineDot(Tone.Err, style, DoonaTheme.size.dot)
        Spacer(Modifier.width(DoonaTheme.spacing.item))
        Text(scripted(message), style = style, color = DoonaTheme.colors.slow)
    }
}

/**
 * The editable text over a state, in the body style on one line, with [decoration] drawn around it; it gets the text's
 * scroll, to fade by. [hint] is the field's label for a screen reader where the decoration has no text of its own to give.
 */
@Composable
private fun FieldInput(
    state: TextFieldState,
    modifier: Modifier,
    enabled: Boolean,
    keyboardOptions: KeyboardOptions,
    onImeAction: (() -> Unit)?,
    error: String?,
    hint: String? = null,
    decoration: @Composable (inner: @Composable () -> Unit, focused: Boolean, scroll: ScrollState) -> Unit,
) {
    val c = DoonaTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val scroll = rememberScrollState()
    BasicTextField(
        state = state,
        modifier = modifier.semantics {
            if (error != null) semanticsError(error)
            if (hint != null) hintText = hint
        },
        enabled = enabled,
        textStyle = DoonaTheme.type.body.forInput().copy(color = if (enabled) c.text else c.textDisabled),
        keyboardOptions = keyboardOptions,
        onKeyboardAction = onImeAction?.let { act ->
            KeyboardActionHandler { performDefault ->
                act()
                performDefault()
            }
        },
        lineLimits = TextFieldLineLimits.SingleLine,
        interactionSource = interaction,
        cursorBrush = SolidColor(c.positive),
        scrollState = scroll,
        decorator = TextFieldDecorator { inner -> decoration(inner, focused, scroll) },
    )
}
