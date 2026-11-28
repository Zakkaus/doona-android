package dev.doona.android.designsystem.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import dev.doona.android.designsystem.theme.DoonaTheme
import dev.doona.android.designsystem.theme.DoonaUsage

/**
 * A day's traffic in the style of Revolut's 'Spent this month' (measured §10.2, study §5; 活動's 今日用量 and 統計's 已用流量,
 * N-11): the day from 00:00 to 24:00 across the canvas and the total × 1.25 up it; the running total of [down] (bytes
 * per hour from 00:00) as a stepped band, [up]'s stacked on it, both at one alpha in their own hue, each with a thin line
 * on its top edge, over a hairline grid every 4 hours that the steps share. The steps stop at [nowHour] (10.4 for 10:24),
 * where a 5 dp dot on a halo marks the total so far (S5-14). The caller sizes it.
 */
@Composable
fun DoonaUsageChart(down: List<Long>, up: List<Long>, nowHour: Float, modifier: Modifier = Modifier) {
    val colors = DoonaTheme.colors
    val chart = DoonaTheme.chart
    val halo = colors.halo(colors.up.line)
    Canvas(modifier.layoutRole(LayoutRole.Chart)) {
        if (down.isEmpty() || up.size != down.size) return@Canvas
        // A day with no bytes yet keeps a top of one byte, so every step lies on the bottom rather than at 0 / 0.
        val high = ((down.sum() + up.sum()) * DoonaUsage.HEADROOM).coerceAtLeast(1f)
        val steps = StepChart(size.width, size.height, high, nowHour)
        hourGrid(colors.grid, steps)
        val downTop = steps.steps(down.runningSum())
        val totalTop = steps.steps(down.zip(up) { d, u -> d + u }.runningSum())
        drawPath(steps.band(totalTop, downTop), colors.up.band)
        drawPath(steps.band(downTop, null), colors.down.band)
        // Butt ends, so the lines stop where the bands do (S5-15).
        val stroke = Stroke(DoonaUsage.stroke.toPx(), cap = StrokeCap.Butt, join = StrokeJoin.Round)
        drawPath(steps.line(downTop), colors.down.line, style = stroke)
        drawPath(steps.line(totalTop), colors.up.line, style = stroke)
        val now = totalTop.last()
        drawCircle(halo, chart.liveHalo.toPx() / 2, now)
        drawCircle(colors.up.line, chart.liveDot.toPx() / 2, now)
    }
}

/** Where an hour and a running total sit on a [width] x [height] canvas whose top is [high] bytes. */
private class StepChart(val width: Float, val height: Float, val high: Float, val now: Float) {
    fun x(hour: Float) = width * hour / DoonaUsage.HOURS
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

/** A hairline at every 4 hours, where [chart] puts that hour, each fading out toward the bottom; and 4 rows across the width. */
private fun DrawScope.hourGrid(color: Color, chart: StepChart) {
    val hairline = 1f
    for (hour in 0..DoonaUsage.HOURS step DoonaUsage.GRID_HOURS) {
        val x = chart.x(hour.toFloat()).coerceIn(hairline / 2, size.width - hairline / 2)
        drawLine(Brush.verticalGradient(listOf(color, Color.Transparent)), Offset(x, 0f), Offset(x, size.height), hairline)
    }
    for (j in 0 until DoonaUsage.GRID_ROWS) {
        val y = size.height * j / DoonaUsage.GRID_ROWS + hairline / 2
        drawLine(color, Offset(0f, y), Offset(size.width, y), hairline)
    }
}

private fun List<Long>.runningSum(): List<Long> = runningReduce { sum, bytes -> sum + bytes }

/** The usage chart's legend: download and upload, each with its line's colour and the caller's text (`下載 1.2 GB`). */
@Composable
fun DoonaUsageLegend(downText: String, upText: String, modifier: Modifier = Modifier) {
    val colors = DoonaTheme.colors
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(DoonaUsage.legendItemGap)) {
        LegendItem(colors.down.line, downText)
        LegendItem(colors.up.line, upText)
    }
}

@Composable
private fun LegendItem(color: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(DoonaUsage.legendSquare).clip(DoonaTheme.shape.legend).background(color))
        Spacer(Modifier.width(DoonaUsage.legendGap))
        LineText(text, DoonaTheme.type.tileCaption, color = DoonaTheme.colors.textSecondary)
    }
}

/**
 * A thin pill bar: [fraction] of it in the accent on the raised track, held to 0 to 1 (a subscription's use of its
 * quota, a goal). A screen reader hears it as a progress in that range; the caller says what it measures next to it.
 */
@Composable
fun DoonaProgressBar(fraction: Float, modifier: Modifier = Modifier) {
    val shown = fraction.coerceIn(0f, 1f)
    Box(
        modifier
            .layoutRole(LayoutRole.Bar)
            .fillMaxWidth()
            .height(DoonaTheme.size.progress)
            .clip(CircleShape)
            .background(DoonaTheme.colors.track)
            .semantics { progressBarRangeInfo = ProgressBarRangeInfo(shown, 0f..1f) },
    ) {
        Box(Modifier.fillMaxWidth(shown).fillMaxHeight().clip(CircleShape).background(DoonaTheme.colors.positive))
    }
}
