package dev.doona.android.designsystem.components

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import dev.doona.android.designsystem.theme.DoonaTheme
import kotlin.math.ceil

// What the charts share: where a value sits, a line that breaks at a gap, the area under it, and the dotted reference line.

/** Where values sit in a chart's canvas, in pixels: 0 on [bottom], [high] on [top]. */
internal class ValueScale(val high: Float, val top: Float, val bottom: Float) {
    fun y(value: Float) = if (high <= 0f) bottom else bottom - (bottom - top) * (value / high).coerceIn(0f, 1f)
}

/**
 * [values] spread over [0, right], joined by straight segments: at one sample a second the line stays dense and jagged,
 * as Revolut's are, and a spike keeps its sharp tip. A missing sample (NaN) breaks the line; nothing bridges the gap.
 */
internal fun polyline(values: List<Float>, scale: ValueScale, right: Float): Path {
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
internal fun areaUnder(values: List<Float>, scale: ValueScale, right: Float, bottom: Float) = Path().apply {
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

internal fun lineStroke(width: Float) = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round)

/** Round dots, as Revolut draws its reference lines. */
internal fun DrawScope.dots() = PathEffect.dashPathEffect(floatArrayOf(DOT_LENGTH, DoonaTheme.chart.dottedPitch.toPx()))

/** A dotted line in [color] at [y] from [from] to [to] (the whole width by default). */
internal fun DrawScope.dottedAcross(color: Color, y: Float, from: Float = 0f, to: Float = size.width) =
    drawLine(color, Offset(from, y), Offset(to, y), DoonaTheme.chart.dotted.toPx(), StrokeCap.Round, dots())

/** A latency chart's axis (node detail and 統計): up to the first whole 100 ms that leaves the slowest 5 % below the top. */
fun latencyAxisTop(slowest: Int): Int = ceil(maxOf(slowest, 1) * LATENCY_HEADROOM / LATENCY_STEP).toInt() * LATENCY_STEP

/** A dash this short with round caps draws as a dot. */
private const val DOT_LENGTH = 0.01f

private const val LATENCY_HEADROOM = 1.05f
private const val LATENCY_STEP = 100
