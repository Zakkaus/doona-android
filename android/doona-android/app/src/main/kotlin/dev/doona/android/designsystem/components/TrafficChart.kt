package dev.doona.android.designsystem.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.rememberTextMeasurer
import dev.doona.android.designsystem.theme.DoonaChart
import dev.doona.android.designsystem.theme.DoonaSeries
import dev.doona.android.designsystem.theme.DoonaTheme
import kotlin.math.roundToInt

/**
 * Revolut's stock chart turned to traffic (measured §10.1), full-bleed: [values] (one a second, say) as a line in
 * [series]' colour on an axis from zero to peak × 1.15, over a tint that fades out linearly to the plot's bottom, on a
 * hairline grid that fades at its ends. A dotted line marks the start value. Opaque value pills carry the peak (end),
 * the low (end) and the start (start), each text made by [label]; each sits on its value where the line leaves room,
 * else just above or under it, and the strip under the plot keeps room for a pill under the bottom. The live point at
 * the end carries a soft halo. A missing sample (NaN) breaks the line: gaps are never bridged. A finger on the chart
 * reports the sample under it to [onScrub] as it moves, and null when it lifts; the caller passes that index back as
 * [scrub], which draws a line in the series' colour and a dot on the curve there and hides the pills.
 */
@Composable
fun DoonaTrafficChart(
    values: List<Float>,
    series: DoonaSeries,
    label: (Float) -> String,
    scrub: Int?,
    onScrub: (Int?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = DoonaTheme.colors
    val chart = DoonaTheme.chart
    val pillStyle = DoonaTheme.type.pill.copy(color = colors.textSecondary)
    val ink = PillInk(colors.pill, colors.raisedShadow)
    val halo = colors.halo(series.line)
    val measurer = rememberTextMeasurer()
    Canvas(modifier.layoutRole(LayoutRole.Chart).fillMaxWidth().height(chart.detail + chart.pill + chart.pillClear).scrubbing(values.size, onScrub)) {
        val plot = chart.detail.toPx()
        hairlineGrid(colors.grid, plot)
        val known = values.filterNot { it.isNaN() }
        if (known.size < 2) return@Canvas
        val stroke = chart.detailStroke.toPx()
        val peak = known.max()
        val scale = ValueScale(peak * DoonaChart.DETAIL_HEADROOM, 0f, plot - stroke)
        val right = size.width - chart.detailEnd.toPx()
        drawPath(areaUnder(values, scale, right, plot), Brush.verticalGradient(listOf(series.area, Color.Transparent), scale.y(peak), plot))
        dottedAcross(colors.detailReference, scale.y(known.first()))
        drawPath(polyline(values, scale, right), series.line, style = lineStroke(stroke))
        val live = values.lastOrNull()?.takeUnless { it.isNaN() }?.let { Offset(right, scale.y(it)) }
        live?.let {
            drawCircle(halo, chart.liveHalo.toPx() / 2, it)
            drawCircle(series.line, chart.liveDot.toPx() / 2, it)
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
 * Sets value pills on the traffic chart without covering its line, its live point or one another: each tries its
 * value's height, then just above it, then just under it (down into the strip under the plot), and takes the first that
 * is clear.
 */
private class PillPlacer(val scope: DrawScope, val values: List<Float>, val scale: ValueScale, val right: Float, live: Offset?) {
    private val taken = mutableListOf<Rect>()
    private val clear = with(scope) { DoonaTheme.chart.pillClear.toPx() }

    init {
        live?.let { taken += Rect(it, with(scope) { DoonaTheme.chart.liveHalo.toPx() / 2 }) }
    }

    fun place(text: TextLayoutResult, y: Float, end: Boolean, ink: PillInk) = with(scope) {
        val pill = pillSize(text)
        val inset = DoonaTheme.chart.pillInset.toPx()
        val left = if (end) size.width - inset - pill.width else inset
        val tops = listOf(y - pill.height / 2, y - clear - pill.height, y + clear).map { it.coerceIn(0f, size.height - pill.height) }
        val top = tops.firstOrNull { free(Rect(Offset(left, it), pill)) } ?: tops.last()
        val rect = Rect(Offset(left, top), pill)
        taken += rect
        drawValuePill(text, rect.topLeft, ink)
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

/** Press and drag: [onScrub] gets the sample under the finger as it moves, and null when it lifts. */
@Composable
private fun Modifier.scrubbing(count: Int, onScrub: (Int?) -> Unit): Modifier {
    val report by rememberUpdatedState(onScrub)
    if (count < 2) return this
    return pointerInput(count) {
        // Read at every event rather than once: the chart can change width without changing its count.
        fun right() = size.width - DoonaTheme.chart.detailEnd.toPx()
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
internal fun sampleAt(x: Float, right: Float, count: Int): Int = (x / right * (count - 1)).roundToInt().coerceIn(0, count - 1)

/**
 * The chart's hairlines in [color] over its [plot] height (measured §10.1): a row and a column at the theme's grid
 * pitch, the columns fading in and out at the plot's top and bottom, the rows at the screen's edges.
 */
private fun DrawScope.hairlineGrid(color: Color, plot: Float) {
    val hairline = 1f
    val fade = listOf(Color.Transparent, color, color, Color.Transparent)
    val column = DoonaTheme.chart.gridColumn.toPx()
    var x = column
    while (x < size.width) {
        drawLine(Brush.verticalGradient(fade, 0f, plot), Offset(x, 0f), Offset(x, plot), hairline)
        x += column
    }
    var y = hairline / 2
    while (y < plot) {
        drawLine(Brush.horizontalGradient(fade), Offset(0f, y), Offset(size.width, y), hairline)
        y += DoonaTheme.chart.gridRow.toPx()
    }
}

/** The scrubbed sample: a 1 dp line down the [plot] in the series' [color], and a dot in it on the curve, ringed in [ring]. */
private fun DrawScope.scrubMarker(at: Offset, color: Color, ring: Color, plot: Float) {
    val chart = DoonaTheme.chart
    drawLine(color, Offset(at.x, 0f), Offset(at.x, plot), chart.scrubLine.toPx())
    val dot = chart.scrubDot.toPx() / 2
    drawCircle(ring, dot + chart.scrubRing.toPx(), at)
    drawCircle(color, dot, at)
}
