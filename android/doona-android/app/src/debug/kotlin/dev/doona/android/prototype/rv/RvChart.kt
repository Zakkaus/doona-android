package dev.doona.android.prototype.rv

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.withStyle
import dev.doona.android.prototype.rv.data.RvAmount
import dev.doona.android.prototype.rv.data.RvFormat
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
 * One of Revolut's crypto home tiles, square as they are: [caption] at the top start, [icon] in the series' tinted 20 dp
 * disc at the top end, the [figure] in bold, a secondary [note] under it, and the sparkline of [values] at the bottom
 * (measured §10.2: 148 x 60 dp). With [quiet] (no
 * connection, or no traffic) the tile takes Revolut's empty chart card instead: a larger figure over ghost bars, and
 * [note] says why there is no line. The whole tile is one button.
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
) {
    val type = Rv.type
    RvCard(modifier.aspectRatio(1f).rvPress(RoundedCornerShape(RvSize.cardRadius), Role.Button, onClick)) {
        Column(Modifier.fillMaxSize().padding(RvChartSize.tilePad)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // One tile layout in every state and on every page: the in-card header's 19 sp, the value at the tile's size (L-03).
                RvText(caption, type.cardTitle, Modifier.weight(1f), color = Rv.colors.textSecondary)
                Box(Modifier.size(RvChartSize.tileDisc).clip(CircleShape).background(series.disc), contentAlignment = Alignment.Center) {
                    RvGlyph(icon, RvChartSize.tileGlyph, series.line)
                }
            }
            Spacer(Modifier.height(RvSpace.tight))
            RvFigureText(figure, type.tileValue, type.tileUnit)
            RvText(note, type.tileCaption, color = Rv.colors.textSecondary)
            Spacer(Modifier.weight(1f))
            if (quiet) RvGhostBars(ghost) else RvSparkline(values, series.line)
        }
    }
}

/** A figure in [style], its unit in [unitStyle] on the same baseline; Latin only, as figures and units are. */
@Composable
internal fun RvFigureText(figure: RvFigure, style: TextStyle, unitStyle: TextStyle, modifier: Modifier = Modifier) {
    val text = buildAnnotatedString {
        append(figure.whole)
        withStyle(unitStyle.toSpanStyle()) { append(figure.unit) }
    }
    BasicText(text, modifier, style = style.copy(color = Rv.colors.text), maxLines = 1)
}

/**
 * A tile's sparkline, as Revolut's crypto tiles draw one: [values] as a 2 dp line in [color] from zero to 10 % above
 * their peak, over a neutral grey fill that fades to nothing at the bottom, and a light grey dotted line at the opening
 * value, the window's first. No axes and no labels.
 */
@Composable
internal fun RvSparkline(values: List<Float>, color: Color, modifier: Modifier = Modifier) {
    val colors = Rv.colors
    Canvas(modifier.fillMaxWidth().height(RvChartSize.spark)) {
        if (values.size < 2) return@Canvas
        val stroke = RvChartSize.sparkStroke.toPx()
        val scale = Scale(values.max() * RvChartSize.SPARK_HEADROOM, 0f, size.height - stroke / 2)
        val line = polyline(values, scale, size.width)
        drawPath(areaUnder(values, scale, size.width, size.height), Brush.verticalGradient(listOf(colors.tileFill, Color.Transparent), scale.y(values.max()), size.height))
        dottedAcross(colors.reference, scale.y(values.first()))
        drawPath(line, color, style = lineStroke(stroke))
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

/**
 * Revolut's stock chart (measured §10.1), full-bleed: [values] as a line in [series]'s colour on an axis from zero to
 * peak × 1.15, over a tint that fades out linearly to the plot's bottom, on a hairline grid that fades at its ends. A
 * dotted line marks the start value. Opaque pills carry the peak (right), the low (right) and the start (left); each sits
 * on its value where the line leaves room, else just above or under it, and the strip under the plot keeps room for a
 * pill under the bottom. The live point at the end carries a soft halo. A missing sample (NaN) breaks the line. A finger
 * on the chart moves [scrub] to the second under it, shows a line in the series' colour and a dot there and hides the
 * pills; lifting it calls [onScrub] with null.
 */
@Composable
internal fun RvDetailChart(values: List<Float>, series: RvSeries, label: (Float) -> String, scrub: Int?, onScrub: (Int?) -> Unit, modifier: Modifier = Modifier) {
    val colors = Rv.colors
    val pillStyle = Rv.type.pill.copy(color = colors.textSecondary)
    val ink = RvPillInk(colors.pill, colors.raisedShadow)
    val measurer = rememberTextMeasurer()
    Canvas(modifier.fillMaxWidth().height(RvChartSize.detail + RvChartSize.pill + RvChartSize.pillClear).scrubbing(values.size, onScrub)) {
        val plot = RvChartSize.detail.toPx()
        hairlineGrid(colors.grid, plot)
        val known = values.filterNot { it.isNaN() }
        if (known.size < 2) return@Canvas
        val stroke = RvChartSize.detailStroke.toPx()
        val peak = known.max()
        val scale = Scale(peak * RvChartSize.DETAIL_HEADROOM, 0f, plot - stroke)
        val right = size.width - RvChartSize.detailEnd.toPx()
        val line = polyline(values, scale, right)
        drawPath(areaUnder(values, scale, right, plot), Brush.verticalGradient(listOf(series.area, Color.Transparent), scale.y(peak), plot))
        dottedAcross(colors.detailReference, scale.y(known.first()))
        drawPath(line, series.line, style = lineStroke(stroke))
        val live = values.lastOrNull()?.takeUnless { it.isNaN() }?.let { Offset(right, scale.y(it)) }
        live?.let {
            drawCircle(series.line.copy(alpha = RvAlpha.HALO), RvChartSize.liveHalo.toPx() / 2, it)
            drawCircle(series.line, RvChartSize.liveDot.toPx() / 2, it)
        }
        val i = scrub?.takeIf { it in values.indices && !values[it].isNaN() }
        if (i != null) {
            scrubMarker(Offset(right * i / (values.size - 1), scale.y(values[i])), series.line, colors.page, plot)
        } else {
            val pills = PillPlacer(this, values, scale, right, live)
            pills.place(measurer.measure(label(peak), pillStyle), scale.y(peak), end = true, ink)
            pills.place(measurer.measure(label(known.min()), pillStyle), scale.y(known.min()), end = true, ink)
            pills.place(measurer.measure(label(known.first()), pillStyle), scale.y(known.first()), end = false, ink)
        }
    }
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

    fun place(text: TextLayoutResult, y: Float, end: Boolean, ink: RvPillInk) = with(scope) {
        val pill = rvPillSize(text)
        val inset = RvChartSize.pillInset.toPx()
        val left = if (end) size.width - inset - pill.width else inset
        val tops = listOf(y - pill.height / 2, y - clear - pill.height, y + clear).map { it.coerceIn(0f, size.height - pill.height) }
        val top = tops.firstOrNull { free(Rect(Offset(left, it), pill)) } ?: tops.last()
        val rect = Rect(Offset(left, top), pill)
        taken += rect
        rvValuePill(text, rect.topLeft, ink)
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
            }
        }
    }
}

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

private fun DrawScope.dottedAcross(color: Color, y: Float) =
    drawLine(color, Offset(0f, y), Offset(size.width, y), RvChartSize.dotted.toPx(), StrokeCap.Round, dots())

/** Ghost bar heights as shares of the chart's height: download's, and upload's of its own shape (S5-09). */
internal val RV_GHOST_DOWN = floatArrayOf(0.42f, 0.64f, 0.5f, 0.8f, 0.58f, 0.36f, 0.7f, 0.9f, 0.54f, 0.68f, 0.46f, 0.76f)
internal val RV_GHOST_UP = floatArrayOf(0.3f, 0.22f, 0.48f, 0.34f, 0.6f, 0.4f, 0.26f, 0.52f, 0.72f, 0.44f, 0.58f, 0.38f)

/** A dash this short with round caps draws as a dot. */
private const val DOT_LENGTH = 0.01f
