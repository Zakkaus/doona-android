package dev.doona.android.designsystem.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import dev.doona.android.R
import dev.doona.android.designsystem.theme.DoonaColors
import dev.doona.android.designsystem.theme.DoonaTheme

@Composable
fun DoonaIcon(@DrawableRes icon: Int, tint: Color, modifier: Modifier = Modifier, size: Dp = DoonaTheme.size.icon) {
    Icon(painterResource(icon), contentDescription = null, tint = tint, modifier = modifier.size(size))
}

/** Twemoji flag ahead of a node name (doona puts the flag glyph first, `src/ui/flags.css`). */
@Composable
fun Flag(@DrawableRes flag: Int, label: String, modifier: Modifier = Modifier) {
    val painter = painterResource(flag)
    val ratio = painter.intrinsicSize.width / painter.intrinsicSize.height
    val edge = DoonaTheme.colors.flagEdge
    Image(
        painter,
        contentDescription = label,
        modifier = modifier
            .height(DoonaTheme.size.flagHeight)
            .aspectRatio(ratio)
            .then(if (edge != null) Modifier.border(DoonaTheme.size.flagEdge, edge, DoonaTheme.shape.flag) else Modifier),
    )
}

/** Body text in the theme's type; feature code uses this, not Material's `Text`. */
@Composable
fun DoonaText(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = DoonaTheme.colors.text,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    softWrap: Boolean = true,
) {
    Text(text, modifier, color = color, style = style, maxLines = maxLines, overflow = overflow, softWrap = softWrap)
}

/** Height of one text line of [style] in dp, so a dot or icon can sit on the centre of a first line. */
@Composable
fun lineHeightOf(style: TextStyle): Dp = with(LocalDensity.current) { style.lineHeight.toDp() }

/** Card (§7): padding 16, gap 12, radius 16, 1 px border, base fill on the sheet. */
@Composable
fun DoonaCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = DoonaTheme.colors
    val shape = DoonaTheme.shape.xl
    Column(
        modifier = modifier
            .clip(shape)
            .background(c.card)
            .border(DoonaTheme.size.border, c.cardBorder, shape)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(DoonaTheme.space.s4),
        verticalArrangement = Arrangement.spacedBy(DoonaTheme.space.s3),
        content = content,
    )
}

/** Control card (§7): tinted icon and 14/500 label, controls at the end of the same row. */
@Composable
fun ControlCard(@DrawableRes icon: Int, iconTint: Color, label: String, trailing: @Composable RowScope.() -> Unit) {
    DoonaCard(Modifier.fillMaxWidth()) {
        Row(Modifier.heightIn(min = DoonaTheme.size.touch), verticalAlignment = Alignment.CenterVertically) {
            DoonaIcon(icon, iconTint)
            Spacer(Modifier.width(DoonaTheme.space.s2))
            Text(label, style = DoonaTheme.type.label, color = DoonaTheme.colors.text, maxLines = 1, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(DoonaTheme.space.s3))
            trailing()
        }
    }
}

enum class Tone { Ok, Warn, Err, Neutral }

/** Latency colours (`src/ui/Tile.tsx:79`): under 100 ms ok, under 300 ms warn, otherwise err. */
fun latencyTone(ms: Int?): Tone = when {
    ms == null -> Tone.Neutral
    ms < 100 -> Tone.Ok
    ms < 300 -> Tone.Warn
    else -> Tone.Err
}

fun toneColor(c: DoonaColors, tone: Tone): Color = when (tone) {
    Tone.Ok -> c.positive
    Tone.Warn -> c.notice
    Tone.Err -> c.negative
    Tone.Neutral -> c.neutral
}

/**
 * Status light (§7): a dot and a label, the dot centred on the label's first line. 10 px dot, 8 px for small and
 * for latency; gap 8, 6 for latency (§4).
 */
@Composable
fun StatusLight(
    tone: Tone,
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = DoonaTheme.type.body,
    color: Color = DoonaTheme.colors.text,
    small: Boolean = false,
    gap: Dp = DoonaTheme.space.s2,
) {
    Row(modifier, verticalAlignment = Alignment.Top) {
        Box(Modifier.height(lineHeightOf(style)), contentAlignment = Alignment.Center) {
            Box(
                Modifier
                    .size(if (small) DoonaTheme.size.dotSmall else DoonaTheme.size.dot)
                    .clip(DoonaTheme.shape.pill)
                    .background(toneColor(DoonaTheme.colors, tone)),
            )
        }
        Spacer(Modifier.width(gap))
        Text(text, style = style, color = color, maxLines = 1, softWrap = false)
    }
}

/** Latency (§2): bold tabular value coloured by the tone's text role, 8 px dot, gap 6. */
@Composable
fun Latency(ms: Int?, style: TextStyle = DoonaTheme.type.bodyStrong) {
    val c = DoonaTheme.colors
    val tone = latencyTone(ms)
    val text = when (tone) {
        Tone.Ok -> c.positiveText
        Tone.Warn -> c.noticeText
        Tone.Err -> c.negativeText
        Tone.Neutral -> c.textSecondary
    }
    StatusLight(
        tone = tone,
        text = ms?.let { "$it ms" } ?: "—",
        style = style.copy(fontFeatureSettings = DoonaTheme.type.tabular),
        color = text,
        small = true,
        gap = DoonaTheme.space.s1h,
    )
}

/** Badge (§7): 20 high, padding 0 × 8, radius 6, 1 px hl-high border, 12/500 subtle. */
@Composable
fun Badge(text: String) {
    val c = DoonaTheme.colors
    Box(
        Modifier
            .height(DoonaTheme.size.badgeHeight)
            .border(DoonaTheme.size.border, c.badgeBorder, DoonaTheme.shape.sm)
            .padding(horizontal = DoonaTheme.space.s2),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = DoonaTheme.type.badge, color = c.textSecondary, maxLines = 1)
    }
}

/** Tag chip (§7): control height, padding 12, radius 8, overlay fill, 14/400. */
@Composable
fun TagChip(text: String, onClick: () -> Unit = {}) {
    Box(
        Modifier
            .heightIn(min = DoonaTheme.size.touch)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .height(DoonaTheme.size.control)
                .clip(DoonaTheme.shape.md)
                .background(DoonaTheme.colors.controlFill)
                .padding(horizontal = DoonaTheme.space.s3),
            contentAlignment = Alignment.Center,
        ) {
            Text(text, style = DoonaTheme.type.body, color = DoonaTheme.colors.text, maxLines = 1)
        }
    }
}

/**
 * Segmented control (§7): overlay track, radius 8, gap 4, 36 high; items 14/400 tabular, padding 0 × 8 on the
 * phone; the selected item is a surface box with a 2 px accent border. The row keeps a 48 dp touch height.
 */
@Composable
fun Segmented(items: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val c = DoonaTheme.colors
    Box(modifier.heightIn(min = DoonaTheme.size.touch), contentAlignment = Alignment.CenterStart) {
        Row(
            Modifier
                .height(DoonaTheme.size.control)
                .clip(DoonaTheme.shape.md)
                .background(c.controlFill)
                .selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(DoonaTheme.space.s1),
        ) {
            items.forEachIndexed { i, label ->
                val on = i == selected
                Box(
                    Modifier
                        .height(DoonaTheme.size.control)
                        .widthIn(min = DoonaTheme.size.control)
                        .clip(DoonaTheme.shape.md)
                        .then(if (on) Modifier.background(c.sheet).border(DoonaTheme.size.borderStrong, c.accent, DoonaTheme.shape.md) else Modifier)
                        .selectable(on, role = Role.RadioButton, onClick = { onSelect(i) })
                        .padding(horizontal = DoonaTheme.space.s2),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(label, style = DoonaTheme.type.body.copy(fontFeatureSettings = DoonaTheme.type.tabular), color = c.text, maxLines = 1, softWrap = false)
                }
            }
        }
    }
}

/** Switch (§7): track 32 × 16, radius 8, hl-high off / accent on; 12 px surface knob inset 2, travels 16. */
@Composable
fun DoonaSwitch(checked: Boolean, onChange: (Boolean) -> Unit, label: String, modifier: Modifier = Modifier) {
    val c = DoonaTheme.colors
    val s = DoonaTheme.size
    Box(
        modifier
            .size(s.touch)
            .toggleable(checked, role = Role.Switch, onValueChange = onChange)
            .semantics { contentDescription = label },
        // The visible track keeps to the end of its 48 dp target, so it lines up with the content edge.
        contentAlignment = Alignment.CenterEnd,
    ) {
        Box(
            Modifier
                .size(s.switchWidth, s.switchHeight)
                .clip(DoonaTheme.shape.md)
                .background(if (checked) c.accent else c.switchOff)
                .padding(DoonaTheme.space.half),
            contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
        ) {
            Box(
                Modifier
                    .size(s.switchKnob)
                    .clip(DoonaTheme.shape.pill)
                    .background(c.switchKnob),
            )
        }
    }
}

/** A quiet picker: value and chevron, 48 dp touch height (doona's quiet button, §7). */
@Composable
fun PickerButton(value: String, onClick: () -> Unit, filled: Boolean = false, leading: (@Composable () -> Unit)? = null) {
    val c = DoonaTheme.colors
    Box(Modifier.heightIn(min = DoonaTheme.size.touch).clickable(role = Role.Button, onClick = onClick), contentAlignment = Alignment.Center) {
        Row(
            Modifier
                .height(DoonaTheme.size.control)
                .clip(DoonaTheme.shape.md)
                // A quiet picker has no fill, so it drops the inline padding and its text lines up with the content edge.
                .then(if (filled) Modifier.background(c.controlFill).padding(horizontal = DoonaTheme.space.s3) else Modifier),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(DoonaTheme.space.s2),
        ) {
            leading?.invoke()
            Text(value, style = DoonaTheme.type.body, color = c.text, maxLines = 1)
            DoonaIcon(R.drawable.ic_chevron_down, c.text, size = DoonaTheme.size.iconInline)
        }
    }
}

/** A node or group name on one line, ellipsized. */
@Composable
fun NodeName(name: String, style: TextStyle, modifier: Modifier = Modifier, color: Color = DoonaTheme.colors.text) {
    Text(name, modifier, color = color, style = style, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

/**
 * Node tile (§7): 56 min high, padding 8 × 12, radius 8, overlay fill, 2 px border (accent and the selected fill
 * when current); row 1 name, badge, latency; row 2 the 12/16 source, 2 below.
 */
@Composable
fun NodeTile(
    name: String,
    latencyMs: Int?,
    current: Boolean,
    groupLabel: String?,
    source: String? = null,
    onClick: () -> Unit = {},
) {
    val c = DoonaTheme.colors
    val shape = DoonaTheme.shape.md
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(min = DoonaTheme.size.nodeTileMin)
            .clip(shape)
            .background(if (current) c.selected else c.controlFill)
            .border(DoonaTheme.size.borderStrong, if (current) c.accent else Color.Transparent, shape)
            .selectable(current, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = DoonaTheme.space.s3, vertical = DoonaTheme.space.s2),
        verticalArrangement = Arrangement.spacedBy(DoonaTheme.space.half, Alignment.CenterVertically),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            NodeName(name, DoonaTheme.type.bodyStrong, modifier = Modifier.weight(1f))
            if (groupLabel != null) {
                Spacer(Modifier.width(DoonaTheme.space.s2))
                Badge(groupLabel)
            }
            Spacer(Modifier.width(DoonaTheme.space.s3))
            Latency(latencyMs)
        }
        source?.let { Text(it, style = DoonaTheme.type.caption, color = c.text, maxLines = 1) }
    }
}
