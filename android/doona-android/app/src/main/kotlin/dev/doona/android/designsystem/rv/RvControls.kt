package dev.doona.android.designsystem.rv

import androidx.annotation.DrawableRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.roundToIntRect
import dev.doona.android.R
import dev.doona.android.feature.NOT_WIRED

// The sample's controls, one of each: range chip, segmented control, chart tool disc, value pill, primary button, text
// field, switch and radio.

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

    /** A tag or status chip (§8: Revolut's tag chips are 36 dp tall, full round, with a 16 dp leading glyph). */
    val tag = 36.dp
    val tagPad = 12.dp
    val tagGlyph = 16.dp

    /** A filter chip (§8 small action button): 40 dp tall, 16 dp before the label, 12 dp after the 16 dp chevron. */
    val chip = 40.dp
    val chipStart = 16.dp
    val chipEnd = 12.dp
    val chipGlyph = 16.dp

    /**
     * The primary button, node detail's approved floating pill moved here (§8, N06: 52 dp, over the 48 dp target), and
     * its soft shadow, cast from its own shape (S7-16).
     */
    val primary = 52.dp
    val primaryShadow = 12.dp
    val primaryShadowDrop = 4.dp

    /** The secondary button in a card or a sheet (revolut-ux3 §3: Revolut's in-card button, 40 dp, full round). */
    val secondary = 40.dp

    /**
     * The text field: the search pill's shape (measured §4, full round) at the primary button's 52 dp, its text 16 dp in
     * as a row's is, its label 8 dp above it as a round action's label sits under its disc (§6), and a 1 dp ring for
     * focus, as the switch's on track is outlined.
     */
    val field = 52.dp
    val fieldRing = 1.dp
    val fieldTrailing = 4.dp
}

/**
 * A filter chip under a pushed page's large title, Revolut's small action button (`Personal ▾`, `This month ▾`): the
 * label on the segment track's fill and a chevron that says it opens a choice. The 40 dp pill is its layout box, so the
 * header measures from its visible edge (spacing audit row 3); its target reaches 48 dp (S7-07) through Compose's touch
 * target expansion, which does not move the layout.
 */
@Composable
internal fun RvFilterChip(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val size = RvControlSize
    Row(
        modifier
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

/**
 * A row of Revolut's range chips (measured §8), [labels] in order, the [selected] one raised, [RvChartSize.rangeGap]
 * apart: under a chart it picks the range; under a node list's search it picks the order. The labels take one size, the
 * largest at which the whole row fits its width ([rvChipRowFit]), and every pill grows with that size's line, keeping
 * [RvChartSize.rangeInset] above and below it, so at a large font size no label is cut or runs past its pill.
 */
@Composable
internal fun RvRangeRow(labels: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    var width by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val style = rvChipRowFit(labels, Rv.type.action, RvChartSize.rangePad, RvChartSize.rangeGap, with(density) { width.toDp() })
    val height = maxOf(RvChartSize.range, with(density) { style.lineHeight.toDp() } + RvChartSize.rangeInset * 2)
    Row(modifier.fillMaxWidth().onSizeChanged { width = it.width }.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(RvChartSize.rangeGap)) {
        labels.forEachIndexed { i, label -> RvRangePill(label, i == selected, style, height) { onSelect(i) } }
    }
}

/**
 * One of a few choices in a row, Revolut's range chip: only the selected one has a fill, raised off the page; no track.
 * The pill ([height], 36 dp at the default size) is its layout box, so a row of them measures from its visible edge; its
 * target reaches 48 dp through Compose's touch target expansion, which does not move the layout.
 */
@Composable
private fun RvRangePill(label: String, selected: Boolean, style: TextStyle, height: Dp, onClick: () -> Unit) {
    val colors = Rv.colors
    Box(
        Modifier
            .height(height)
            .then(if (selected) Modifier.rvRaised(CircleShape) else Modifier.clip(CircleShape))
            .selectable(selected, interactionSource = null, indication = rvPressIndication(), role = Role.Tab, onClick = onClick)
            .padding(horizontal = RvChartSize.rangePad),
        contentAlignment = Alignment.Center,
    ) { RvText(label, style, color = if (selected) colors.text else colors.textSecondary) }
}

/** How far a raised chip's shadow reaches under it in this theme; dark draws no shadow (its pill is a lighter fill). */
internal val rvRaisedReach: Dp
    @Composable get() = if (Rv.colors.raisedShadow) RvChartSize.rangeShadowReach else 0.dp // literal: no shadow, so no reach

/** A raised control's fill in [shape]: a surface over a soft shadow in light, a lighter container in dark. */
@Composable
internal fun Modifier.rvRaised(shape: Shape): Modifier {
    val colors = Rv.colors
    val lift = if (colors.raisedShadow) Modifier.shadow(RvChartSize.rangeShadow, shape) else Modifier
    val rim = colors.raisedRim?.let { Modifier.border(RvChrome.ring, it, shape) } ?: Modifier
    return then(lift).clip(shape).background(colors.raised).then(rim)
}

/**
 * Revolut's segmented control (measured §8) in the owner's look E (rv-fix-4e), the one slider of every page: a 40 dp
 * pill track, visible on any surface, [options] in equal parts, each after its glyph when [glyphs] gives one drawable
 * per option, the [selected] one on a raised pill 2 dp inside it (a soft shadow in light, a rim in dark). The labels
 * take one size ([rvSegmentStyle]) and the control grows with their line at a large font size ([rvSegmentHeight]), so
 * no label is cut. The track is the control's layout box, so what is above and below it measures from its visible edge
 * (spacing audit row 4); each part's target reaches 48 dp through Compose's touch target expansion, which does not move
 * the layout. The press lights only its pill.
 */
@Composable
internal fun RvSegmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier, glyphs: List<Int> = emptyList()) {
    val colors = Rv.colors
    val motion = Rv.motion
    // Each segment's pill as the row lays it out; one raised pill rests there and slides to the next selection.
    val slots = remember(options.size) { mutableStateListOf<IntRect?>().apply { repeat(options.size) { add(null) } } }
    var width by remember { mutableIntStateOf(0) }
    val style = rvSegmentStyle(options, glyphs.isNotEmpty(), width)
    Box(modifier.fillMaxWidth().height(rvSegmentHeight(style)).onSizeChanged { width = it.width }.testTag(RvPart.SEGMENTED)) {
        Box(Modifier.matchParentSize().clip(CircleShape).background(colors.segmentTrack))
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
                        .padding(RvChartSize.segmentInset)
                        .onPlaced { placed -> placed.boundsInParent().roundToIntRect().let { if (slots[i] != it) slots[i] = it } }
                        .clip(CircleShape)
                        .indication(source, rvPressIndication()),
                    contentAlignment = Alignment.Center,
                ) { RvSegmentLabel(label, glyphs.getOrNull(i), style, ink) }
            }
        }
    }
}

/**
 * The segments' labels in one size for all [options] (one label size per row): the largest at which each fits on one
 * line in its part of the control [width] px wide, a range chip's padding ([RvChartSize.rangePad]) clear of its pill's
 * round ends, after its [glyph] when the control has glyphs ([rvRowFit]). Before the control is measured ([width] 0)
 * it is the full size.
 */
@Composable
private fun rvSegmentStyle(options: List<String>, glyph: Boolean, width: Int): TextStyle {
    val style = Rv.type.segment
    if (width == 0 || options.isEmpty()) return style
    val pad = RvChartSize.segmentInset + RvChartSize.rangePad
    val column = with(LocalDensity.current) { (width / options.size).toDp() } - pad * 2
    return rvRowFit(options, style, if (glyph) column - RvChartSize.segmentGlyph - RvChartSize.segmentGlyphGap else column)
}

/**
 * The control's height for labels in [style]: [RvChartSize.segment], or the labels' line and the pill's inset above and
 * below it where that is more, as at font scale 2.0, so a label is never cut at the top or the bottom.
 */
@Composable
internal fun rvSegmentHeight(style: TextStyle): Dp {
    val line = with(LocalDensity.current) { style.lineHeight.toDp() }
    return maxOf(RvChartSize.segment, line + RvChartSize.segmentInset * 2)
}

/** A segment's label in [style] and [color], after its [glyph] when it has one. */
@Composable
private fun RvSegmentLabel(text: String, @DrawableRes glyph: Int?, style: TextStyle, color: Color) {
    if (glyph == null) {
        RvText(text, style, color = color)
        return
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        RvGlyph(glyph, RvChartSize.segmentGlyph, color)
        Spacer(Modifier.width(RvChartSize.segmentGlyphGap))
        RvText(text, style, color = color)
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
 * The page's one main action, Revolut's full-width pill (§8): node detail's approved 'Get started' pill, moved here. One
 * style, the ink's fill (white in dark, Addendum A) with the page's colour as its label, the soft shadow under it in
 * light. Pressed, the label's colour lies over the fill as the state layer;
 * disabled, the button stays flat at [RvAlpha.DISABLED] and takes no press. At least [RvControlSize.primary] tall, so the
 * whole pill is the target. [interactionSource] lets a caller (or a test) observe or drive the press.
 */
@Composable
internal fun RvPrimaryButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    interactionSource: MutableInteractionSource? = null,
) {
    val colors = Rv.colors
    val size = RvControlSize
    val fill = if (colors.dark) Color.White else colors.text
    val source = interactionSource ?: remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    // Disabled, the pill is opaque in the colour its faded fill gives over the page, so a list scrolling under a floating
    // action never shows through it; its label in the page's colour looks as it did faded.
    val shown = when {
        !enabled -> fill.copy(alpha = RvAlpha.DISABLED).compositeOver(colors.page)
        pressed -> colors.page.copy(alpha = RvAlpha.PRESSED).compositeOver(fill)
        else -> fill
    }
    val lift = if (enabled) Modifier.rvSoftShadow(CircleShape, size.primaryShadow, size.primaryShadowDrop, RvAlpha.CTA_SHADOW) else Modifier
    Box(
        modifier
            .fillMaxWidth()
            .heightIn(min = size.primary)
            .then(lift)
            .clip(CircleShape)
            .background(shown)
            .clickable(interactionSource = source, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = RvSpace.gutter, vertical = RvSpace.tight),
        contentAlignment = Alignment.Center,
    ) { RvText(label, Rv.type.button, color = colors.page, align = TextAlign.Center) }
}

/**
 * A card's or a sheet's secondary action, Revolut's in-card button (revolut-ux3 §3): a full-width pill at least
 * [RvControlSize.secondary] tall on the filter chip's neutral fill (the segment track, which reads on a card, a sheet
 * and the page), its 17 sp semibold [label] in ink, centred; pressed, the state layer. It never takes a hue, so it never
 * pairs a green or teal action with a red glyph. Its target reaches 48 dp through Compose's touch target expansion.
 * Disabled, the whole pill is at [RvAlpha.DISABLED] and takes no press. Floating where a page scrolls under it, it takes
 * the colour it stands on as [over] and, as [RvPrimaryButton], is opaque in the colours it shows there, faded or not,
 * so nothing under it shows through.
 */
@Composable
internal fun RvSecondaryButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, over: Color? = null) {
    val colors = Rv.colors
    val track = over?.let { colors.segmentTrack.compositeOver(it) } ?: colors.segmentTrack
    val faded = { color: Color -> if (enabled || over == null) color else color.copy(alpha = RvAlpha.DISABLED).compositeOver(over) }
    // A pill with nothing under it to hide fades as one layer.
    val layer = if (enabled || over != null) Modifier else Modifier.graphicsLayer { alpha = RvAlpha.DISABLED }
    val press = if (enabled) {
        Modifier.rvPress(CircleShape, Role.Button, onClick)
    } else {
        Modifier.semantics(mergeDescendants = true) {
            role = Role.Button
            disabled()
        }
    }
    Box(
        modifier
            .fillMaxWidth()
            .heightIn(min = RvControlSize.secondary)
            .then(layer)
            .clip(CircleShape)
            .background(faded(track))
            .then(press)
            .padding(horizontal = RvSpace.gutter, vertical = RvSpace.tight),
        contentAlignment = Alignment.Center,
    ) { RvText(label, Rv.type.action, color = faded(colors.text), align = TextAlign.Center) }
}

/**
 * A single-line text field. The [label] sits over the field in secondary ink; it is null when the title right above
 * already names the field, and [name] then names it for a screen reader. The field is the search pill's shape
 * (measured §4) on the segmented control's track, which reads on the page, on a card and in a sheet, holding [value] in
 * 19 sp (the stat value, measured §11) or [placeholder] in secondary ink; under it [supporting] text, or [error] in the
 * error hue, which also marks it for a screen reader; no approved page outlines an error, so the message alone shows
 * it. Focused, the field is ringed in the accent and
 * the cursor is the accent. Label, field and message are one target, as Material's field is; [trailing] (a clear or
 * paste button) sits inside the field's end. Disabled, the whole field is at [RvAlpha.DISABLED] and takes no input.
 */
@Composable
internal fun RvTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String?,
    modifier: Modifier = Modifier,
    name: String? = null,
    placeholder: String? = null,
    supporting: String? = null,
    error: String? = null,
    enabled: Boolean = true,
    keyboard: KeyboardOptions = KeyboardOptions.Default,
    actions: KeyboardActions = KeyboardActions.Default,
    trailing: (@Composable () -> Unit)? = null,
) {
    val colors = Rv.colors
    val size = RvControlSize
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val ring = if (focused && enabled) colors.positive else null
    BasicTextField(
        value,
        onValueChange,
        modifier
            .fillMaxWidth()
            .graphicsLayer { alpha = if (enabled) 1f else RvAlpha.DISABLED }
            .semantics {
                if (label == null && name != null) contentDescription = name
                if (error != null) error(error)
            },
        enabled = enabled,
        textStyle = Rv.type.field.copy(color = colors.text),
        keyboardOptions = keyboard,
        keyboardActions = actions,
        singleLine = true,
        visualTransformation = RvScripted,
        interactionSource = source,
        cursorBrush = SolidColor(colors.positive),
        decorationBox = { field ->
            Column {
                if (label != null) {
                    RvText(label, Rv.type.rowSub, Modifier.padding(horizontal = RvSpace.rowPadding), color = colors.textSecondary)
                    Spacer(Modifier.height(RvSpace.discToLabel))
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = size.field)
                        .clip(CircleShape)
                        .background(colors.segmentTrack)
                        .then(ring?.let { Modifier.border(size.fieldRing, it, CircleShape) } ?: Modifier)
                        .padding(start = RvSpace.rowPadding, end = if (trailing != null) size.fieldTrailing else RvSpace.rowPadding),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                        if (value.isEmpty() && placeholder != null) RvText(placeholder, Rv.type.field, color = colors.textSecondary)
                        field()
                    }
                    trailing?.invoke()
                }
                (error ?: supporting)?.let { RvFieldMessage(it, error = error != null) }
            }
        },
    )
}

/**
 * The message under a field or a form's card: [text] in the row's 17 sp, on the card's content inset, [RvSpace.subGap]
 * under what it describes, in the error hue when it is an [error] (else in secondary ink), wrapping to two lines.
 * [RvTextField] sets its supporting and error text with it; a form whose choice cannot be saved says why with it under
 * that choice's card (依 App's 至少要勾選一個 App).
 */
@Composable
internal fun RvFieldMessage(text: String, modifier: Modifier = Modifier, error: Boolean = true) {
    val pad = Modifier.padding(start = RvSpace.rowPadding, end = RvSpace.rowPadding, top = RvSpace.subGap)
    RvText(text, Rv.type.rowSub, modifier.then(pad), color = if (error) Rv.colors.error else Rv.colors.textSecondary, maxLines = MESSAGE_LINES)
}

/** What the user types, with every run of CJK set in Noto as [RvText] sets it; the text itself is unchanged. */
private val RvScripted = VisualTransformation { TransformedText(scripted(it.text), OffsetMapping.Identity) }

/** A field's supporting or error message wraps to at most this many lines. */
private const val MESSAGE_LINES = 2

/**
 * A tag chip on a card's header (`v0.2`), Revolut's tag chip (measured §8, [RvControlSize.tag]): full round, the pill's
 * fill and ink, flat.
 * It is not the floating readout pill: tags cast no shadow (N-04).
 */
@Composable
internal fun RvTag(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier.heightIn(min = RvControlSize.tag).clip(CircleShape).background(Rv.colors.pill).padding(horizontal = RvControlSize.tagPad),
        contentAlignment = Alignment.Center,
    ) { RvText(text, Rv.type.pill, color = Rv.colors.textSecondary) }
}

/** The pill's look for a canvas: its fill, and whether it casts the soft shadow. */
internal class RvPillInk(val fill: Color, val shadow: Boolean)

/** The size of the value pill round [text]. */
internal fun DrawScope.rvPillSize(text: TextLayoutResult) = Size(text.size.width + 2 * RvChartSize.pillPadH.toPx(), RvChartSize.pill.toPx())

/**
 * The value pill drawn on a canvas at [topLeft], with [text] centred in it; the shadow is two soft steps under it. The
 * whole pill fades by [alpha], as a chart's series does while its range changes.
 */
internal fun DrawScope.rvValuePill(text: TextLayoutResult, topLeft: Offset, ink: RvPillInk, alpha: Float = 1f) {
    val size = rvPillSize(text)
    val radius = CornerRadius(size.height / 2)
    if (ink.shadow) {
        val step = RvChartSize.pillShadow.toPx() / 2
        drawRoundRect(Color.Black.copy(alpha = RvAlpha.PILL_SHADOW_FAR), topLeft + Offset(0f, step * 2), size, radius, alpha = alpha)
        drawRoundRect(Color.Black.copy(alpha = RvAlpha.PILL_SHADOW_NEAR), topLeft + Offset(0f, step), size, radius, alpha = alpha)
    }
    drawRoundRect(ink.fill, topLeft, size, radius, alpha = alpha)
    // NaN leaves the text's own colour as it is; only a fading pill changes it.
    val center = topLeft + Offset((size.width - text.size.width) / 2, (size.height - text.size.height) / 2)
    drawText(text, topLeft = center, alpha = if (alpha < 1f) alpha else Float.NaN)
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

/** A card row whose whole width toggles its switch: [icon]'s disc, [title] over an optional [subtitle], the switch at the end. */
@Composable
internal fun RvSwitchRow(@DrawableRes icon: Int, title: String, checked: Boolean, subtitle: String? = null, onChange: (Boolean) -> Unit) {
    val toggle = Modifier.toggleable(checked, interactionSource = null, indication = rvRowIndication(), role = Role.Switch, onValueChange = onChange)
    RvRow({ RvIconDisc(icon) }, title, subtitle, toggle) { RvSwitch(checked) }
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
