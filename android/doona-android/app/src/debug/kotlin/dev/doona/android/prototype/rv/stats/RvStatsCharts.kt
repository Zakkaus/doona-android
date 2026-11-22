package dev.doona.android.prototype.rv.stats

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import dev.doona.android.designsystem.components.latencyTone
import dev.doona.android.prototype.rv.Rv
import dev.doona.android.prototype.rv.RvChartSize

/**
 * Revolut's 'Spent' chart: the day's running total as a grey stepped line over a grey tint, on a hairline grid that fades
 * out toward the left and the bottom. [hours] are the day's bytes per hour from midnight; the last one runs to [now],
 * in hours from midnight. With no bytes the grid stands alone.
 */
@Composable
internal fun RvSteppedArea(hours: List<Long>, now: Float, modifier: Modifier = Modifier) {
    val colors = Rv.colors
    val line = colors.textSecondary.copy(alpha = colors.textSecondary.alpha * RvStatsAlpha.STEP_LINE)
    Canvas(modifier) {
        fadingGrid(colors.grid)
        val total = hours.sum()
        if (total == 0L) return@Canvas
        val stroke = RvStatsSize.stepStroke.toPx()
        val bottom = size.height - stroke / 2
        val y = { bytes: Long -> bottom - (bottom - stroke) * bytes / (total * STEP_HEADROOM) }
        val x = { hour: Float -> size.width * hour / HOURS }
        val path = Path().apply {
            moveTo(0f, bottom)
            var sum = 0L
            hours.forEachIndexed { h, bytes ->
                lineTo(x(if (h == hours.lastIndex) now else h + 1f), y(sum))
                sum += bytes
                lineTo(x(if (h == hours.lastIndex) now else h + 1f), y(sum))
            }
        }
        val area = Path().apply {
            addPath(path)
            lineTo(x(now), size.height)
            close()
        }
        drawPath(area, Brush.verticalGradient(listOf(colors.tileFill, Color.Transparent), y(total), size.height))
        drawPath(path, line, style = Stroke(stroke, cap = StrokeCap.Round))
    }
}

/** Hairlines in [color], [RvStatsSize.GRID_COLUMNS] by [RvStatsSize.GRID_ROWS] cells, gone at the left and the bottom. */
private fun DrawScope.fadingGrid(color: Color) {
    val hairline = 1f
    val fadeIn = size.width * GRID_FADE
    for (i in 1..RvStatsSize.GRID_COLUMNS) {
        val x = size.width * i / RvStatsSize.GRID_COLUMNS - hairline / 2
        val strength = (x / fadeIn).coerceIn(0f, 1f)
        drawLine(Brush.verticalGradient(listOf(color.copy(alpha = color.alpha * strength), Color.Transparent)), Offset(x, 0f), Offset(x, size.height), hairline)
    }
    for (j in 0 until RvStatsSize.GRID_ROWS) {
        val y = size.height * j / RvStatsSize.GRID_ROWS + hairline / 2
        val faded = color.copy(alpha = color.alpha * (1f - y / size.height))
        drawLine(Brush.horizontalGradient(0f to Color.Transparent, GRID_FADE to faded, 1f to faded), Offset(0f, y), Offset(size.width, y), hairline)
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

/** One subscription in the quota ring: its colour, what it used and what it has. */
internal class RvRingSegment(val color: Color, val used: Long, val total: Long)

/**
 * Revolut's 'Total assets' ring: an 8 dp ring with butt ends and a segment per subscription, each as long as its share of all the
 * quota; the used part is solid and the rest a tint of the same colour. With nothing to show it is a full ring on the
 * track, as Revolut draws a single category.
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
        val radius = box.width / 2
        val gap = Math.toDegrees(RvStatsSize.ringGap.toPx() / radius.toDouble()).toFloat()
        var start = START_ANGLE
        segments.forEach { s ->
            val sweep = FULL_TURN * s.total / sum
            val usable = (sweep - gap).coerceAtLeast(0f)
            val used = usable * (s.used.toFloat() / s.total).coerceIn(0f, 1f)
            val begin = start + gap / 2
            drawArc(s.color.copy(alpha = RvStatsAlpha.RING_REST), begin, usable, false, inset, box, style = Stroke(stroke))
            drawArc(s.color, begin, used, false, inset, box, style = Stroke(stroke))
            start += sweep
        }
    }
}

/**
 * Revolut's EPS chart turned to latency: a dot per tested node in its quality's hue, sorted from the fastest, on
 * hairlines every 100 ms labelled at the right, over a dotted zero line. The current node ([selected], an index into
 * [values]) stands in a column of the primary colour's pale tint, with [label] in a chip of the same tint under it, as
 * Revolut marks the selected year.
 */
@Composable
internal fun RvLatencyDots(values: List<Int>, selected: Int?, label: String, modifier: Modifier = Modifier) {
    val colors = Rv.colors
    val measurer = rememberTextMeasurer()
    val axisStyle = Rv.type.pill.copy(color = colors.textSecondary)
    // A node's name keeps its own figures: Inter's tabular figures widen the hyphen of `hk-01`.
    val chipStyle = Rv.type.pill.copy(color = colors.down.line, fontWeight = FontWeight.SemiBold, fontFeatureSettings = null)
    val top = (((values.maxOrNull() ?: 0) + STEP_MS - 1) / STEP_MS).coerceAtLeast(1) * STEP_MS
    val ticks = (0..top step STEP_MS).map { it to "$it" }
    Canvas(modifier.fillMaxWidth().height(RvStatsSize.latency)) {
        val axis = ticks.map { (ms, text) -> ms to measurer.measure(text, axisStyle) }
        val chip = measurer.measure(label, chipStyle)
        val chipH = chip.size.height + 2 * RvStatsSize.labelChipPadV.toPx()
        val plot = Plot(
            right = size.width - axis.maxOf { it.second.size.width } - RvStatsSize.axisLabelGap.toPx(),
            top = axis.last().second.size.height / 2f,
            bottom = size.height - chipH - RvStatsSize.labelChipGap.toPx(),
            topMs = top,
        )
        axis.forEach { (ms, text) -> axisLine(plot, ms, text, colors.grid, colors.reference) }
        if (values.isEmpty()) return@Canvas
        val pitch = plot.right / values.size
        selected?.let { i ->
            val x = pitch * (i + 0.5f)
            val w = RvStatsSize.column.toPx()
            drawRoundRect(colors.down.area, Offset(x - w / 2, 0f), Size(w, plot.bottom + w / 2), CornerRadius(w / 2))
            labelChip(chip, x, chipH, colors.down.area)
        }
        values.forEachIndexed { i, ms ->
            val big = i == selected
            val radius = (if (big) RvStatsSize.latencyDotSelected else RvStatsSize.latencyDot).toPx() / 2
            drawCircle(colors.quality(latencyTone(ms)), radius, Offset(pitch * (i + 0.5f), plot.y(ms)))
        }
    }
}

/** The latency chart's plot: from 0 to [right], [top] to [bottom], 0 ms at the bottom and [topMs] at the top. */
private class Plot(val right: Float, val top: Float, val bottom: Float, val topMs: Int) {
    fun y(ms: Int) = bottom - (bottom - top) * ms.coerceAtMost(topMs) / topMs
}

/** A hairline at [ms] across the plot with its label after it; the zero line is dotted, as Revolut's EPS baseline. */
private fun DrawScope.axisLine(plot: Plot, ms: Int, text: TextLayoutResult, grid: Color, zero: Color) {
    val y = plot.y(ms)
    if (ms == 0) {
        val dots = PathEffect.dashPathEffect(floatArrayOf(DOT_LENGTH, RvChartSize.dottedPitch.toPx()))
        drawLine(zero, Offset(0f, y), Offset(plot.right, y), RvChartSize.dotted.toPx(), StrokeCap.Round, dots)
    } else {
        drawLine(grid, Offset(0f, y), Offset(plot.right, y), 1f)
    }
    drawText(text, topLeft = Offset(size.width - text.size.width, y - text.size.height / 2f))
}

/** [text] in a chip centred under [x] at the bottom of the chart, kept inside it. */
private fun DrawScope.labelChip(text: TextLayoutResult, x: Float, height: Float, fill: Color) {
    val padH = RvStatsSize.labelChipPadH.toPx()
    val width = text.size.width + 2 * padH
    val left = (x - width / 2).coerceIn(0f, size.width - width)
    val top = size.height - height
    drawRoundRect(fill, Offset(left, top), Size(width, height), CornerRadius(height / 2))
    drawText(text, topLeft = Offset(left + padH, top + RvStatsSize.labelChipPadV.toPx()))
}

/** Revolut's analyst bar: an 8 dp pill track with [fraction] of it filled by a gradient of [color], light to full. */
@Composable
internal fun RvRatingBar(fraction: Float, color: Color, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(RvStatsSize.ratingBar).clip(CircleShape).background(Rv.colors.track)) {
        val brush = Brush.horizontalGradient(listOf(color.copy(alpha = color.alpha * RvStatsAlpha.RATING_START), color))
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).fillMaxHeight().clip(CircleShape).background(brush))
    }
}

/** The day's hours on the x axis of the stepped area and the hour bars. */
internal const val HOURS = 24

/** The running total ends this far under the top, so the grid shows above it. */
private const val STEP_HEADROOM = 1.25f

/** The grid's columns and rows reach full strength this far across from the left. */
private const val GRID_FADE = 0.5f
private const val FULL_TURN = 360f
private const val START_ANGLE = -90f
private const val STEP_MS = 100
private const val DOT_LENGTH = 0.01f
