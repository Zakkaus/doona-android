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
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.offset
import dev.doona.android.R
import dev.doona.android.designsystem.theme.DoonaTheme
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sqrt

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
 * the room wraps onto more lines, up to [maxLines], and ends in an ellipsis only past them; the button grows to hold
 * them, keeping the air above and below the label that one line has, and, for a pill, the air its round ends need.
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
    maxLines: Int = LABEL_LINES,
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
            .labelPadding(skin.type.lineHeight, roundEnds = skin.shape === DoonaTheme.shape.pill, DoonaTheme.size.control, DoonaTheme.spacing.fillInset, DoonaTheme.spacing.tight),
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
            maxLines = maxLines,
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

/**
 * The padding between a button's fill and its content: `fillInset` at the ends and `tight` above and below, which with
 * the control's height centres one line. A label that wraps keeps what one line has. Above and below that is the air of
 * a single line in the control's height, `(control - line) / 2`. At the ends, a rounded rectangle needs no more, but a
 * pill's ends are half circles, and a taller block of text at the old inset would touch them: it moves in by as much as
 * puts its corner as far from the arc as the corner of a single line is. The label wraps when its natural width is
 * more than the room the fill leaves, which the content's intrinsic sizes say without measuring it twice.
 */
private fun Modifier.labelPadding(line: TextUnit, roundEnds: Boolean, control: Dp, inset: Dp, tight: Dp): Modifier = layout { measurable, constraints ->
    val lineHeight = line.toPx()
    val sideInset = inset.toPx()
    val tightPad = tight.toPx()
    val room = constraints.maxWidth - 2 * sideInset
    val wraps = constraints.hasBoundedWidth && measurable.maxIntrinsicWidth(Constraints.Infinity) > room
    var end = sideInset
    var vertical = tightPad
    if (wraps) {
        vertical = maxOf(tightPad, (control.toPx() - lineHeight) / 2)
        if (roundEnds) {
            val block = measurable.minIntrinsicHeight(room.roundToInt()).toFloat()
            end = maxOf(sideInset, pillEnd(block, vertical, lineHeight, sideInset, tightPad, control.toPx()))
        }
    }
    val sides = end.roundToInt()
    val ends = vertical.roundToInt()
    val placeable = measurable.measure(constraints.offset(horizontal = -2 * sides, vertical = -2 * ends))
    layout(constraints.constrainWidth(placeable.width + 2 * sides), constraints.constrainHeight(placeable.height + 2 * ends)) {
        placeable.placeRelative(sides, ends)
    }
}

/**
 * How far from the end of a pill a block of text [block] high starts, so that its corner is as far from the arc of the
 * end as a single line's corner is in the pill of one line (its height [control] at least, [line] and `2 * tightPad`
 * at most), where the line starts [sideInset] in. The pill's ends are half circles of its half height.
 */
private fun pillEnd(block: Float, vertical: Float, line: Float, sideInset: Float, tightPad: Float, control: Float): Float {
    val single = maxOf(control, line + 2 * tightPad) / 2
    val gap = single - hypot(single - sideInset, line / 2)
    val radius = (block + 2 * vertical) / 2
    val clear = radius - gap
    return radius - sqrt(maxOf(clear * clear - (block / 2) * (block / 2), 0f))
}
