package dev.doona.android.designsystem.rv

import androidx.annotation.DrawableRes
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.em
import kotlinx.coroutines.launch
import kotlin.math.ceil
import kotlin.math.roundToInt

/** Where values sit in a chart's canvas, in pixels: 0 on [bottom], [high] on [top]. */
private class Scale(val high: Float, val top: Float, val bottom: Float) {
    fun y(value: Float) = if (high <= 0f) bottom else bottom - (bottom - top) * (value / high).coerceIn(0f, 1f)
}

/** A tile's figure: the number large, its unit small on the same baseline. */
internal class RvFigure(val whole: String, val unit: String)

/** An amount as a figure: the number large, the unit small. */
internal fun RvAmount.figure() = RvFigure(number, unit)

/** [RvFormat] in the current locale: how every page writes bytes, rates, durations and dates. */
@Composable
internal fun rememberRvFormat(): RvFormat {
    val locale = LocalConfiguration.current.locales[0]
    return remember(locale) { RvFormat(locale) }
}

/**
 * Tiles side by side ([RvSparkTile]), [RvSpace.tileGap] apart, each as tall as the tallest: square, or taller, all of
 * them, when one's text needs more room at a large font size.
 */
@Composable
internal fun RvTileRow(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Row(modifier.height(IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(RvSpace.tileGap), content = content)
}

/**
 * One of Revolut's crypto home tiles, square as they are: [caption] at the top start, [icon] in the series' tinted 20 dp
 * disc at the top end, the [figure] in bold, a secondary [note] under it, and the sparkline of [values] at the bottom
 * (measured §10.2: 148 x 60 dp). With [quiet] (no
 * connection, or no traffic) the tile takes Revolut's empty chart card instead: a larger figure over ghost bars, and
 * [note] says why there is no line. The whole tile is one button.
 *
 * At a large font size the figure keeps one line, smaller where it must ([RvFigureText], fitting [widest] too), the note
 * wraps, and the tile grows past square to hold them over the whole sparkline; in a [RvTileRow] its neighbour grows
 * with it.
 */
@Composable
internal fun RvSparkTile(
    caption: String,
    @DrawableRes icon: Int,
    figure: RvFigure,
    note: String,
    values: List<Float>,
    series: RvSeries,
    quiet: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    ghost: FloatArray = RV_GHOST_DOWN,
    widest: List<RvFigure> = emptyList(),
) {
    val type = Rv.type
    RvCard(modifier.fillMaxHeight().rvPress(RoundedCornerShape(Rv.cardRadius), Role.Button, onClick)) {
        Column(Modifier.fillMaxWidth().rvSquareAtLeast().padding(RvChartSize.tilePad)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // One tile layout in every state and on every page: the in-card header's 19 sp, the value at the tile's size (L-03).
                RvText(caption, type.cardTitle, Modifier.weight(1f), color = Rv.colors.textSecondary)
                Box(Modifier.size(RvChartSize.tileDisc).clip(CircleShape).background(series.disc), contentAlignment = Alignment.Center) {
                    RvGlyph(icon, RvChartSize.tileGlyph, series.line)
                }
            }
            Spacer(Modifier.height(RvSpace.tight))
            // Each second's rate cross-fades in instead of jumping.
            Crossfade(figure.whole to figure.unit, animationSpec = Rv.motion.tween(RvMotion.FADE_MS), label = "tile") { (whole, unit) ->
                RvFigureText(RvFigure(whole, unit), type.tileValue, type.tileUnit, widest = widest)
            }
            RvText(note, type.tileCaption, color = Rv.colors.textSecondary, maxLines = Int.MAX_VALUE)
            Spacer(Modifier.weight(1f))
            if (quiet) RvGhostBars(ghost) else RvSparkline(values, series.line)
        }
    }
}

/**
 * At least as tall as it is wide: a tile's square, taller only when its content needs it. The height the place gives
 * (a [RvTileRow]'s) is filled; where it gives none, the square is.
 */
private fun Modifier.rvSquareAtLeast() = layout { measurable, constraints ->
    val side = minOf(constraints.maxWidth, constraints.maxHeight)
    val placeable = measurable.measure(constraints.copy(minHeight = maxOf(constraints.minHeight, side)))
    // Asked for its intrinsic height, the content answers with its own, which the square may exceed.
    layout(placeable.width, maxOf(placeable.height, side)) { placeable.place(0, 0) }
}

/**
 * A figure in [style], its unit in [unitStyle] on the same baseline; Latin only, as figures and units are. It keeps one
 * line: where it, or one of the [widest] figures the place may show, does not fit the width, number and unit shrink
 * together to the largest size at which all of them fit ([rvFigureFit]), never cut.
 */
@Composable
internal fun RvFigureText(figure: RvFigure, style: TextStyle, unitStyle: TextStyle, modifier: Modifier = Modifier, widest: List<RvFigure> = emptyList()) {
    // The unit as a share of the figure's size, so the two shrink together. The share is taken at this font scale, whose
    // sizes do not grow in proportion, and of the unit's size in whole px, as a span in sp is set: unshrunk, the unit
    // is the size it was.
    val density = LocalDensity.current
    val unit = remember(style, unitStyle, density) {
        val share = with(density) { unitStyle.fontSize.toPx().roundToInt() / style.fontSize.toPx() }
        unitStyle.toSpanStyle().copy(fontSize = share.em)
    }
    val all = remember(widest, unit) { widest.map { it.text(unit) } }
    BasicText(figure.text(unit), modifier, style = style.copy(color = Rv.colors.text), maxLines = 1, autoSize = rvFigureFit(style, all))
}

/** The figure as one text, its unit in [unit]. */
private fun RvFigure.text(unit: SpanStyle) = buildAnnotatedString {
    append(whole)
    withStyle(unit) { append(this@text.unit) }
}

/**
 * A tile's sparkline, as Revolut's crypto tiles draw one: [values] as a 2 dp line in [color] from zero to 10 % above
 * their peak, over a neutral grey fill that fades to nothing at the bottom, and a light grey dotted line at the opening
 * value, the window's first. No axes and no labels.
 */
@Composable
internal fun RvSparkline(values: List<Float>, color: Color, modifier: Modifier = Modifier) {
    val colors = Rv.colors
    val glide = rememberRvGlide(values)
    // A layer of its own: a frame of the glide records the line alone, not the page around it with the glass over it.
    Canvas(modifier.fillMaxWidth().height(RvChartSize.spark).graphicsLayer {}) {
        if (values.size < 2) return@Canvas
        val stroke = RvChartSize.sparkStroke.toPx()
        val scale = Scale(values.max() * RvChartSize.SPARK_HEADROOM, 0f, size.height - stroke / 2)
        gliding(glide, values, size.width) { drawn, right ->
            drawPath(areaUnder(drawn, scale, right, size.height), Brush.verticalGradient(listOf(colors.tileFill, Color.Transparent), scale.y(values.max()), size.height))
        }
        dottedAcross(colors.reference, scale.y(values.first()))
        gliding(glide, values, size.width) { drawn, right -> drawPath(polyline(drawn, scale, right), color, style = lineStroke(stroke)) }
    }
}

/**
 * Draws [draw] with [values] at rest, or, while [glide] runs, with the sample that just left in front and the whole
 * moved right by what is left of a step, clipped to [right]: the window scrolls on by one sample each second.
 */
private inline fun DrawScope.gliding(glide: RvGlide, values: List<Float>, right: Float, draw: DrawScope.(List<Float>, Float) -> Unit) {
    val lead = glide.lead
    if (lead == null) return draw(values, right)
    val step = right / (values.size - 1)
    clipRect(right = right) {
        translate(left = (glide.drawnShift() - 1f) * step) { draw(listOf(lead) + values, right + step) }
    }
}

/** Revolut's empty 'Income' card: twelve faint bars of made-up [heights] across the width, each fading out downward. */
@Composable
internal fun RvGhostBars(heights: FloatArray, modifier: Modifier = Modifier) {
    val ghost = Rv.colors.ghost
    Canvas(modifier.fillMaxWidth().height(RvChartSize.spark)) {
        val bar = RvChartSize.ghostBar.toPx()
        val pitch = (size.width - bar) / (RvChartSize.GHOST_BARS - 1)
        heights.forEachIndexed { i, height ->
            val top = size.height * (1 - height)
            val brush = Brush.verticalGradient(listOf(ghost, Color.Transparent), top, size.height)
            drawRoundRect(brush, Offset(i * pitch, top), Size(bar, size.height - top), CornerRadius(bar / 2))
        }
    }
}

/** The detail chart's test tag: where a test scrubs it and captures it. */
internal const val RV_CHART = "rv-chart"

/**
 * Revolut's stock chart (measured §10.1), full-bleed: [values] as a line in [series]'s colour on an axis from zero to
 * peak × 1.15, over a tint that fades out linearly to the plot's bottom, on a hairline grid that fades at its ends. A
 * dotted line marks the start value. Opaque pills carry the peak (right), the low (right) and the start (left); each sits
 * on its value where the line leaves room, else just above or under it, and the strip under the plot keeps room for a
 * pill under the bottom. The live point at the end carries a soft halo. A missing sample (NaN) breaks the line. A finger
 * on the chart moves [scrub] to the second under it, shows a line in the series' colour and a dot there and hides the
 * pills; lifting it calls [onScrub] with null. The scrub ticks as it reaches the peak sample or either end (G5). A new
 * [range] cross-fades from the old series to the new one while the axis moves to the new peak, with no placeholder.
 */
@Composable
internal fun RvDetailChart(values: List<Float>, series: RvSeries, label: (Float) -> String, scrub: Int?, onScrub: (Int?) -> Unit, modifier: Modifier = Modifier, range: Int = 0) {
    val colors = Rv.colors
    val ink = DetailInk(series, colors, RvPillInk(colors.pill, colors.raisedShadow), Rv.type.pill.copy(color = colors.textSecondary), rememberTextMeasurer(), label)
    val glide = rememberRvGlide(values)
    val change = rememberRangeChange(values, range)
    val haptics = LocalHapticFeedback.current
    val ticks = remember(haptics) { ScrubTicks(haptics) }
    val follow: (Int?) -> Unit = { i ->
        ticks.follow(i, values)
        onScrub(i)
    }
    Canvas(modifier.fillMaxWidth().height(RvChartSize.detail + RvChartSize.pill + RvChartSize.pillClear).testTag(RV_CHART).scrubbing(values.size, follow)) {
        hairlineGrid(colors.grid, RvChartSize.detail.toPx())
        val top = if (change.moving) change.top.value else axisTop(values)
        val fade = change.fade.value
        change.before?.takeIf { fade < 1f }?.let { detailSeries(it, ink, top, AT_REST, 1f - fade, null) }
        detailSeries(values, ink, top, glide, fade, scrub)
    }
}

/** What the detail chart draws a series with: its colours, the pills' look and type, and how a value reads. */
private class DetailInk(val series: RvSeries, colors: RvColors, val pill: RvPillInk, val pillStyle: TextStyle, val measurer: TextMeasurer, val label: (Float) -> String) {
    val reference = colors.detailReference
    val ring = colors.page
}

/**
 * One series of the detail chart at [alpha] on an axis from zero to [top]: its fill, the dotted start line, the line, the
 * live point, then the [scrub]bed second's marker or else the value pills.
 */
private fun DrawScope.detailSeries(values: List<Float>, ink: DetailInk, top: Float, glide: RvGlide, alpha: Float, scrub: Int?) {
    val known = values.filterNot { it.isNaN() }
    if (known.size < 2) return
    val plot = RvChartSize.detail.toPx()
    val stroke = RvChartSize.detailStroke.toPx()
    val line = ink.series.line
    val peak = known.max()
    val scale = Scale(top, 0f, plot - stroke)
    val right = size.width - RvChartSize.detailEnd.toPx()
    gliding(glide, values, right) { drawn, end ->
        drawPath(areaUnder(drawn, scale, end, plot), Brush.verticalGradient(listOf(ink.series.area, Color.Transparent), scale.y(peak), plot), alpha)
    }
    dottedAcross(ink.reference, scale.y(known.first()), alpha)
    gliding(glide, values, right) { drawn, end -> drawPath(polyline(drawn, scale, end), line, alpha, lineStroke(stroke)) }
    val live = liveValue(values, glide)?.let { Offset(right, scale.y(it)) }
    live?.let {
        drawCircle(line.copy(alpha = RvAlpha.HALO), RvChartSize.liveHalo.toPx() / 2, it, alpha)
        drawCircle(line, RvChartSize.liveDot.toPx() / 2, it, alpha)
    }
    val i = scrub?.takeIf { it in values.indices && !values[it].isNaN() }
    if (i != null) return scrubMarker(Offset(right * i / (values.size - 1), scale.y(values[i])), line, ink.ring, plot)
    val pills = PillPlacer(this, values, scale, right, live)
    pills.place(ink.measurer.measure(ink.label(peak), ink.pillStyle), scale.y(peak), end = true, ink.pill, alpha)
    pills.place(ink.measurer.measure(ink.label(known.min()), ink.pillStyle), scale.y(known.min()), end = true, ink.pill, alpha)
    pills.place(ink.measurer.measure(ink.label(known.first()), ink.pillStyle), scale.y(known.first()), end = false, ink.pill, alpha)
}

/** The detail chart's axis for [values]: up to their peak × [RvChartSize.DETAIL_HEADROOM], or 0 with nothing known. */
private fun axisTop(values: List<Float>): Float = (values.filterNot { it.isNaN() }.maxOrNull() ?: 0f) * RvChartSize.DETAIL_HEADROOM

/** A series that does not glide: the one a range change fades out. */
private val AT_REST = RvGlide(null, Animatable(0f))

/**
 * A change of range on the detail chart (G5): the series shown [before] fades out as the new one fades in ([fade], 0 to
 * 1 over [RvMotion.FADE_MS]), and the axis's [top] moves from where it was to the new series' over [RvMotion.MOVE_MS],
 * [moving] until it is there. With nothing before (the first range shown, or at animator scale 0) it is at rest at once.
 */
private class RangeChange(val before: List<Float>?, from: Float) {
    val fade = Animatable(if (before == null) 1f else 0f)
    val top = Animatable(from)
    var moving by mutableStateOf(before != null)
}

/** The series last shown and the change that showed it, carried from one range to the next. */
private class RangeHeld(var values: List<Float>? = null, var change: RangeChange? = null)

@Composable
private fun rememberRangeChange(values: List<Float>, range: Int): RangeChange {
    val motion = Rv.motion
    val held = remember { RangeHeld() }
    val change = remember(range) {
        val before = held.values?.takeIf { !motion.instant }
        // A change caught on its way moves the axis on from where it is.
        val from = held.change?.takeIf { it.moving }?.top?.value ?: before?.let(::axisTop) ?: 0f
        RangeChange(before, from).also { held.change = it }
    }
    held.values = values
    LaunchedEffect(change) {
        if (change.before == null) return@LaunchedEffect
        launch { change.fade.animateTo(1f, motion.tween(RvMotion.FADE_MS, LinearEasing)) }
        change.top.animateTo(axisTop(values), motion.tween(RvMotion.MOVE_MS))
        change.moving = false
    }
    return change
}

/**
 * The scrub's haptic ticks (G5): one as the finger reaches the series' peak sample or either end, whether it lands on
 * the sample or passes over it, and none while it stays there.
 */
private class ScrubTicks(private val haptics: HapticFeedback) {
    private var at: Int? = null

    fun follow(i: Int?, values: List<Float>) {
        val from = at
        at = i
        if (i == null || i == from) return
        val peak = values.indices.filterNot { values[it].isNaN() }.maxByOrNull { values[it] } ?: return
        if (listOf(0, peak, values.lastIndex).any { reaches(from, i, it) }) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
    }

    /** Whether a move from [from] (none: the finger just landed) to [to] arrives at or passes over [mark]. */
    private fun reaches(from: Int?, to: Int, mark: Int) = if (from == null) to == mark else mark != from && mark in minOf(from, to)..maxOf(from, to)
}

/** The value the line crosses the chart's right edge at: the last sample, or while it glides in, on its way to it. */
private fun liveValue(values: List<Float>, glide: RvGlide): Float? {
    val last = values.lastOrNull()?.takeUnless { it.isNaN() } ?: return null
    val before = values.getOrNull(values.size - 2)?.takeUnless { it.isNaN() || glide.lead == null } ?: return last
    return last + (before - last) * glide.drawnShift()
}

/**
 * Sets value pills on the detail chart without covering its line, its live point or one another: each tries its value's
 * height, then just above it, then just under it (down into the strip under the plot), and takes the first that is clear.
 */
private class PillPlacer(val scope: DrawScope, val values: List<Float>, val scale: Scale, val right: Float, live: Offset?) {
    private val taken = mutableListOf<Rect>()
    private val clear = with(scope) { RvChartSize.pillClear.toPx() }

    init {
        live?.let { taken += Rect(it, with(scope) { RvChartSize.liveHalo.toPx() / 2 }) }
    }

    fun place(text: TextLayoutResult, y: Float, end: Boolean, ink: RvPillInk, alpha: Float) = with(scope) {
        val pill = rvPillSize(text)
        val inset = RvChartSize.pillInset.toPx()
        val left = if (end) size.width - inset - pill.width else inset
        val tops = listOf(y - pill.height / 2, y - clear - pill.height, y + clear).map { it.coerceIn(0f, size.height - pill.height) }
        val top = tops.firstOrNull { free(Rect(Offset(left, it), pill)) } ?: tops.last()
        val rect = Rect(Offset(left, top), pill)
        taken += rect
        rvValuePill(text, rect.topLeft, ink, alpha)
    }

    /** Whether [rect], grown by the clearance, misses the other pills, the live point and every segment of the line. */
    private fun free(rect: Rect): Boolean {
        val zone = rect.inflate(clear)
        if (taken.any { it.overlaps(zone) }) return false
        val step = right / (values.size - 1)
        return values.indices.none { i ->
            val x = i * step
            val y = values[i].takeUnless { it.isNaN() }?.let(scale::y) ?: return@none false
            val next = values.getOrNull(i + 1)?.takeUnless { it.isNaN() }?.let(scale::y) ?: y
            // The segment from this sample to the next crosses the zone's columns somewhere between its two heights.
            x <= zone.right && x + step >= zone.left && maxOf(y, next) >= zone.top && minOf(y, next) <= zone.bottom
        }
    }
}

/** Press and drag: [onScrub] gets the second under the finger as it moves, and null when it lifts. */
@Composable
private fun Modifier.scrubbing(count: Int, onScrub: (Int?) -> Unit): Modifier {
    val report by rememberUpdatedState(onScrub)
    if (count < 2) return this
    return pointerInput(count) {
        // Read at every event rather than once: the chart can change width without changing its count.
        fun right() = size.width - RvChartSize.detailEnd.toPx()
        awaitEachGesture {
            val down = awaitFirstDown()
            down.consume()
            RvFrames.begin(SCRUB)
            report(sampleAt(down.position.x, right(), count))
            try {
                while (true) {
                    val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) break
                    change.consume()
                    report(sampleAt(change.position.x, right(), count))
                }
            } finally {
                report(null)
                RvFrames.end(SCRUB)
            }
        }
    }
}

private const val SCRUB = "scrub"

/** The sample nearest [x] when [count] samples span [0, right]. */
private fun sampleAt(x: Float, right: Float, count: Int): Int = (x / right * (count - 1)).roundToInt().coerceIn(0, count - 1)

/**
 * The detail chart's hairlines in [color] over its [plot] height (measured §10.1): a row every [RvChartSize.gridRow] and a
 * column every [RvChartSize.gridColumn], the columns fading in and out at the plot's top and bottom, the rows at the
 * screen's edges.
 */
private fun DrawScope.hairlineGrid(color: Color, plot: Float) {
    val hairline = 1f
    val fade = listOf(Color.Transparent, color, color, Color.Transparent)
    val column = RvChartSize.gridColumn.toPx()
    var x = column
    while (x < size.width) {
        drawLine(Brush.verticalGradient(fade, 0f, plot), Offset(x, 0f), Offset(x, plot), hairline)
        x += column
    }
    var y = hairline / 2
    while (y < plot) {
        drawLine(Brush.horizontalGradient(fade), Offset(0f, y), Offset(size.width, y), hairline)
        y += RvChartSize.gridRow.toPx()
    }
}

/** The scrubbed second: a 1 dp line down the [plot] in the series' [color], and a dot in it on the curve, ringed in [ring]. */
private fun DrawScope.scrubMarker(at: Offset, color: Color, ring: Color, plot: Float) {
    drawLine(color, Offset(at.x, 0f), Offset(at.x, plot), RvChartSize.scrubLine.toPx())
    val dot = RvChartSize.scrubDot.toPx() / 2
    drawCircle(ring, dot + RvChartSize.scrubRing.toPx(), at)
    drawCircle(color, dot, at)
}

/**
 * [values] spread over [0, right], joined by straight segments: at one sample a second the line stays dense and jagged,
 * as Revolut's are, and a spike keeps its sharp tip. A missing sample (NaN) breaks the line; nothing bridges the gap.
 */
private fun polyline(values: List<Float>, scale: Scale, right: Float): Path {
    val step = right / (values.size - 1)
    return Path().apply {
        var drawing = false
        values.forEachIndexed { i, v ->
            when {
                v.isNaN() -> drawing = false

                drawing -> lineTo(i * step, scale.y(v))

                else -> {
                    moveTo(i * step, scale.y(v))
                    drawing = true
                    // A sample alone between two gaps draws as a dot under the round cap, not as nothing.
                    if (values.getOrNull(i + 1)?.isNaN() != false) lineTo(i * step, scale.y(v))
                }
            }
        }
    }
}

/** The area under each unbroken run of [values] over [0, right], down to [bottom]. */
private fun areaUnder(values: List<Float>, scale: Scale, right: Float, bottom: Float) = Path().apply {
    val step = right / (values.size - 1)
    var start = -1
    for (i in 0..values.size) {
        val known = i < values.size && !values[i].isNaN()
        if (known && start < 0) start = i
        if (!known && start >= 0) {
            moveTo(start * step, bottom)
            for (j in start until i) lineTo(j * step, scale.y(values[j]))
            lineTo((i - 1) * step, bottom)
            close()
            start = -1
        }
    }
}

private fun lineStroke(width: Float) = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round)

/** Round dots, as Revolut draws its reference lines. */
private fun DrawScope.dots() = PathEffect.dashPathEffect(floatArrayOf(DOT_LENGTH, RvChartSize.dottedPitch.toPx()))

private fun DrawScope.dottedAcross(color: Color, y: Float, alpha: Float = 1f) =
    drawLine(color, Offset(0f, y), Offset(size.width, y), RvChartSize.dotted.toPx(), StrokeCap.Round, dots(), alpha)

/** Ghost bar heights as shares of the chart's height: download's, and upload's of its own shape (S5-09). */
internal val RV_GHOST_DOWN = floatArrayOf(0.42f, 0.64f, 0.5f, 0.8f, 0.58f, 0.36f, 0.7f, 0.9f, 0.54f, 0.68f, 0.46f, 0.76f)
internal val RV_GHOST_UP = floatArrayOf(0.3f, 0.22f, 0.48f, 0.34f, 0.6f, 0.4f, 0.26f, 0.52f, 0.72f, 0.44f, 0.58f, 0.38f)

/** A dash this short with round caps draws as a dot. */
private const val DOT_LENGTH = 0.01f

/** A latency chart's axis (node detail and 統計): up to the first whole 100 ms that leaves the slowest 5 % below the top. */
internal fun rvLatencyAxisTop(slowest: Int): Int = ceil(maxOf(slowest, 1) * LATENCY_HEADROOM / LATENCY_STEP).toInt() * LATENCY_STEP

/**
 * The axis for a chart of one test, which then sits on its middle line: twice [value], so the middle label is the test's
 * own figure (92 → 184 ms; 20 → 40 ms). A test that timed out ([value] 0) keeps [rvLatencyAxisTop]'s.
 */
internal fun rvLatencyAxisMiddle(value: Int): Int = if (value > 0) 2 * value else rvLatencyAxisTop(value)

private const val LATENCY_HEADROOM = 1.05f
private const val LATENCY_STEP = 100

/**
 * The picked point of a latency chart, as Revolut's EPS chart marks its picked year (§10.2): a column of [fill] behind
 * the dot at [x], one [pitch] wide but no narrower than twice its [dot] and no wider than [RvChartSize.markerColumn],
 * from the top to [RvChartSize.markerBelow] under [plotBottom]; and [label] in a chip of the same fill, the value pill's
 * height and padding, [RvChartSize.markerGap] under the plot, kept inside the chart. Returns the chip's start and end.
 */
internal fun DrawScope.rvChartMarker(x: Float, pitch: Float, dot: Float, plotBottom: Float, label: TextLayoutResult, fill: Color): ClosedFloatingPointRange<Float> {
    val column = pitch.coerceIn(2 * dot, RvChartSize.markerColumn.toPx())
    drawRoundRect(fill, Offset(x - column / 2, 0f), Size(column, plotBottom + RvChartSize.markerBelow.toPx()), CornerRadius(column / 2))
    val padH = RvChartSize.pillPadH.toPx()
    val chip = Size(label.size.width + 2 * padH, rvMarkerChip(label.size.height.toFloat()))
    val left = (x - chip.width / 2).coerceAtMost(size.width - chip.width).coerceAtLeast(0f)
    val top = plotBottom + RvChartSize.markerGap.toPx()
    drawRoundRect(fill, Offset(left, top), chip, CornerRadius(chip.height / 2))
    drawText(label, topLeft = Offset(left + padH, top + (chip.height - label.size.height) / 2))
    return left..(left + chip.width)
}

/** The marker chip's height round a label [text] px tall: the value pill's, or the label's at a large font scale. */
internal fun Density.rvMarkerChip(text: Float): Float = maxOf(RvChartSize.pill.toPx(), text)
