package dev.doona.android.prototype.rv

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer

/** Where the plot sits inside the chart's canvas, in pixels. */
private class Plot(val width: Float, val top: Float, val bottom: Float, val max: Float) {
    fun y(value: Float) = bottom - (bottom - top) * (value / max).coerceIn(0f, 1f)
}

/**
 * Two series over the same time span on one value axis: grid lines at [max], half of it and zero, labelled at the end
 * edge; [start] and [end] under the plot name its time span; the newest value of each series is marked with a dot.
 * [down] is drawn over [up] and gets the area fill, as the series the header shows.
 */
@Composable
internal fun RvTrafficChart(down: List<Float>, up: List<Float>, max: Float, axis: (Float) -> String, start: String, end: String, modifier: Modifier = Modifier) {
    val colors = Rv.colors
    val axisStyle = Rv.type.axis.copy(color = colors.textSecondary)
    val measurer = rememberTextMeasurer()
    val labelLine = with(LocalDensity.current) { axisStyle.lineHeight.toDp() }
    Canvas(modifier.fillMaxWidth().height(RvSize.chartHeight + RvSpace.axisGap + labelLine)) {
        val values = listOf(max, max / 2, 0f).map { measurer.measure(axis(it), axisStyle) }
        val half = values.first().size.height / 2f
        val gap = RvSpace.axisGap.toPx()
        val plot = Plot(size.width - values.maxOf { it.size.width } - gap, half, RvSize.chartHeight.toPx() - half, max)
        values.forEachIndexed { i, label ->
            val y = plot.top + (plot.bottom - plot.top) * i / (values.size - 1)
            drawLine(colors.grid.copy(alpha = RvAlpha.GRID), Offset(0f, y), Offset(plot.width, y), RvSize.gridStroke.toPx())
            drawText(label, topLeft = Offset(size.width - label.size.width, y - label.size.height / 2f))
        }
        val bottomY = RvSize.chartHeight.toPx() + gap
        val first = measurer.measure(start, axisStyle)
        val last = measurer.measure(end, axisStyle)
        drawText(first, topLeft = Offset(0f, bottomY))
        drawText(last, topLeft = Offset(plot.width - last.size.width, bottomY))
        series(up, colors.seriesUp, plot, colors.card, fill = false)
        series(down, colors.seriesDown, plot, colors.card, fill = true)
    }
}

private fun DrawScope.series(values: List<Float>, color: Color, plot: Plot, ring: Color, fill: Boolean) {
    if (values.size < 2) return
    val step = plot.width / (values.size - 1)
    val line = Path().apply {
        moveTo(0f, plot.y(values[0]))
        for (i in 1 until values.size) {
            val x0 = (i - 1) * step
            val x1 = i * step
            val mid = (x0 + x1) / 2
            cubicTo(mid, plot.y(values[i - 1]), mid, plot.y(values[i]), x1, plot.y(values[i]))
        }
    }
    if (fill) {
        val area = Path().apply {
            addPath(line)
            lineTo(plot.width, plot.bottom)
            lineTo(0f, plot.bottom)
            close()
        }
        drawPath(area, Brush.verticalGradient(listOf(color.copy(alpha = RvAlpha.FILL), color.copy(alpha = 0f)), plot.top, plot.bottom))
    }
    drawPath(line, color, style = Stroke(RvSize.chartStroke.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    val now = Offset(plot.width, plot.y(values.last()))
    drawCircle(ring, RvSize.dot.toPx() / 2 + RvSize.dotRing.toPx(), now)
    drawCircle(color, RvSize.dot.toPx() / 2, now)
}
