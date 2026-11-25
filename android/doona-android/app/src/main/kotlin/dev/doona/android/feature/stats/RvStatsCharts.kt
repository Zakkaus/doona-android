package dev.doona.android.feature.stats

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import dev.doona.android.designsystem.rv.Rv
import dev.doona.android.designsystem.rv.RvChartSize
import dev.doona.android.designsystem.rv.RvPillInk
import dev.doona.android.designsystem.rv.RvStatsAlpha
import dev.doona.android.designsystem.rv.RvStatsSize
import dev.doona.android.designsystem.rv.RvText
import dev.doona.android.designsystem.rv.RvUsageSize
import dev.doona.android.designsystem.rv.latencyColor
import dev.doona.android.designsystem.rv.rvChartMarker
import dev.doona.android.designsystem.rv.rvLatencyAxisTop
import dev.doona.android.designsystem.rv.rvMarkerChip
import dev.doona.android.designsystem.rv.rvPillSize
import dev.doona.android.designsystem.rv.rvValuePill
import dev.doona.android.designsystem.rv.scripted
import kotlin.math.abs

/** An empty chart (Addendum A): the grid alone with a short [line] on it; never bars that were not measured. */
@Composable
internal fun RvEmptyChart(line: String, modifier: Modifier = Modifier) {
    val colors = Rv.colors
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Canvas(Modifier.matchParentSize()) { hourGrid(colors.grid) }
        RvText(line, Rv.type.tileCaption, color = colors.textSecondary, align = TextAlign.Center)
    }
}

/** Hairlines in [color]: a column every [RvUsageSize.GRID_HOURS] hours of the day, edges included, and [RvStatsSize.GRID_ROWS] rows. */
private fun DrawScope.hourGrid(color: Color) {
    val hairline = 1f
    for (hour in 0..HOURS step RvUsageSize.GRID_HOURS) {
        val x = (size.width * hour / HOURS).coerceIn(hairline / 2, size.width - hairline / 2)
        drawLine(color, Offset(x, 0f), Offset(x, size.height), hairline)
    }
    for (j in 0 until RvStatsSize.GRID_ROWS) {
        val y = size.height * j / RvStatsSize.GRID_ROWS + hairline / 2
        drawLine(color, Offset(0f, y), Offset(size.width, y), hairline)
    }
}

/**
 * The bars of a paged tile, after Revolut's 'Income' card: one per hour of the day in [color], scaled to the busiest;
 * the hours still to come are short stubs on the track.
 */
@Composable
internal fun RvHourBars(values: List<Long>, color: Color, modifier: Modifier = Modifier) {
    val stub = Rv.colors.track
    Canvas(modifier.fillMaxWidth().height(RvStatsSize.bars)) {
        val pitch = size.width / HOURS
        val bar = pitch * RvStatsSize.BAR_SHARE
        val peak = values.maxOrNull()?.takeIf { it > 0 } ?: 1L
        val least = RvStatsSize.barStub.toPx()
        for (h in 0 until HOURS) {
            val value = values.getOrNull(h)
            val height = if (value == null) least else (size.height * value / peak).coerceAtLeast(least)
            val left = h * pitch + (pitch - bar) / 2
            drawRoundRect(if (value == null) stub else color, Offset(left, size.height - height), Size(bar, height), CornerRadius(bar / 2))
        }
    }
}

/** One subscription in the quota ring: its colour, the lighter step of it for what is left, what it used and what it has. */
internal class RvRingSegment(val color: Color, val rest: Color, val used: Long, val total: Long)

/**
 * Revolut's 'Total assets' ring: an 8 dp ring with butt ends and a segment per subscription, each as long as its share of
 * all the quota: the used part in the subscription's colour, then what is left in a lighter step of it, every two parts
 * [RvStatsSize.ringGap] apart in the card's colour (S05). With nothing to show it is a full ring on the track, as Revolut
 * draws a single category.
 */
@Composable
internal fun RvQuotaRing(segments: List<RvRingSegment>, modifier: Modifier = Modifier) {
    val track = Rv.colors.track
    Canvas(modifier.size(RvStatsSize.ring)) {
        val stroke = RvStatsSize.ringStroke.toPx()
        val inset = Offset(stroke / 2, stroke / 2)
        val box = Size(size.width - stroke, size.height - stroke)
        val sum = segments.sumOf { it.total }.toFloat()
        if (sum <= 0f) {
            drawArc(track, 0f, FULL_TURN, false, inset, box, style = Stroke(stroke))
            return@Canvas
        }
        val gap = Math.toDegrees(RvStatsSize.ringGap.toPx() / (box.width / 2).toDouble()).toFloat()
        val arc = { color: Color, from: Float, sweep: Float -> if (sweep > 0f) drawArc(color, from, sweep, false, inset, box, style = Stroke(stroke)) }
        var start = START_ANGLE
        segments.forEach { s ->
            val sweep = FULL_TURN * s.total / sum
            val begin = start + gap / 2
            val usable = (sweep - gap).coerceAtLeast(0f)
            val share = (s.used.toFloat() / s.total).coerceIn(0f, 1f)
            when {
                share >= 1f -> arc(s.color, begin, usable)

                share <= 0f -> arc(s.rest, begin, usable)

                else -> {
                    // The used part and the rest each give up half a gap where they meet.
                    val used = usable * share
                    arc(s.color, begin, used - gap / 2)
                    arc(s.rest, begin + used + gap / 2, usable - used - gap / 2)
                }
            }
            start += sweep
        }
    }
}

/**
 * Revolut's EPS chart turned to latency, in node detail's grammar (S04): a dot per tested node in its quality's hue,
 * sorted from the fastest, on hairlines at the top, the middle and zero, labelled at the right with the unit on the top
 * one, and a dotted line at the [median] with its value in the value pill at the line's end, which hides a label it would
 * cover. The axis leaves the slowest dot 5 % under the top. The current node ([selected], an index into [values]) is
 * marked with the library's [rvChartMarker] in the primary colour's pale tint, [label] in its chip, as node detail marks
 * its picked test. The dots are small enough that neighbours keep a gap at 47 nodes; the selected one is a step larger
 * and still clear of its neighbours.
 */
@Composable
internal fun RvLatencyDots(values: List<Int>, selected: Int?, label: String, median: Int?, unit: String, modifier: Modifier = Modifier) {
    val colors = Rv.colors
    val measurer = rememberTextMeasurer()
    val axisStyle = Rv.type.pill.copy(color = colors.textSecondary)
    val chipStyle = Rv.type.pillName.copy(color = colors.down.line)
    val top = rvLatencyAxisTop(values.maxOrNull() ?: 0)
    val ticks = listOf(top to "$top $unit", top / 2 to "${top / 2}", 0 to "0")
    val pillInk = RvPillInk(colors.pill, colors.raisedShadow)
    val dotColors = values.map { latencyColor(it) }
    Canvas(modifier.fillMaxWidth().height(RvStatsSize.latency)) {
        val axis = ticks.map { (ms, text) -> ms to measurer.measure(scripted(text), axisStyle) }
        val pill = median?.let { measurer.measure(scripted("$it $unit"), axisStyle) }
        val strip = maxOf(axis.maxOf { it.second.size.width }.toFloat(), pill?.let { rvPillSize(it).width } ?: 0f) + RvStatsSize.axisLabelGap.toPx()
        // At a large font scale the label could outgrow the chart: it ends in an ellipsis instead.
        val chipMax = (size.width - 2 * RvChartSize.pillPadH.toPx()).toInt().coerceAtLeast(0)
        val chip = measurer.measure(label, chipStyle, TextOverflow.Ellipsis, maxLines = 1, constraints = Constraints(maxWidth = chipMax))
        val plot = Plot(
            right = size.width - strip,
            top = axis.first().second.size.height / 2f,
            bottom = size.height - rvMarkerChip(chip.size.height.toFloat()) - RvChartSize.markerGap.toPx(),
            topMs = top,
        )
        val medianY = median?.let { plot.y(it) }
        axis.forEach { (ms, text) ->
            val covered = pill != null && medianY != null && abs(medianY - plot.y(ms)) <= (rvPillSize(pill).height + text.size.height) / 2
            axisLine(plot, plot.y(ms), text.takeUnless { covered }, colors.grid)
        }
        if (pill != null && medianY != null) medianLine(plot, medianY, pill, colors.reference, pillInk)
        if (values.isEmpty()) return@Canvas
        val pitch = plot.right / values.size
        val x = { i: Int -> pitch * (i + 0.5f) }
        selected?.let { i -> rvChartMarker(x(i), pitch, RvStatsSize.latencyDotSelected.toPx(), plot.bottom, chip, colors.down.area) }
        values.forEachIndexed { i, ms ->
            if (i != selected) drawCircle(dotColors[i], RvStatsSize.latencyDot.toPx() / 2, Offset(x(i), plot.y(ms)))
        }
        selected?.let { i -> drawCircle(dotColors[i], RvStatsSize.latencyDotSelected.toPx() / 2, Offset(x(i), plot.y(values[i]))) }
    }
}

/** The latency chart's plot: from 0 to [right], [top] to [bottom], 0 ms at the bottom and [topMs] at the top. */
private class Plot(val right: Float, val top: Float, val bottom: Float, val topMs: Int) {
    fun y(ms: Int) = bottom - (bottom - top) * ms.coerceAtMost(topMs) / topMs
}

/** A hairline at [y] across the plot, and its label, if any, at the strip's end. */
private fun DrawScope.axisLine(plot: Plot, y: Float, text: TextLayoutResult?, grid: Color) {
    drawLine(grid, Offset(0f, y), Offset(plot.right, y), 1f)
    text?.let { drawText(it, topLeft = Offset(size.width - it.size.width, y - it.size.height / 2f)) }
}

/** The dotted median across the plot, its value in the value pill at the strip's end, as node detail draws it. */
private fun DrawScope.medianLine(plot: Plot, y: Float, text: TextLayoutResult, color: Color, ink: RvPillInk) {
    val dots = PathEffect.dashPathEffect(floatArrayOf(DOT_LENGTH, RvChartSize.dottedPitch.toPx()))
    drawLine(color, Offset(0f, y), Offset(plot.right, y), RvChartSize.dotted.toPx(), StrokeCap.Round, dots)
    val pill = rvPillSize(text)
    rvValuePill(text, Offset(size.width - pill.width, (y - pill.height / 2).coerceIn(0f, plot.bottom - pill.height)), ink)
}

/** Revolut's analyst bar: an 8 dp pill track with [fraction] of it filled by a gradient of [color], light to full. */
@Composable
internal fun RvRatingBar(fraction: Float, color: Color, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(RvStatsSize.ratingBar).clip(CircleShape).background(Rv.colors.track)) {
        val brush = Brush.horizontalGradient(listOf(color.copy(alpha = color.alpha * RvStatsAlpha.RATING_START), color))
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).fillMaxHeight().clip(CircleShape).background(brush))
    }
}

/** The day's hours on the x axis of the empty grid and the hour bars. */
internal const val HOURS = 24

private const val FULL_TURN = 360f
private const val START_ANGLE = -90f
private const val DOT_LENGTH = 0.01f
