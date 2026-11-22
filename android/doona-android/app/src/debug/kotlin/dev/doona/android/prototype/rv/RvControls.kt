package dev.doona.android.prototype.rv

import androidx.annotation.DrawableRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.roundToIntRect
import dev.doona.android.R
import dev.doona.android.feature.NOT_WIRED

// The sample's controls, one of each: range chip, segmented control, chart tool disc, value pill, switch and radio.

/** The geometry of the switch and the radio (measured §13.4; Addendum A: a thinner track under the 19 dp thumb). */
internal object RvControlSize {
    val switchWidth = 50.dp
    val switchHeight = 27.dp
    val switchTrackWidth = 34.dp
    val switchTrackHeight = 14.dp
    val switchThumb = 19.dp
    val switchThumbShadow = 1.dp
    val switchThumbRing = 1.dp
    val switchTrackRim = 1.dp
    val radio = 24.dp
    val radioStroke = 2.dp
    val radioDot = 12.dp

    /** A filter chip (§8 small action button): 40 dp tall, 16 dp before the label, 12 dp after the 16 dp chevron. */
    val chip = 40.dp
    val chipStart = 16.dp
    val chipEnd = 12.dp
    val chipGlyph = 16.dp
}

/**
 * A filter chip under a pushed page's large title, Revolut's small action button (`Personal ▾`, `This month ▾`): the
 * label on the segment track's fill and a chevron that says it opens a choice; a 48 dp target (S7-07).
 */
@Composable
internal fun RvFilterChip(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val size = RvControlSize
    Row(
        modifier
            .minimumInteractiveComponentSize()
            .heightIn(min = size.chip)
            .clip(CircleShape)
            .background(Rv.colors.segmentTrack)
            .rvPress(CircleShape, Role.Button, onClick)
            .padding(start = size.chipStart, end = size.chipEnd, top = RvSpace.tight, bottom = RvSpace.tight),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(RvSpace.tight),
    ) {
        RvText(label, Rv.type.rowTitle)
        RvGlyph(R.drawable.rv_sym_keyboard_arrow_down, size.chipGlyph, Rv.colors.text)
    }
}

/** A range under a chart, Revolut's chip (measured §8): only the selected one has a fill, raised off the page; no track. */
@Composable
internal fun RvRangePill(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = Rv.colors
    Box(
        Modifier
            .minimumInteractiveComponentSize()
            .height(RvChartSize.range)
            .then(if (selected) Modifier.rvRaised(CircleShape) else Modifier.clip(CircleShape))
            .selectable(selected, interactionSource = null, indication = rvPressIndication(), role = Role.Tab, onClick = onClick)
            .padding(horizontal = RvChartSize.rangePad),
        contentAlignment = Alignment.Center,
    ) { RvText(label, Rv.type.action, color = if (selected) colors.text else colors.textSecondary) }
}

/** A raised control's fill in [shape]: a surface over a soft shadow in light, a lighter container in dark. */
@Composable
internal fun Modifier.rvRaised(shape: Shape): Modifier {
    val colors = Rv.colors
    val lift = if (colors.raisedShadow) Modifier.shadow(RvChartSize.rangeShadow, shape) else Modifier
    val rim = colors.raisedRim?.let { Modifier.border(RvChrome.ring, it, shape) } ?: Modifier
    return then(lift).clip(shape).background(colors.raised).then(rim)
}

/**
 * Revolut's segmented control (measured §8): a 36 dp pill track, visible on any surface, [options] in equal parts, the
 * [selected] one on a raised pill 2 dp inside it. Each part is a 48 dp target; the press lights only its pill.
 */
@Composable
internal fun RvSegmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val colors = Rv.colors
    val motion = Rv.motion
    // Each segment's pill as the row lays it out; one raised pill rests there and slides to the next selection.
    val slots = remember(options.size) { mutableStateListOf<IntRect?>().apply { repeat(options.size) { add(null) } } }
    Box(modifier.fillMaxWidth().height(RvSize.touch), contentAlignment = Alignment.Center) {
        Box(Modifier.fillMaxWidth().height(RvChartSize.segment).clip(CircleShape).background(colors.segmentTrack))
        slots.getOrNull(selected)?.let { slot ->
            val x by animateIntAsState(slot.left, motion.tween(RvMotion.SLIDE_MS), label = "segment")
            Box(Modifier.align(Alignment.TopStart).offset { IntOffset(x, slot.top) }.exactSize(slot.width, slot.height).rvRaised(CircleShape))
        }
        Row(Modifier.fillMaxSize()) {
            options.forEachIndexed { i, label ->
                val source = remember { MutableInteractionSource() }
                val ink by animateColorAsState(if (i == selected) colors.text else colors.textSecondary, motion.tween(RvMotion.SLIDE_MS), label = "ink")
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .selectable(i == selected, interactionSource = source, indication = null, role = Role.Tab) { onSelect(i) }
                        .padding(vertical = (RvSize.touch - RvChartSize.segment) / 2)
                        .padding(RvChartSize.segmentInset)
                        .onPlaced { placed -> placed.boundsInParent().roundToIntRect().let { if (slots[i] != it) slots[i] = it } }
                        .clip(CircleShape)
                        .indication(source, rvPressIndication()),
                    contentAlignment = Alignment.Center,
                ) { RvText(label, Rv.type.segment, color = ink) }
            }
        }
    }
}

/** Exactly [width] by [height] pixels, so a part placed from another's measured bounds matches it to the pixel. */
private fun Modifier.exactSize(width: Int, height: Int): Modifier = layout { measurable, _ ->
    val placeable = measurable.measure(Constraints.fixed(width, height))
    layout(width, height) { placeable.place(0, 0) }
}

/**
 * The chart's two tool discs at the end of the range row (measured §8): 35 dp, 8 dp apart. Each keeps a 48 dp target,
 * so the two targets overlap by a few dp between the discs and stick out [rvToolOverhang] past the outer edge.
 */
@Composable
internal fun RvChartTools(first: Pair<Int, String>, second: Pair<Int, String>) {
    Box(Modifier.width(RvChartSize.roundButton * 2 + RvChartSize.roundGap + rvToolOverhang * 2)) {
        Box(Modifier.align(Alignment.CenterStart)) { RvRoundButton(first.first, first.second, NOT_WIRED) }
        Box(Modifier.align(Alignment.CenterEnd)) { RvRoundButton(second.first, second.second, NOT_WIRED) }
    }
}

/** How far a 48 dp target reaches past its 35 dp tool disc. */
internal val rvToolOverhang = (RvSize.touch - RvChartSize.roundButton) / 2

/** A round icon button on a container colour, as after Revolut's range chips; [label] is for the screen reader. */
@Composable
internal fun RvRoundButton(@DrawableRes icon: Int, label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .minimumInteractiveComponentSize()
            .size(RvChartSize.roundButton)
            .clip(CircleShape)
            .background(Rv.colors.roundButton)
            .rvPress(CircleShape, Role.Button, onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { RvGlyph(icon, RvChartSize.roundGlyph, Rv.colors.text) }
}

/**
 * A tag chip on a card's header (`v0.2`), Revolut's tag chips (measured §8): full round, the pill's fill and ink, flat.
 * It is not the floating readout pill: tags cast no shadow (N-04).
 */
@Composable
internal fun RvTag(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier.height(RvChartSize.pill).clip(CircleShape).background(Rv.colors.pill).padding(horizontal = RvChartSize.pillPadH),
        contentAlignment = Alignment.Center,
    ) { RvText(text, Rv.type.pill, color = Rv.colors.textSecondary) }
}

/** The pill's look for a canvas: its fill, and whether it casts the soft shadow. */
internal class RvPillInk(val fill: Color, val shadow: Boolean)

/** The size of the value pill round [text]. */
internal fun DrawScope.rvPillSize(text: TextLayoutResult) = Size(text.size.width + 2 * RvChartSize.pillPadH.toPx(), RvChartSize.pill.toPx())

/** The value pill drawn on a canvas at [topLeft], with [text] centred in it; the shadow is two soft steps under it. */
internal fun DrawScope.rvValuePill(text: TextLayoutResult, topLeft: Offset, ink: RvPillInk) {
    val size = rvPillSize(text)
    val radius = CornerRadius(RvChartSize.pillRadius.toPx())
    if (ink.shadow) {
        val step = RvChartSize.pillShadow.toPx() / 2
        drawRoundRect(Color.Black.copy(alpha = RvAlpha.PILL_SHADOW_FAR), topLeft + Offset(0f, step * 2), size, radius)
        drawRoundRect(Color.Black.copy(alpha = RvAlpha.PILL_SHADOW_NEAR), topLeft + Offset(0f, step), size, radius)
    }
    drawRoundRect(ink.fill, topLeft, size, radius)
    drawText(text, topLeft = topLeft + Offset((size.width - text.size.width) / 2, (size.height - text.size.height) / 2))
}

/**
 * Revolut's switch (§13.4, Addendum A): a 50 × 27 dp node holding a thin 34 dp track and a 19 dp thumb taller than it.
 * On, the thumb is the accent over a tint of it, outlined in the accent where the tint alone would not show on the card;
 * off, a light thumb over the [RvColors.control] track. The row around it owns the toggling.
 */
@Composable
internal fun RvSwitch(checked: Boolean, modifier: Modifier = Modifier) {
    val colors = Rv.colors
    val size = RvControlSize
    val off = (size.switchWidth - size.switchTrackWidth) / 2 - (size.switchThumb - size.switchTrackHeight) / 2
    val x by animateDpAsState(if (checked) off + size.switchTrackWidth - size.switchTrackHeight else off, Rv.motion.tween(RvMotion.SWITCH_MS), label = "thumb")
    Box(modifier.size(size.switchWidth, size.switchHeight).clearAndSetSemantics {}) {
        Box(
            Modifier
                .align(Alignment.Center)
                .size(size.switchTrackWidth, size.switchTrackHeight)
                .clip(CircleShape)
                .background(if (checked) colors.switchOnTrack else colors.control)
                .then(colors.switchOnRim?.takeIf { checked }?.let { Modifier.border(size.switchTrackRim, it, CircleShape) } ?: Modifier),
        )
        Box(
            Modifier
                .align(Alignment.CenterStart)
                .offset(x = x)
                .size(size.switchThumb)
                .shadow(size.switchThumbShadow, CircleShape)
                .background(if (checked) colors.switchOnThumb else colors.switchOffThumb, CircleShape)
                // Off, the light thumb keeps an edge of the track's grey, so it does not melt into the card (L-10).
                .then(if (checked) Modifier else Modifier.border(size.switchThumbRing, colors.control, CircleShape)),
        )
    }
}

/** A radio: a 2 dp ring in [RvColors.control], in the accent with a dot inside once selected. */
@Composable
internal fun RvRadio(selected: Boolean, modifier: Modifier = Modifier) {
    val ring = if (selected) Rv.colors.positive else Rv.colors.control
    Canvas(modifier.size(RvControlSize.radio).clearAndSetSemantics {}) {
        val stroke = RvControlSize.radioStroke.toPx()
        drawCircle(ring, radius = (size.minDimension - stroke) / 2, style = Stroke(stroke))
        if (selected) drawCircle(ring, radius = RvControlSize.radioDot.toPx() / 2)
    }
}
