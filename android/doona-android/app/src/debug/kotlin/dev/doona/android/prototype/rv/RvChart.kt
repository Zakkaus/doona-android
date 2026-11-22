package dev.doona.android.prototype.rv

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
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
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer

/** Where values sit in a chart's canvas, in pixels: [low] on [bottom], [high] on [top]. */
private class Scale(val low: Float, val high: Float, val top: Float, val bottom: Float) {
    fun y(value: Float) = if (high <= low) bottom else bottom - (bottom - top) * ((value - low) / (high - low)).coerceIn(0f, 1f)
}

/**
 * One of Revolut's home tiles: [caption] at the top start, [icon] in the series' tinted disc at the top end, [value] in
 * bold, a secondary [note] under it, then the sparkline of [values]. The whole tile is one button.
 */
@Composable
internal fun RvSparkTile(
    caption: String,
    @DrawableRes icon: Int,
    value: String,
    note: String,
    values: List<Float>,
    series: RvSeries,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    RvCard(modifier.rvPress(RoundedCornerShape(RvSize.cardRadius), Role.Button, onClick)) {
        Column(Modifier.padding(RvSpace.rowPadding)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RvText(caption, Rv.type.rowSub, Modifier.weight(1f), color = Rv.colors.textSecondary)
                Box(Modifier.size(RvChartSize.tileDisc).clip(CircleShape).background(series.disc), contentAlignment = Alignment.Center) {
                    RvGlyph(icon, RvChartSize.tileGlyph, series.onDisc)
                }
            }
            Spacer(Modifier.height(RvSpace.tight))
            RvText(value, Rv.type.tileValue)
            RvText(note, Rv.type.rowSub, color = Rv.colors.textSecondary)
            Spacer(Modifier.height(RvSpace.sparkGap))
            RvSparkline(values, series.line)
        }
    }
}

/**
 * A tile's sparkline: [values] as a 2 dp line in [color] over a fill that fades to nothing at the bottom, and a dotted
 * grey line at their average. No axes, labels or grid. The line spans the values' own range, so a steady rate still
 * shows its swings; the lowest value sits above the bottom so the fill under it still reads.
 */
@Composable
internal fun RvSparkline(values: List<Float>, color: Color, modifier: Modifier = Modifier) {
    val reference = Rv.colors.reference
    Canvas(modifier.fillMaxWidth().height(RvChartSize.spark)) {
        if (values.size < 2) return@Canvas
        val stroke = RvChartSize.sparkStroke.toPx()
        val scale = Scale(values.min(), values.max(), stroke, size.height * SPARK_FLOOR)
        val line = linePath(values, scale)
        fillUnder(line, color, scale.top)
        dottedAcross(reference, scale.y(values.average().toFloat()))
        drawPath(line, color, style = lineStroke(stroke))
    }
}

/**
 * Revolut's stock chart, full-bleed: [down] and [up] on one scale from zero, [down] over a fill; a dotted line across at
 * [down]'s peak with its value in a pill at the end, its low in a pill under the point, and a faint dotted vertical
 * grid. [label] formats a pill's value.
 */
@Composable
internal fun RvDetailChart(down: List<Float>, up: List<Float>, label: (Float) -> String, modifier: Modifier = Modifier) {
    val colors = Rv.colors
    val pillStyle = Rv.type.pill.copy(color = colors.onPill)
    val measurer = rememberTextMeasurer()
    Canvas(modifier.fillMaxWidth().height(RvChartSize.detail)) {
        if (down.size < 2) return@Canvas
        for (k in 1 until RvChartSize.GRID_PARTS) {
            val x = size.width * k / RvChartSize.GRID_PARTS
            drawLine(colors.grid.copy(alpha = RvAlpha.GRID), Offset(x, 0f), Offset(x, size.height), RvChartSize.dotted.toPx(), StrokeCap.Round, dots())
        }
        val peak = down.max()
        val scale = Scale(0f, maxOf(peak, up.maxOrNull() ?: 0f), RvChartSize.detailTop.toPx(), size.height - RvChartSize.detailBottom.toPx())
        val stroke = RvChartSize.detailStroke.toPx()
        val downLine = linePath(down, scale)
        fillUnder(downLine, colors.down.line, scale.top)
        if (up.size >= 2) drawPath(linePath(up, scale), colors.up.line, style = lineStroke(stroke))
        drawPath(downLine, colors.down.line, style = lineStroke(stroke))
        dottedAcross(colors.reference, scale.y(peak))

        val gutter = RvSpace.gutter.toPx()
        val high = measurer.measure(label(peak), pillStyle)
        val highSize = pillSize(high)
        valuePill(high, highSize, Offset(size.width - gutter - highSize.width, scale.y(peak) - highSize.height / 2), colors.pill)
        val lowAt = down.indexOf(down.min())
        val low = measurer.measure(label(down[lowAt]), pillStyle)
        val lowSize = pillSize(low)
        val lowX = size.width * lowAt / (down.size - 1) - lowSize.width / 2
        val lowTop = scale.y(down[lowAt]) + RvChartSize.pillOffset.toPx()
        valuePill(low, lowSize, Offset(lowX.coerceIn(gutter, size.width - gutter - lowSize.width), lowTop), colors.pill)
    }
}

/** [values] spread across the canvas's width as one smooth line. */
private fun DrawScope.linePath(values: List<Float>, scale: Scale): Path {
    val step = size.width / (values.size - 1)
    return Path().apply {
        moveTo(0f, scale.y(values[0]))
        for (i in 1 until values.size) {
            val mid = (i - 0.5f) * step
            cubicTo(mid, scale.y(values[i - 1]), mid, scale.y(values[i]), i * step, scale.y(values[i]))
        }
    }
}

/** The area under [line] down to the canvas's bottom, from a quarter of [color] at [top] to nothing. */
private fun DrawScope.fillUnder(line: Path, color: Color, top: Float) {
    val area = Path().apply {
        addPath(line)
        lineTo(size.width, size.height)
        lineTo(0f, size.height)
        close()
    }
    drawPath(area, Brush.verticalGradient(listOf(color.copy(alpha = RvAlpha.FILL), color.copy(alpha = 0f)), top, size.height))
}

private fun DrawScope.lineStroke(width: Float) = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round)

/** Round dots, as Revolut draws its reference lines and grid. */
private fun DrawScope.dots() = PathEffect.dashPathEffect(floatArrayOf(DOT_LENGTH, RvChartSize.dottedPitch.toPx()))

private fun DrawScope.dottedAcross(color: Color, y: Float) =
    drawLine(color, Offset(0f, y), Offset(size.width, y), RvChartSize.dotted.toPx(), StrokeCap.Round, dots())

private fun DrawScope.pillSize(text: TextLayoutResult) =
    Size(text.size.width + 2 * RvChartSize.pillPadH.toPx(), text.size.height + 2 * RvChartSize.pillPadV.toPx())

/** A value on the chart: [text] in a small rounded pill at [topLeft]. */
private fun DrawScope.valuePill(text: TextLayoutResult, pill: Size, topLeft: Offset, fill: Color) {
    drawRoundRect(fill, topLeft, pill, CornerRadius(pill.height / 2))
    drawText(text, topLeft = topLeft + Offset(RvChartSize.pillPadH.toPx(), RvChartSize.pillPadV.toPx()))
}

/** The share of a sparkline's height where its lowest value sits. */
private const val SPARK_FLOOR = 0.8f

/** A dash this short with round caps draws as a dot. */
private const val DOT_LENGTH = 0.01f
