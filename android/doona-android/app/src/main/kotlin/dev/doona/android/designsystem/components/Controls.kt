package dev.doona.android.designsystem.components

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.doona.android.designsystem.theme.DoonaTheme
import dev.doona.android.icons.R as IconsR

// The controls, one of each: switch, radio, segmented control, filter chip, range chip, tool disc and tag.

/**
 * Revolut's switch (§13.4, Addendum A): a 50 × 27 dp node holding a thin 34 dp track and a 19 dp thumb taller than it.
 * On, the thumb is the accent over a tint of it, outlined in the accent where the tint alone would not show on the card;
 * off, a light thumb ringed in [control][dev.doona.android.designsystem.theme.DoonaColors.control] over a track of it.
 * Every thumb keeps 3:1 on its track, and every track or its outline 3:1 on the card (the theme computes the tints).
 * It is only the picture: the row around it ([DoonaSwitchRow]) owns the toggling and what a screen reader hears.
 */
@Composable
fun DoonaSwitch(checked: Boolean, modifier: Modifier = Modifier) {
    val colors = DoonaTheme.colors
    val d = DoonaSwitchDefaults
    val off = (d.width - d.trackWidth) / 2 - (d.thumb - d.trackHeight) / 2
    val x by animateDpAsState(if (checked) off + d.trackWidth - d.trackHeight else off, label = "thumb")
    Box(modifier.layoutRole(LayoutRole.Toggle).size(d.width, d.height).clearAndSetSemantics {}) {
        Box(
            Modifier
                .align(Alignment.Center)
                .size(d.trackWidth, d.trackHeight)
                .clip(CircleShape)
                .background(if (checked) colors.switchOnTrack else colors.control)
                .then(colors.switchOnRim?.takeIf { checked }?.let { Modifier.border(d.trackRim, it, CircleShape) } ?: Modifier),
        )
        Box(
            Modifier
                .align(Alignment.CenterStart)
                .offset(x = x)
                .size(d.thumb)
                .shadow(d.thumbShadow, CircleShape)
                .background(if (checked) colors.switchOnThumb else colors.switchOffThumb, CircleShape)
                // Off, the light thumb keeps an edge of the track's grey, so it does not melt into the card (L-10).
                .then(if (checked) Modifier else Modifier.border(d.thumbRing, colors.control, CircleShape)),
        )
    }
}

/** A radio: a 2 dp ring in the control grey, in the accent with a dot inside once [selected]. The row owns the choice. */
@Composable
fun DoonaRadio(selected: Boolean, modifier: Modifier = Modifier) {
    val ring = if (selected) DoonaTheme.colors.positive else DoonaTheme.colors.control
    val d = DoonaSwitchDefaults
    Canvas(modifier.layoutRole(LayoutRole.Toggle).size(d.radio).clearAndSetSemantics {}) {
        val stroke = d.radioStroke.toPx()
        drawCircle(ring, radius = (size.minDimension - stroke) / 2, style = Stroke(stroke))
        if (selected) drawCircle(ring, radius = d.radioDot.toPx() / 2)
    }
}

/**
 * Revolut's segmented control (measured §8): a 36 dp pill track, visible on any surface, [items] in equal parts, the
 * [selected] one on a raised pill 2 dp inside it. Each part is a 48 dp target; the press lights only its pill. Those are
 * minimums: a label that a large font makes taller grows the track and the pill with it. A tap on the part already
 * selected calls nothing: [selected] may show a choice the caller maps from another, which a tap must not write back.
 */
@Composable
fun DoonaSegmented(items: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val colors = DoonaTheme.colors
    val chart = DoonaTheme.chart
    val touch = DoonaTheme.size.touch
    Box(modifier.layoutRole(LayoutRole.Segmented).fillMaxWidth().heightIn(min = touch), contentAlignment = Alignment.Center) {
        Box(Modifier.matchParentSize().padding(vertical = (touch - chart.segment) / 2).clip(CircleShape).background(colors.segmentTrack))
        Row(Modifier.fillMaxWidth().heightIn(min = touch).height(IntrinsicSize.Min).selectableGroup()) {
            items.forEachIndexed { i, label ->
                val source = remember { MutableInteractionSource() }
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .selectable(i == selected, interactionSource = source, indication = null, role = Role.Tab) { if (i != selected) onSelect(i) }
                        .padding(vertical = (touch - chart.segment) / 2)
                        .padding(chart.segmentInset)
                        .then(if (i == selected) Modifier.raised(CircleShape) else Modifier.clip(CircleShape))
                        .indication(source, ripple(color = colors.stateLayer)),
                    contentAlignment = Alignment.Center,
                ) { LineText(label, DoonaTheme.type.segment, color = if (i == selected) colors.text else colors.textSecondary) }
            }
        }
    }
}

/** A raised control's fill in [shape]: a surface over a soft shadow in light, a lighter container with a rim in dark. */
@Composable
fun Modifier.raised(shape: Shape): Modifier {
    val colors = DoonaTheme.colors
    val lift = if (colors.raisedShadow) Modifier.shadow(DoonaTheme.elevation.raised, shape) else Modifier
    val rim = colors.raisedRim?.let { Modifier.border(DoonaTheme.size.ring, it, shape) } ?: Modifier
    return then(lift).clip(shape).background(colors.raised).then(rim)
}

/**
 * A filter chip under a pushed page's large title, Revolut's small action button (`Personal ▾`, `This month ▾`): the
 * [label] on the segment track's fill and a chevron that says it opens a choice; a 48 dp target (S7-07).
 */
@Composable
fun DoonaFilterChip(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val d = DoonaSwitchDefaults
    Row(
        modifier
            .layoutRole(LayoutRole.Chip)
            .minimumInteractiveComponentSize()
            .heightIn(min = d.chip)
            .clip(CircleShape)
            .background(DoonaTheme.colors.segmentTrack)
            .press(CircleShape, Role.Button, onClick)
            .padding(start = d.chipStart, end = d.chipEnd, top = DoonaTheme.spacing.tight, bottom = DoonaTheme.spacing.tight),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DoonaTheme.spacing.tight),
    ) {
        LineText(label, DoonaTheme.type.rowTitle)
        DoonaGlyph(IconsR.drawable.sym_keyboard_arrow_down, d.chipGlyph, DoonaTheme.colors.text)
    }
}

/** A range under a chart, Revolut's chip (measured §8): only the [selected] one has a fill, raised off the page; no track. */
@Composable
fun DoonaRangeChip(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = DoonaTheme.colors
    Box(
        modifier
            .layoutRole(LayoutRole.Chip)
            .minimumInteractiveComponentSize()
            .heightIn(min = DoonaTheme.chart.range)
            .then(if (selected) Modifier.raised(CircleShape) else Modifier.clip(CircleShape))
            .selectable(selected, interactionSource = null, indication = ripple(color = colors.stateLayer), role = Role.Tab, onClick = onClick)
            .padding(horizontal = DoonaTheme.chart.rangePad),
        contentAlignment = Alignment.Center,
    ) { LineText(label, DoonaTheme.type.action, color = if (selected) colors.text else colors.textSecondary) }
}

/** A round tool disc on a container colour, as after Revolut's range chips (35 dp in a 48 dp target); [label] is for the screen reader. */
@Composable
fun DoonaToolDisc(@DrawableRes icon: Int, label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .layoutRole(LayoutRole.Button)
            .minimumInteractiveComponentSize()
            .size(DoonaTheme.chart.roundButton)
            .clip(CircleShape)
            .background(DoonaTheme.colors.roundButton)
            .press(CircleShape, Role.Button, onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { DoonaGlyph(icon, DoonaTheme.chart.roundGlyph, DoonaTheme.colors.text) }
}

/**
 * A chart's two tool discs at the end of its range row (measured §8): 35 dp, 8 dp apart. Each keeps a 48 dp target, and
 * the two targets meet in the middle of the gap rather than overlap (an overlap leaves each less than 48 dp of its own,
 * which the accessibility check fails), so each target reaches past the outer edge of its disc by [toolOverhang].
 */
@Composable
fun DoonaChartTools(first: DoonaBarAction, second: DoonaBarAction, modifier: Modifier = Modifier) {
    val half = DoonaTheme.chart.roundGap / 2
    Row(modifier) {
        ToolTarget(first, PaddingValues(start = toolOverhang(), end = half))
        ToolTarget(second, PaddingValues(start = half, end = toolOverhang()))
    }
}

/** One of [DoonaChartTools]' discs in its 48 dp target, padded by [padding] off the target's sides; only the disc lights up. */
@Composable
private fun ToolTarget(action: DoonaBarAction, padding: PaddingValues) {
    val source = remember { MutableInteractionSource() }
    val chart = DoonaTheme.chart
    Box(
        Modifier
            .layoutRole(LayoutRole.Button)
            .size(DoonaTheme.size.touch)
            .clickable(interactionSource = source, indication = null, role = Role.Button, onClick = action.onClick)
            .semantics { contentDescription = action.label }
            .padding(padding),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(chart.roundButton).clip(CircleShape).background(DoonaTheme.colors.roundButton).indication(source, ripple(color = DoonaTheme.colors.stateLayer)),
            contentAlignment = Alignment.Center,
        ) { DoonaGlyph(action.icon, chart.roundGlyph, DoonaTheme.colors.text) }
    }
}

/** How far a chart tool's 48 dp target reaches past the outer edge of its disc: a row that ends in [DoonaChartTools] pulls in by this. */
fun toolOverhang() = DoonaTheme.size.touch - DoonaTheme.chart.roundButton - DoonaTheme.chart.roundGap / 2

/**
 * A tag on a card's header or after a name (`v0.2`), Revolut's tag chips (measured §8): full round, the value pill's
 * fill and secondary ink, flat. It is not the floating value pill: tags cast no shadow (N-04). With [onClick] it opens
 * something and keeps a 48 dp target with the chip centred in it. A row that holds both kinds centres its items
 * (`Alignment.CenterVertically`); aligned at the top, the plain tag sits higher than the tappable one.
 */
@Composable
fun DoonaTag(text: String, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    val chart = DoonaTheme.chart
    Box(
        modifier
            .layoutRole(LayoutRole.Tag)
            .then(if (onClick != null) Modifier.minimumInteractiveComponentSize() else Modifier)
            .heightIn(min = chart.pill)
            .clip(CircleShape)
            .background(DoonaTheme.colors.pill)
            .then(onClick?.let { Modifier.press(CircleShape, Role.Button, it) } ?: Modifier)
            .padding(horizontal = chart.pillPadH),
        contentAlignment = Alignment.Center,
    ) { LineText(text, DoonaTheme.type.pill, color = DoonaTheme.colors.textSecondary) }
}

/** The geometry of the switch, the radio and the filter chip (measured §13.4; Addendum A: a thinner track under the 19 dp thumb). */
internal object DoonaSwitchDefaults {
    val width = 50.dp
    val height = 27.dp
    val trackWidth = 34.dp
    val trackHeight = 14.dp
    val thumb = 19.dp
    val thumbShadow = 1.dp
    val thumbRing = 1.dp
    val trackRim = 1.dp
    val radio = 24.dp
    val radioStroke = 2.dp
    val radioDot = 12.dp

    /** A filter chip (§8 small action button): 40 dp tall, 16 dp before the label, 12 dp after the 16 dp chevron. */
    val chip = 40.dp
    val chipStart = 16.dp
    val chipEnd = 12.dp
    val chipGlyph = 16.dp
}
