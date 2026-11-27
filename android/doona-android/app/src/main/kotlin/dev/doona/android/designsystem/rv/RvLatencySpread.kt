package dev.doona.android.designsystem.rv

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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.log10

/**
 * Revolut's EPS chart turned to latency, in node detail's grammar (S04): a dot per tested node in its quality's hue,
 * sorted from the fastest, on hairlines at the top, the middle and zero, labelled at the right with the unit on the top
 * one, and a dotted line at the [median] with its value in the value pill at the line's end, which hides a label it would
 * cover. The axis leaves the slowest dot 5 % under the top. The current node ([selected], an index into [values]) is
 * marked with the library's [rvChartMarker] in the primary colour's pale tint, [label] in its chip, as node detail marks
 * its picked test. 統計's 延遲分布 draws it; any page that shows its nodes' spread of latency takes the same one.
 * [dotColors], one per value, colour the dots by what each sample was (DNS 統計's by outcome) instead of by quality; on a
 * [RvLatencyScale.Log] axis, as the web's beeswarm, a hairline at each decade from the fastest's to the slowest's, so a
 * five-second timeout and a ten-millisecond answer both stand where they are.
 */
@Composable
internal fun RvLatencySpread(
    values: List<Int>,
    selected: Int?,
    label: String,
    median: Int?,
    unit: String,
    modifier: Modifier = Modifier,
    dotColors: List<Color>? = null,
    scale: RvLatencyScale = RvLatencyScale.Linear,
) {
    val colors = Rv.colors
    val measurer = rememberTextMeasurer()
    val axisStyle = Rv.type.pill.copy(color = colors.textSecondary)
    val chipStyle = Rv.type.pillName.copy(color = colors.down.line)
    val axisScale = when (scale) {
        RvLatencyScale.Linear -> LinearAxis(rvLatencyAxisTop(values.maxOrNull() ?: 0))
        RvLatencyScale.Log -> LogAxis.of(values)
    }
    val ticks = axisScale.ticks(unit)
    val pillInk = RvPillInk(colors.pill, colors.raisedShadow)
    val dotInk = dotColors ?: values.map { latencyColor(it) }
    Canvas(modifier.fillMaxWidth().height(RvLatencySpreadSize.height)) {
        val axis = ticks.map { (ms, text) -> ms to measurer.measure(scripted(text), axisStyle) }
        val pill = median?.let { measurer.measure(scripted("$it $unit"), axisStyle) }
        val strip = maxOf(axis.maxOf { it.second.size.width }.toFloat(), pill?.let { rvPillSize(it).width } ?: 0f) + RvLatencySpreadSize.labelGap.toPx()
        // At a large font scale the label could outgrow the chart: it ends in an ellipsis instead.
        val chipMax = (size.width - 2 * RvChartSize.pillPadH.toPx()).toInt().coerceAtLeast(0)
        val chip = measurer.measure(label, chipStyle, TextOverflow.Ellipsis, maxLines = 1, constraints = Constraints(maxWidth = chipMax))
        val plot = Plot(
            right = size.width - strip,
            top = axis.first().second.size.height / 2f,
            bottom = size.height - rvMarkerChip(chip.size.height.toFloat()) - RvChartSize.markerGap.toPx(),
            axis = axisScale,
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
        selected?.let { i -> rvChartMarker(x(i), pitch, RvLatencySpreadSize.dotSelected.toPx(), plot.bottom, chip, colors.down.area) }
        values.forEachIndexed { i, ms ->
            if (i != selected) drawCircle(dotInk[i], RvLatencySpreadSize.dot.toPx() / 2, Offset(x(i), plot.y(ms)))
        }
        selected?.let { i -> drawCircle(dotInk[i], RvLatencySpreadSize.dotSelected.toPx() / 2, Offset(x(i), plot.y(values[i]))) }
    }
}

/** How the latency chart's axis runs: straight from 0 ms ([RvLatencyScale.Linear]), or by decades ([RvLatencyScale.Log]). */
internal enum class RvLatencyScale { Linear, Log }

/** An axis: where a latency stands from 0 (the bottom) to 1 (the top), and its labelled hairlines, the unit on the top one. */
private sealed interface Axis {
    fun share(ms: Int): Float
    fun ticks(unit: String): List<Pair<Int, String>>
}

/** Node detail's and 統計's axis: 0 ms at the bottom, [top] at the top, hairlines at the top, the middle and zero. */
private class LinearAxis(val top: Int) : Axis {
    override fun share(ms: Int) = ms.coerceIn(0, top).toFloat() / top
    override fun ticks(unit: String) = listOf(top to "$top $unit", top / 2 to "${top / 2}", 0 to "0")
}

/** The web's log axis (`logDomain`): from the decade at or under the fastest to the one at or over the slowest. */
private class LogAxis(val low: Int, val high: Int) : Axis {
    override fun share(ms: Int) = ((log10(ms.coerceIn(low, high).toFloat()) - log10(low.toFloat())) / (log10(high.toFloat()) - log10(low.toFloat())))
    override fun ticks(unit: String) = generateSequence(high) { if (it / 10 >= low) it / 10 else null }
        .map { it to if (it == high) "$it $unit" else "$it" }
        .toList()

    companion object {
        fun of(values: List<Int>): LogAxis {
            if (values.isEmpty()) return LogAxis(1, 10)
            val low = decade(values.min().coerceAtLeast(1), floor = true)
            val high = decade(values.max().coerceAtLeast(1), floor = false)
            return LogAxis(low, if (high > low) high else low * 10)
        }

        private fun decade(ms: Int, floor: Boolean): Int {
            var d = 1
            while (d * 10 <= ms) d *= 10
            return if (floor || d == ms) d else d * 10
        }
    }
}

/** The latency chart's plot: from 0 to [right], [top] to [bottom], placed on [axis]. */
private class Plot(val right: Float, val top: Float, val bottom: Float, val axis: Axis) {
    fun y(ms: Int) = bottom - (bottom - top) * axis.share(ms)
}

/** A hairline at [y] across the plot, and its label, if any, at the strip's end. */
private fun DrawScope.axisLine(plot: Plot, y: Float, text: TextLayoutResult?, grid: Color) {
    drawLine(grid, Offset(0f, y), Offset(plot.right, y), 1f)
    text?.let { drawText(it, topLeft = Offset(size.width - it.size.width, y - it.size.height / 2f)) }
}

/** The dotted median across the plot, its value in the value pill at the strip's end, as node detail draws it. */
private fun DrawScope.medianLine(plot: Plot, y: Float, text: TextLayoutResult, color: Color, ink: RvPillInk) {
    drawLine(color, Offset(0f, y), Offset(plot.right, y), RvChartSize.dotted.toPx(), StrokeCap.Round, rvDots())
    val pill = rvPillSize(text)
    rvValuePill(text, Offset(size.width - pill.width, (y - pill.height / 2).coerceIn(0f, plot.bottom - pill.height)), ink)
}

/**
 * The spread's sizes: dots small enough that neighbours keep a gap at a 5.6 dp pitch (47 nodes), the current node's dot a
 * step larger and still clear of its neighbours (S04); its marker is the library's.
 */
internal object RvLatencySpreadSize {
    val height = 156.dp
    val dot = 3.5.dp
    val dotSelected = 5.5.dp
    val labelGap = 8.dp
}
