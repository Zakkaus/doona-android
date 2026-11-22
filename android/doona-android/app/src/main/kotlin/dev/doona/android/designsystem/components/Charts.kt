package dev.doona.android.designsystem.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import dev.doona.android.designsystem.theme.DoonaChart
import dev.doona.android.designsystem.theme.DoonaTheme

/**
 * doona's sparkline (§7, Charts; `Sparkline.tsx`, `AreaCurve.tsx`): a monotone-X curve stroked 1.5 in the series
 * colour over an area filled with a vertical gradient of that colour, 0.35 to 0.03, drawn at 0.6.
 */
@Composable
fun Sparkline(values: List<Float>, color: Color, modifier: Modifier = Modifier) {
    val stroke = DoonaTheme.size.sparkStroke
    Canvas(modifier) {
        if (values.size < 2) return@Canvas
        val sw = stroke.toPx()
        // doona's domain and range (`Sparkline.tsx:42-49`): min × 0.85 to max × 1.05, drawn 2 px inside the box.
        val lo = values.min() * DoonaChart.DOMAIN_LOW
        val hi = values.max() * DoonaChart.DOMAIN_HIGH
        val span = (hi - lo).takeIf { it > 0f } ?: 1f
        val inset = DoonaChart.INSET.toPx()
        val points = values.mapIndexed { i, v ->
            Offset(i * size.width / (values.size - 1), inset + (1f - (v - lo) / span) * (size.height - 2 * inset))
        }
        val line = monotoneX(points)
        val area = monotoneX(points).apply {
            lineTo(points.last().x, size.height)
            lineTo(points.first().x, size.height)
            close()
        }
        drawPath(
            area,
            Brush.verticalGradient(
                listOf(color.copy(alpha = DoonaChart.FILL_TOP), color.copy(alpha = DoonaChart.FILL_BOTTOM)),
            ),
            alpha = DoonaChart.FILL_OPACITY,
        )
        drawPath(line, color, style = Stroke(width = sw, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/** d3's curveMonotoneX (Fritsch–Carlson tangents) as cubic Béziers, the curve doona draws with. */
private fun monotoneX(p: List<Offset>): Path {
    val n = p.size
    val slope = FloatArray(n - 1) { i -> (p[i + 1].y - p[i].y) / (p[i + 1].x - p[i].x) }
    val tangent = FloatArray(n) { i ->
        when (i) {
            0 -> slope[0]

            n - 1 -> slope[n - 2]

            else -> {
                val a = slope[i - 1]
                val b = slope[i]
                if (a * b <= 0f) {
                    0f
                } else {
                    val h0 = p[i].x - p[i - 1].x
                    val h1 = p[i + 1].x - p[i].x
                    val m = (a * h1 + b * h0) / (h0 + h1)
                    (if (a < 0) -1f else 1f) * minOf(kotlin.math.abs(a), kotlin.math.abs(b), 0.5f * kotlin.math.abs(m)) * 2f
                }
            }
        }
    }
    return Path().apply {
        moveTo(p[0].x, p[0].y)
        for (i in 0 until n - 1) {
            val dx = (p[i + 1].x - p[i].x) / 3f
            cubicTo(p[i].x + dx, p[i].y + dx * tangent[i], p[i + 1].x - dx, p[i + 1].y - dx * tangent[i + 1], p[i + 1].x, p[i + 1].y)
        }
    }
}
