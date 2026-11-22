package dev.doona.android.prototype.rv.nodes

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import dev.doona.android.R
import dev.doona.android.designsystem.components.latencyTone
import dev.doona.android.prototype.rv.Rv
import dev.doona.android.prototype.rv.RvChartSize
import dev.doona.android.prototype.rv.scripted
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Revolut's EPS dot chart for a node's last tests: one dot per test in its quality's hue, an empty ring for one that
 * timed out (drawn at the top: no answer is the slowest), the axis labels on the right, and every other test's time
 * under its dot. A dotted line marks [median], its value in a pill at the line's right end, where Revolut sets a
 * reference price; an axis label it would cover is left out. The [selected] test's column is a tinted chip, as behind
 * the picked year on Revolut's EPS chart: from the top of the plot to [CHIP_BELOW] under its time, at least the dot
 * column wide and its time's width plus [CHIP_PAD] on each side, the time centred in it. A time the chip would reach is
 * left out. The dots are spread so the widest time's chip fits inside the plot, and a tap picks the nearest test.
 */
@Composable
internal fun RvLatencyDots(history: List<Int?>, median: Int?, times: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val colors = Rv.colors
    val top = axisTop(history.filterNotNull().maxOrNull() ?: 0)
    val axis = listOf(top, top / 2, 0).map { stringResource(R.string.latency_ms, it) }
    val medianText = median?.let { stringResource(R.string.latency_ms, it) }
    val look = DotsLook(
        label = Rv.type.pill.copy(color = colors.textSecondary),
        picked = Rv.type.pill.copy(color = colors.positive, fontWeight = FontWeight.SemiBold),
        grid = colors.grid,
        reference = colors.reference,
        chip = colors.positive.copy(alpha = if (colors.dark) CHIP_DARK else CHIP_LIGHT),
        pill = colors.pill,
        pillEdge = colors.pillEdge,
        missed = colors.textSecondary,
        dots = history.map { colors.quality(latencyTone(it)) },
    )
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val pad = with(density) { CHIP_PAD.toPx() }
    val widest = times.maxOfOrNull { measurer.measure(it, look.picked).size.width } ?: 0
    val labelHeight = measurer.measure(times.getOrElse(selected) { "" }, look.picked).size.height
    val labels = with(density) { LABEL_GAP + labelHeight.toDp() + CHIP_BELOW }
    val pick by rememberUpdatedState(onSelect)
    Canvas(
        modifier.fillMaxWidth().height(HEAD + PLOT + labels).pointerInput(history.size, widest) {
            detectTapGestures { tap -> pick(Columns(size.width - AXIS.toPx(), history.size, widest + 2 * pad).at(tap.x)) }
        },
    ) {
        val plot = Plot(this, Columns(size.width - AXIS.toPx(), history.size, widest + 2 * pad))
        val pill = medianText?.let { measurer.measure(scripted(it), look.label) }
        val medianY = median?.let { plot.y(it, top) }
        plot.grid(axis, measurer, look, avoid = if (pill != null && medianY != null) medianY to pill.size.height + plot.pillPad.height else null)
        val picked = times.getOrNull(selected)?.let { measurer.measure(it, look.picked) }
        val chip = picked?.let { plot.chip(selected, it, look.chip) }
        if (pill != null && medianY != null) plot.median(medianY, pill, look)
        history.forEachIndexed { i, ms -> plot.dot(i, ms?.let { plot.y(it, top) }, look.dots[i], look.missed) }
        times.forEachIndexed { i, time ->
            if (i != selected && i % 2 == times.lastIndex % 2) plot.time(i, measurer.measure(time, look.label), clearOf = chip)
        }
        picked?.let { plot.time(selected, it, clearOf = null) }
    }
}

/**
 * Where [count] dots sit across a plot [right] wide: one column each, or, when the widest time's chip is wider than a
 * column, spread from half a [chip] in from either end, so no chip leaves the plot.
 */
private class Columns(private val right: Float, private val count: Int, chip: Float) {
    val column = right / count
    private val edge = maxOf(column, chip) / 2
    private val step = if (count > 1) (right - 2 * edge) / (count - 1) else 0f

    fun x(i: Int) = edge + step * i

    /** The test nearest to [x]. */
    fun at(x: Float) = if (step > 0f) ((x - edge) / step).roundToInt().coerceIn(0, count - 1) else 0
}

/** The colours and text styles the chart draws with. */
private class DotsLook(
    val label: TextStyle,
    val picked: TextStyle,
    val grid: Color,
    val reference: Color,
    val chip: Color,
    val pill: Color,
    val pillEdge: Color?,
    val missed: Color,
    val dots: List<Color>,
)

/** The chart's geometry in one [scope]: the [columns] across the plot, the axis labels in a strip at the right. */
private class Plot(private val scope: DrawScope, private val columns: Columns) {
    private val head = with(scope) { HEAD.toPx() }
    private val bottom = head + with(scope) { PLOT.toPx() }
    private val right = scope.size.width - with(scope) { AXIS.toPx() }
    private val timeTop = bottom + with(scope) { LABEL_GAP.toPx() }
    val pillPad = with(scope) { Size(RvChartSize.pillPadH.toPx(), RvChartSize.pillPadV.toPx() * 2) }

    fun x(i: Int) = columns.x(i)

    fun y(ms: Int, top: Int) = bottom - (bottom - head) * ms / top

    /**
     * Hairlines at the top, the middle and zero, each with its label at the strip's end; a label within reach of
     * [avoid]'s height (a centre and a height) is left out.
     */
    fun grid(labels: List<String>, measurer: TextMeasurer, look: DotsLook, avoid: Pair<Float, Float>?) = with(scope) {
        labels.forEachIndexed { i, label ->
            val y = head + (bottom - head) * i / (labels.size - 1)
            drawLine(look.grid, Offset(0f, y), Offset(right, y), 1f)
            val text = measurer.measure(scripted(label), look.label)
            val clear = avoid == null || abs(avoid.first - y) > (avoid.second + text.size.height) / 2
            if (clear) drawText(text, topLeft = Offset(size.width - text.size.width, y - text.size.height / 2))
        }
    }

    /** The selected test's chip round its [time]; returns the chip's start and end. */
    fun chip(i: Int, time: TextLayoutResult, color: Color): ClosedFloatingPointRange<Float> = with(scope) {
        val width = maxOf(time.size.width + 2 * CHIP_PAD.toPx(), columns.column)
        val height = timeTop + time.size.height + CHIP_BELOW.toPx()
        drawRoundRect(color, Offset(x(i) - width / 2, 0f), Size(width, height), CornerRadius(CHIP_RADIUS.toPx()))
        (x(i) - width / 2)..(x(i) + width / 2)
    }

    /** The dotted median across the plot, its value in a pill at the strip's end. */
    fun median(y: Float, text: TextLayoutResult, look: DotsLook) = with(scope) {
        val dots = PathEffect.dashPathEffect(floatArrayOf(DOT_LENGTH, RvChartSize.dottedPitch.toPx()))
        drawLine(look.reference, Offset(0f, y), Offset(right, y), RvChartSize.dotted.toPx(), StrokeCap.Round, dots)
        val pill = Size(text.size.width + 2 * pillPad.width, text.size.height + pillPad.height)
        val topLeft = Offset(size.width - pill.width, (y - pill.height / 2).coerceIn(0f, bottom - pill.height))
        val radius = CornerRadius(RvChartSize.pillRadius.toPx())
        drawRoundRect(look.pill, topLeft, pill, radius)
        look.pillEdge?.let { drawRoundRect(it, topLeft, pill, radius, Stroke(1f)) }
        drawText(text, topLeft = topLeft + Offset(pillPad.width, pillPad.height / 2))
    }

    /** A test's dot at [y], or an empty ring at the top for one that timed out. */
    fun dot(i: Int, y: Float?, color: Color, missed: Color) = with(scope) {
        val r = DOT.toPx() / 2
        if (y == null) {
            drawCircle(missed, r - RING.toPx() / 2, Offset(x(i), head), style = Stroke(RING.toPx()))
        } else {
            drawCircle(color, r, Offset(x(i), y))
        }
    }

    /** A test's time centred under its dot, unless it would reach into the chip at [clearOf]. */
    fun time(i: Int, text: TextLayoutResult, clearOf: ClosedFloatingPointRange<Float>?) = with(scope) {
        val start = x(i) - text.size.width / 2
        val end = start + text.size.width
        if (clearOf == null || end < clearOf.start || start > clearOf.endInclusive) drawText(text, topLeft = Offset(start, timeTop))
    }
}

/** The axis runs to the first whole 100 ms that leaves the slowest test 5 % below the top. */
private fun axisTop(slowest: Int) = ceil(maxOf(slowest, 1) * HEADROOM / STEP).toInt() * STEP

private const val HEADROOM = 1.05f
private const val STEP = 100
private const val CHIP_LIGHT = 0.1f
private const val CHIP_DARK = 0.16f

/** A dash this short with round caps draws as a dot. */
private const val DOT_LENGTH = 0.01f

/** The plot, the gap from it to the times, room above for the top label, and the strip for the axis labels. */
private val PLOT = 140.dp
private val LABEL_GAP = 8.dp
private val HEAD = 10.dp
private val AXIS = 68.dp

/** The dots (Revolut's 12 pt EPS dots, on a 423 dp screen 8 dp: ours sit between) and a timed-out test's ring. */
private val DOT = 10.dp
private val RING = 1.5.dp

/** The selected column's chip: this much wider than its time on each side, this far below it, this round. */
private val CHIP_PAD = 8.dp
private val CHIP_BELOW = 4.dp
private val CHIP_RADIUS = 12.dp
