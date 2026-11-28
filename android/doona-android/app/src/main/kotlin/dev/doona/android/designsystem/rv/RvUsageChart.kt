package dev.doona.android.designsystem.rv

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import dev.doona.android.R
import dev.doona.android.feature.model.RvDay

/**
 * The usage chart's legend: download and upload, each with its line's colour and the day's bytes, side by side, the
 * second under the first where they do not fit on one line.
 */
@Composable
internal fun RvUsageLegend(day: RvDay, modifier: Modifier = Modifier) {
    val format = rememberRvFormat()
    val colors = Rv.colors
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(RvUsageSize.legendItemGap)) {
        RvLegendItem(colors.down.line, "${stringResource(R.string.ui_download)}$SEPARATOR${format.bytes(day.downTotal)}")
        RvLegendItem(colors.up.line, "${stringResource(R.string.ui_upload)}$SEPARATOR${format.bytes(day.upTotal)}")
    }
}

/**
 * A chart's legend entry, the one legend style: [color]'s square, then [text] at the chart caption's step in [ink], and,
 * given [end], that text at the row's end, the entry filling its width.
 */
@Composable
internal fun RvLegendItem(color: Color, text: String, modifier: Modifier = Modifier, ink: Color = Rv.colors.textSecondary, end: String? = null) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(RvUsageSize.legendSquare).background(color))
        Spacer(Modifier.width(RvUsageSize.legendGap))
        RvText(text, Rv.type.tileCaption, if (end != null) Modifier.weight(1f) else Modifier, color = ink)
        end?.let { RvText(it, Rv.type.tileCaption, color = Rv.colors.textSecondary, align = TextAlign.End) }
    }
}

/**
 * The day from 00:00 to 24:00 across the canvas and the total × [RvUsageSize.HEADROOM] up it: download's running total as
 * a stepped band, upload's stacked on it, both at one alpha in their own hue, each with a thin line on its top edge, over
 * a hairline grid every 4 hours that the steps share. The steps stop at the current time, where a 5 dp dot on a halo
 * marks the total so far (S5-14). 活動's 今日用量 and 統計's 已用流量 both draw the day with it (N-11). 統計's 本週 and 本月
 * draw days the same way: [day]'s entries are then days, [slots] of them across the canvas, a grid line every
 * [gridEvery], and [nowHour] is in days.
 */
@Composable
internal fun RvUsageChart(day: RvDay, nowHour: Float, modifier: Modifier, slots: Int = RvUsageSize.HOURS, gridEvery: Int = RvUsageSize.GRID_HOURS) {
    val colors = Rv.colors
    Canvas(modifier) {
        // A day with no bytes yet keeps a top of one byte, so every step lies on the bottom rather than at 0 / 0.
        val high = (day.total * RvUsageSize.HEADROOM).coerceAtLeast(1f)
        val chart = StepChart(size.width, size.height, high, nowHour, slots)
        hourGrid(colors.grid, chart, gridEvery)
        val downTop = chart.steps(day.down.runningSum())
        val totalTop = chart.steps(day.down.zip(day.up) { down, up -> down + up }.runningSum())
        drawPath(chart.band(totalTop, downTop), colors.up.band)
        drawPath(chart.band(downTop, null), colors.down.band)
        // Butt ends, so the lines stop where the bands do (S5-15).
        val stroke = Stroke(RvUsageSize.stroke.toPx(), cap = StrokeCap.Butt, join = StrokeJoin.Round)
        drawPath(chart.line(downTop), colors.down.line, style = stroke)
        drawPath(chart.line(totalTop), colors.up.line, style = stroke)
        val now = totalTop.last()
        drawCircle(colors.up.line.copy(alpha = RvAlpha.HALO), RvChartSize.liveHalo.toPx() / 2, now)
        drawCircle(colors.up.line, RvChartSize.liveDot.toPx() / 2, now)
    }
}

/** Where an hour (of [slots]) and a running total sit on a [width] x [height] canvas whose top is [high] bytes. */
private class StepChart(val width: Float, val height: Float, val high: Float, val now: Float, val slots: Int) {
    fun x(hour: Float) = width * hour / slots
    private fun y(bytes: Long) = height * (1 - bytes / high)

    /** The top edge of a running total: level through each hour, rising at the hour's start, ending now. */
    fun steps(totals: List<Long>): List<Offset> = buildList {
        add(Offset(x(0f), y(0)))
        totals.forEachIndexed { hour, total ->
            add(Offset(x(hour.toFloat()), y(total)))
            add(Offset(x(minOf(hour + 1f, now)), y(total)))
        }
    }

    fun line(points: List<Offset>) = Path().apply {
        moveTo(points[0].x, points[0].y)
        points.drop(1).forEach { lineTo(it.x, it.y) }
    }

    /** The area between [top] and [bottom], or down to the canvas's bottom when [bottom] is null. */
    fun band(top: List<Offset>, bottom: List<Offset>?) = line(top).apply {
        if (bottom == null) {
            lineTo(top.last().x, height)
            lineTo(top.first().x, height)
        } else {
            bottom.asReversed().forEach { lineTo(it.x, it.y) }
        }
        close()
    }
}

/**
 * A hairline at every [every] hours (both edges included), where [chart] puts that hour, so the steps rise on the grid,
 * each fading out toward the bottom; and [RvUsageSize.GRID_ROWS] rows across the whole width.
 */
private fun DrawScope.hourGrid(color: Color, chart: StepChart, every: Int) {
    val hairline = 1f
    for (hour in ((0..chart.slots step every) + chart.slots).distinct()) {
        val x = chart.x(hour.toFloat()).coerceIn(hairline / 2, size.width - hairline / 2)
        drawLine(Brush.verticalGradient(listOf(color, Color.Transparent)), Offset(x, 0f), Offset(x, size.height), hairline)
    }
    for (j in 0 until RvUsageSize.GRID_ROWS) {
        val y = size.height * j / RvUsageSize.GRID_ROWS + hairline / 2
        drawLine(color, Offset(0f, y), Offset(size.width, y), hairline)
    }
}

private fun List<Long>.runningSum(): List<Long> = runningReduce { sum, bytes -> sum + bytes }
