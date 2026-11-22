package dev.doona.android.designsystem.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import dev.doona.android.R
import dev.doona.android.designsystem.theme.DoonaTheme

/**
 * How loud a button is: [Primary] is the one action of a page or dialog (accent pill, bold), [Secondary] an alternative
 * next to it (outlined), [Text] a minor one (no fill, no outline), [Destructive] the one action that removes something
 * (a primary pill in the destructive fill).
 */
enum class DoonaButtonStyle { Primary, Secondary, Text, Destructive }

/**
 * Button (§7): 36 high, 12 of padding at the sides, the label centred (14/500, a primary or destructive 14/700), a
 * leading [icon] of 16 if given, which grows with the label's font. Hit over 48 dp like every control, so stacked
 * buttons are a `block` apart. [enabled] false mutes it and ignores taps; [busy] swaps the icon for a spinner, ignores
 * taps and tells a screen reader it is in progress, for the time between the tap and the result. A label too long for
 * the room wraps onto a second line, then ends in an ellipsis; the button grows to hold both lines, a `tight` gap above and below them.
 */
@Composable
fun DoonaButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: DoonaButtonStyle = DoonaButtonStyle.Primary,
    @DrawableRes icon: Int? = null,
    enabled: Boolean = true,
    busy: Boolean = false,
) {
    val skin = skinOf(style, enabled)
    // The glyph grows with the label as the label's line does, so it does not stay a speck beside a large font.
    val glyph = dotSizeFor(skin.type, DoonaTheme.size.iconInline)
    val state = stringResource(R.string.ui_in_progress)
    Row(
        modifier
            .layoutRole(LayoutRole.Button)
            .heightIn(min = DoonaTheme.size.control)
            .widthIn(min = DoonaTheme.size.control)
            .clip(skin.shape)
            .then(if (skin.fill != null) Modifier.background(skin.fill) else Modifier)
            .then(if (skin.outline != null) Modifier.border(DoonaTheme.size.borderStrong, skin.outline, skin.shape) else Modifier)
            .clickable(enabled = enabled && !busy, role = Role.Button, onClick = onClick)
            .semantics { if (busy) stateDescription = state }
            .padding(horizontal = DoonaTheme.spacing.fillInset, vertical = DoonaTheme.spacing.tight),
        horizontalArrangement = Arrangement.spacedBy(DoonaTheme.spacing.item, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (busy) {
            Spinner(skin.label, glyph, Modifier.clearAndSetSemantics {})
        } else if (icon != null) {
            DoonaIcon(icon, skin.label, size = glyph)
        }
        Text(
            label,
            style = skin.type,
            color = skin.label,
            textAlign = TextAlign.Center,
            maxLines = LABEL_LINES,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
    }
}

/** What a button draws for its look and state: the fill, the outline, the label's colour, shape and type. */
private class ButtonSkin(val fill: Color?, val outline: Color?, val label: Color, val shape: Shape, val type: TextStyle)

/** A disabled button's label is muted and its outline dimmed with it. */
@Composable
private fun skinOf(style: DoonaButtonStyle, enabled: Boolean): ButtonSkin {
    val c = DoonaTheme.colors
    val ink = if (enabled) null else c.text.disabled
    return when (style) {
        DoonaButtonStyle.Primary -> ButtonSkin(if (enabled) c.accent else c.control, null, ink ?: c.onAccent, DoonaTheme.shape.pill, DoonaTheme.type.bodyStrong)
        DoonaButtonStyle.Destructive -> ButtonSkin(if (enabled) c.destructive else c.control, null, ink ?: c.onAccent, DoonaTheme.shape.pill, DoonaTheme.type.bodyStrong)
        DoonaButtonStyle.Secondary -> ButtonSkin(null, if (enabled) c.strongLine else c.disabledLine, ink ?: c.text.primary, DoonaTheme.shape.control, DoonaTheme.type.label)
        DoonaButtonStyle.Text -> ButtonSkin(null, null, ink ?: c.text.accent, DoonaTheme.shape.control, DoonaTheme.type.label)
    }
}
