package dev.doona.android.designsystem.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.style.TextAlign
import dev.doona.android.designsystem.theme.DoonaChart
import dev.doona.android.designsystem.theme.DoonaTheme
import dev.doona.android.designsystem.theme.DoonaUsage

/**
 * A sparkline, as Revolut's crypto tiles draw one (measured §10.2): [values] as a 2 dp line in [color] from zero to
 * 10 % above their peak, over a neutral grey fill that fades to nothing at the bottom, and a light grey dotted line at
 * the opening value, the window's first. No axes and no labels; 60 dp high and as wide as its caller lets it.
 */
@Composable
fun DoonaSparkline(values: List<Float>, color: Color, modifier: Modifier = Modifier) {
    val colors = DoonaTheme.colors
    val chart = DoonaTheme.chart
    Canvas(modifier.layoutRole(LayoutRole.Chart).fillMaxWidth().height(chart.spark)) {
        if (values.size < 2) return@Canvas
        val stroke = chart.sparkStroke.toPx()
        val scale = ValueScale(values.max() * DoonaChart.SPARK_HEADROOM, 0f, size.height - stroke / 2)
        val line = polyline(values, scale, size.width)
        drawPath(areaUnder(values, scale, size.width, size.height), Brush.verticalGradient(listOf(colors.tileFill, Color.Transparent), scale.y(values.max()), size.height))
        dottedAcross(colors.reference, scale.y(values.first()))
        drawPath(line, color, style = lineStroke(stroke))
    }
}

/**
 * Revolut's empty 'Income' card, where a sparkline would be with nothing to draw: twelve faint bars of made-up [heights]
 * (shares of the height: [ghostDownload] or [ghostUpload]) across the width, each fading out downward.
 */
@Composable
fun DoonaGhostBars(modifier: Modifier = Modifier, heights: FloatArray = ghostDownload) {
    val ghost = DoonaTheme.colors.ghost
    val chart = DoonaTheme.chart
    Canvas(modifier.layoutRole(LayoutRole.Chart).fillMaxWidth().height(chart.spark)) {
        val bar = chart.ghostBar.toPx()
        val pitch = (size.width - bar) / (DoonaChart.GHOST_BARS - 1)
        heights.forEachIndexed { i, height ->
            val top = size.height * (1 - height)
            val brush = Brush.verticalGradient(listOf(ghost, Color.Transparent), top, size.height)
            drawRoundRect(brush, Offset(i * pitch, top), Size(bar, size.height - top), CornerRadius(bar / 2))
        }
    }
}

/** An empty chart (Addendum A): the grid alone with a short [line] on it; never bars that were not measured. */
@Composable
fun DoonaEmptyChart(line: String, modifier: Modifier = Modifier) {
    val colors = DoonaTheme.colors
    Box(modifier.layoutRole(LayoutRole.Chart).fillMaxWidth(), contentAlignment = Alignment.Center) {
        Canvas(Modifier.matchParentSize()) { dayGrid(colors.grid) }
        LineText(line, DoonaTheme.type.tileCaption, color = colors.textSecondary, align = TextAlign.Center)
    }
}

/** Hairlines in [color]: a column every few hours of the day, edges included, and [DoonaGhostBarsDefaults.GRID_ROWS] rows. */
private fun DrawScope.dayGrid(color: Color) {
    val hairline = 1f
    for (hour in 0..DoonaUsage.HOURS step DoonaUsage.GRID_HOURS) {
        val x = (size.width * hour / DoonaUsage.HOURS).coerceIn(hairline / 2, size.width - hairline / 2)
        drawLine(color, Offset(x, 0f), Offset(x, size.height), hairline)
    }
    for (j in 0 until DoonaGhostBarsDefaults.GRID_ROWS) {
        val y = size.height * j / DoonaGhostBarsDefaults.GRID_ROWS + hairline / 2
        drawLine(color, Offset(0f, y), Offset(size.width, y), hairline)
    }
}

/** Ghost bar heights as shares of the chart's height: download's, and upload's of its own shape (S5-09). */
val ghostDownload = floatArrayOf(0.42f, 0.64f, 0.5f, 0.8f, 0.58f, 0.36f, 0.7f, 0.9f, 0.54f, 0.68f, 0.46f, 0.76f)

/** The empty chart's grid rows. */
internal object DoonaGhostBarsDefaults {
    const val GRID_ROWS = 6
}
