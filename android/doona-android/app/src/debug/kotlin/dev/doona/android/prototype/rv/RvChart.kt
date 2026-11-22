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

/** A figure in [style], its unit in [unitStyle] on the same baseline; Latin only, as figures and units are. */
@Composable
internal fun RvFigureText(figure: RvFigure, style: TextStyle, unitStyle: TextStyle, modifier: Modifier = Modifier) {
    val text = buildAnnotatedString {
        append(figure.whole)
        withStyle(unitStyle.toSpanStyle()) { append(figure.unit) }
    }
    BasicText(text, modifier, style = style.copy(color = Rv.colors.text), maxLines = 1)
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
 * 活動's 即時速度 chart (O3): the last minute's [down] and [up] as two lines in their series' colours, the same teal and
 * slate as 今日用量, on one axis from zero to the higher peak × 1.15 over the detail chart's hairline grid; each live
 * point carries its halo. A finger scrubs as on 流量詳情: [scrub] moves to the second under it, a line in download's
 * colour runs down the chart and each curve gets its dot, ringed in the card's colour; lifting it calls [onScrub] with null.
 */
@Composable
internal fun RvLiveChart(down: List<Float>, up: List<Float>, scrub: Int?, onScrub: (Int?) -> Unit, modifier: Modifier = Modifier) {
    val colors = Rv.colors
    Canvas(modifier.fillMaxWidth().scrubbing(down.size, onScrub)) {
        val plot = size.height
        hairlineGrid(colors.grid, plot)
        val known = (down + up).filterNot { it.isNaN() }
        if (known.isEmpty() || down.size < 2 || up.size != down.size) return@Canvas
        val stroke = RvChartSize.detailStroke.toPx()
        val scale = Scale(known.max() * RvChartSize.DETAIL_HEADROOM, 0f, plot - stroke)
        val right = size.width - RvChartSize.detailEnd.toPx()
        // Download last, so it lies on top where the two cross.
        val lines = listOf(up to colors.up.line, down to colors.down.line)
        for ((values, color) in lines) {
            drawPath(polyline(values, scale, right), color, style = lineStroke(stroke))
            values.last().takeUnless { it.isNaN() }?.let {
                val at = Offset(right, scale.y(it))
                drawCircle(color.copy(alpha = RvAlpha.HALO), RvChartSize.liveHalo.toPx() / 2, at)
                drawCircle(color, RvChartSize.liveDot.toPx() / 2, at)
            }
        }
        val i = scrub?.takeIf { it in down.indices } ?: return@Canvas
        val x = right * i / (down.size - 1)
        drawLine(colors.down.line, Offset(x, 0f), Offset(x, plot), RvChartSize.scrubLine.toPx())
        for ((values, color) in lines) values[i].takeUnless { it.isNaN() }?.let { scrubDot(Offset(x, scale.y(it)), color, colors.card) }
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
    scrubDot(at, color, ring)
}

/** The scrubbed second's dot on a curve: [color], ringed in [ring]. */
private fun DrawScope.scrubDot(at: Offset, color: Color, ring: Color) {
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

/** Ghost bar heights as shares of the chart's height (S5-09). */
internal val RV_GHOST = floatArrayOf(0.42f, 0.64f, 0.5f, 0.8f, 0.58f, 0.36f, 0.7f, 0.9f, 0.54f, 0.68f, 0.46f, 0.76f)

/** A dash this short with round caps draws as a dot. */
private const val DOT_LENGTH = 0.01f

/** A latency chart's axis (node detail and 統計): up to the first whole 100 ms that leaves the slowest 5 % below the top. */
internal fun rvLatencyAxisTop(slowest: Int): Int = ceil(maxOf(slowest, 1) * LATENCY_HEADROOM / LATENCY_STEP).toInt() * LATENCY_STEP

private const val LATENCY_HEADROOM = 1.05f
private const val LATENCY_STEP = 100
