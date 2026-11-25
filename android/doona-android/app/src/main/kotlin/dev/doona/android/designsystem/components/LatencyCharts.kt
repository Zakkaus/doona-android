package dev.doona.android.designsystem.components

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
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.doona.android.designsystem.rv.latencyTone
import dev.doona.android.designsystem.theme.DoonaTheme
import dev.doona.android.designsystem.theme.scripted
import kotlin.math.abs
import kotlin.math.roundToInt

// Revolut's EPS dot chart turned to latency, in one grammar (fix round 2): the axis from [latencyAxisTop], solid
// hairlines at the top, the middle and 0 labelled at the right with the unit on the top one, a dotted median with its
// value in the value pill at the line's end that hides a label it would cover, and a dot per test in its quality's hue.
// Two charts share it: a node's tests over time ([DoonaLatencyHistory]) and every node at once ([DoonaLatencySpread]).
// They differ in what lies along the x axis, so they stay two components over the shared axis and median below.

/**
 * A node's last tests (node detail): one dot per test of [history] in its quality's hue, an empty ring for one that
 * timed out (null, drawn at the top: no answer is the slowest), and every other test's time of [times] under its dot.
 * [label] writes a latency with its unit (the top label and the [median]'s pill). The [selected] test is marked as the
 * picked year on Revolut's EPS chart: a tinted column behind its dot down the plot, and, apart from it, a 28 dp chip
 * round its time, 8 dp wider than the time on each side (S7-14, N03); a time the chip would reach is left out. The
 * dots are spread so the widest time's chip fits inside the plot, and a tap reports the nearest test to [onSelect].
 */
@Composable
fun DoonaLatencyHistory(
    history: List<Int?>,
    median: Int?,
    times: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    label: (Int) -> String,
    modifier: Modifier = Modifier,
) {
    val d = DoonaLatencyDefaults
    val top = latencyAxisTop(history.filterNotNull().maxOrNull() ?: 0)
    val look = historyLook(history)
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val axis = Axis(listOf(top to label(top), top / 2 to "${top / 2}", 0 to "0"), median?.let(label), measurer, look.label)
    val pad = with(density) { d.chipPad.toPx() }
    val widest = times.maxOfOrNull { measurer.measure(it, look.picked).size.width } ?: 0
    val strip = axis.width(density) + with(density) { d.plotGap.toPx() }
    val pick by rememberUpdatedState(onSelect)
    Canvas(
        modifier.layoutRole(LayoutRole.Chart).fillMaxWidth().height(d.head + d.plot + d.labelGap + d.chipHeight).pointerInput(history.size, widest, strip) {
            detectTapGestures { tap -> pick(Columns(size.width - strip, history.size, widest + 2 * pad).at(tap.x)) }
        },
    ) {
        val plot = HistoryPlot(this, Columns(size.width - strip, history.size, widest + 2 * pad), strip)
        val frame = AxisFrame(right = plot.right, top = d.head.toPx(), bottom = plot.bottom, topMs = top)
        axis.grid(this, frame, median, look.grid)
        val picked = times.getOrNull(selected)?.let { measurer.measure(it, look.picked) }
        val chip = picked?.let { plot.chip(selected, it, look.chip) }
        axis.median(this, frame, median, look.reference, look.pill)
        history.forEachIndexed { i, ms -> plot.dot(i, ms?.let(frame::y), look.dots[i], look.missed) }
        var shownTo = Float.NEGATIVE_INFINITY
        times.forEachIndexed { i, time ->
            if (i != selected && i % 2 == times.lastIndex % 2) shownTo = plot.time(i, measurer.measure(time, look.label), clearOf = chip, after = shownTo)
        }
        picked?.let { plot.time(selected, it, clearOf = null) }
    }
}

/**
 * Every tested node at once (統計's 延遲分布), in node detail's grammar (S04): a dot per node of [values] in its quality's
 * hue, sorted from the fastest by the caller, the median's pill, and [label] writing a latency with its unit. The current
 * node ([selected], an index into [values]) stands in a column of the primary colour's pale tint that stops 4 dp under
 * the plot, apart from [name]'s chip of the same tint 8 dp under the plot. The dots are small enough that neighbours keep
 * a gap at 47 nodes; the selected one is a step larger and still clear of its neighbours.
 */
@Composable
fun DoonaLatencySpread(values: List<Int>, selected: Int?, name: String, median: Int?, label: (Int) -> String, modifier: Modifier = Modifier) {
    val colors = DoonaTheme.colors
    val d = DoonaLatencyDefaults
    val measurer = rememberTextMeasurer()
    val axisStyle = DoonaTheme.type.pill.copy(color = colors.textSecondary)
    // A node's name keeps its own figures: Inter's tabular figures widen the hyphen of `hk-01`.
    val chipStyle = DoonaTheme.type.pill.copy(color = colors.down.line, fontWeight = FontWeight.SemiBold, fontFeatureSettings = null)
    val top = latencyAxisTop(values.maxOrNull() ?: 0)
    val axis = Axis(listOf(top to label(top), top / 2 to "${top / 2}", 0 to "0"), median?.let(label), measurer, axisStyle)
    val pill = PillInk(colors.pill, colors.raisedShadow)
    val hues = values.map { toneColor(colors, latencyTone(it)) }
    val density = LocalDensity.current
    val strip = axis.width(density) + with(density) { d.axisLabelGap.toPx() }
    Canvas(modifier.layoutRole(LayoutRole.Chart).fillMaxWidth().height(d.spread)) {
        // At a large font scale the name could outgrow the chart: it ends in an ellipsis instead.
        val chipMax = (size.width - 2 * d.nameChipPadH.toPx()).toInt().coerceAtLeast(0)
        val chip = measurer.measure(name, chipStyle, TextOverflow.Ellipsis, maxLines = 1, constraints = Constraints(maxWidth = chipMax))
        val chipH = chip.size.height + 2 * d.nameChipPadV.toPx()
        val frame = AxisFrame(right = size.width - strip, top = axis.topLabelHeight / 2f, bottom = size.height - chipH - d.nameChipGap.toPx(), topMs = top)
        axis.grid(this, frame, median, colors.grid)
        axis.median(this, frame, median, colors.reference, pill)
        if (values.isEmpty()) return@Canvas
        val pitch = frame.right / values.size
        val x = { i: Int -> pitch * (i + 0.5f) }
        selected?.let { i ->
            val w = d.column.toPx()
            drawRoundRect(colors.down.area, Offset(x(i) - w / 2, 0f), Size(w, frame.bottom + d.columnBelow.toPx()), CornerRadius(w / 2))
            nameChip(chip, x(i), chipH, colors.down.area)
        }
        values.forEachIndexed { i, ms -> if (i != selected) drawCircle(hues[i], d.spreadDot.toPx() / 2, Offset(x(i), frame.y(ms))) }
        selected?.let { i -> drawCircle(hues[i], d.spreadDotSelected.toPx() / 2, Offset(x(i), frame.y(values[i]))) }
    }
}

/** A latency chart's plot: from 0 to [right], [top] to [bottom], 0 ms at the bottom and [topMs] at the top. */
private class AxisFrame(val right: Float, val top: Float, val bottom: Float, val topMs: Int) {
    fun y(ms: Int) = bottom - (bottom - top) * ms.coerceAtMost(topMs) / topMs
}

/** The shared axis: its [ticks] (a latency and its label), and the [median]'s pill text, measured in [style]. */
private class Axis(ticks: List<Pair<Int, String>>, median: String?, measurer: TextMeasurer, style: TextStyle) {
    private val labels = ticks.map { (ms, text) -> ms to measurer.measure(scripted(text), style) }
    private val pill = median?.let { measurer.measure(scripted(it), style) }
    val topLabelHeight = labels.first().second.size.height

    /** The label strip's width: the widest label or the median's pill. */
    fun width(density: Density): Float {
        val pillWidth = pill?.let { it.size.width + with(density) { 2 * DoonaTheme.chart.pillPadH.toPx() } } ?: 0f
        return maxOf(labels.maxOf { it.second.size.width }.toFloat(), pillWidth)
    }

    /** The hairlines in [color] with their labels; a label the [median]'s pill would cover is left out. */
    fun grid(scope: DrawScope, frame: AxisFrame, median: Int?, color: Color) = with(scope) {
        val medianY = median?.let(frame::y)
        labels.forEach { (ms, text) ->
            val y = frame.y(ms)
            drawLine(color, Offset(0f, y), Offset(frame.right, y), 1f)
            val covered = pill != null && medianY != null && abs(medianY - y) <= (pillSize(pill).height + text.size.height) / 2
            if (!covered) drawText(text, topLeft = Offset(size.width - text.size.width, y - text.size.height / 2f))
        }
    }

    /** The dotted [median] across the plot in [reference], its value in the value pill at the strip's end. */
    fun median(scope: DrawScope, frame: AxisFrame, median: Int?, reference: Color, ink: PillInk) = with(scope) {
        if (pill == null || median == null) return@with
        val y = frame.y(median)
        dottedAcross(reference, y, to = frame.right)
        val box = pillSize(pill)
        drawValuePill(pill, Offset(size.width - box.width, (y - box.height / 2).coerceIn(0f, frame.bottom - box.height)), ink)
    }
}

/** The colours and text styles the history chart draws with. */
private class HistoryLook(
    val label: TextStyle,
    val picked: TextStyle,
    val grid: Color,
    val reference: Color,
    val chip: Color,
    val pill: PillInk,
    val missed: Color,
    val dots: List<Color>,
)

@Composable
private fun historyLook(history: List<Int?>): HistoryLook {
    val colors = DoonaTheme.colors
    return HistoryLook(
        label = DoonaTheme.type.pill.copy(color = colors.textSecondary),
        picked = DoonaTheme.type.pill.copy(color = colors.positive, fontWeight = FontWeight.SemiBold),
        grid = colors.grid,
        reference = colors.reference,
        chip = colors.positive.copy(alpha = if (colors.dark) DoonaLatencyDefaults.CHIP_DARK else DoonaLatencyDefaults.CHIP_LIGHT),
        pill = PillInk(colors.pill, colors.raisedShadow),
        missed = colors.textSecondary,
        dots = history.map { toneColor(colors, latencyTone(it)) },
    )
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

/** The history chart's geometry in one [scope]: the [columns] across the plot, the axis labels in a [strip] at the right. */
private class HistoryPlot(private val scope: DrawScope, private val columns: Columns, strip: Float) {
    private val d = DoonaLatencyDefaults
    val bottom = with(scope) { d.head.toPx() + d.plot.toPx() }
    val right = scope.size.width - strip
    private val chipTop = bottom + with(scope) { d.labelGap.toPx() }
    private val chipHeight = with(scope) { d.chipHeight.toPx() }

    fun x(i: Int) = columns.x(i)

    /** The selected test's column behind its dot, and its chip round its [time]; returns the chip's start and end. */
    fun chip(i: Int, time: TextLayoutResult, color: Color): ClosedFloatingPointRange<Float> = with(scope) {
        val column = d.historyColumn.toPx()
        drawRoundRect(color, Offset(x(i) - column / 2, 0f), Size(column, bottom + d.columnBelow.toPx()), CornerRadius(column / 2))
        val width = time.size.width + 2 * d.chipPad.toPx()
        drawRoundRect(color, Offset(x(i) - width / 2, chipTop), Size(width, chipHeight), CornerRadius(chipHeight / 2))
        (x(i) - width / 2)..(x(i) + width / 2)
    }

    /** A test's dot at [y], or an empty ring at the top for one that timed out. */
    fun dot(i: Int, y: Float?, color: Color, missed: Color) = with(scope) {
        val r = d.historyDot.toPx() / 2
        if (y == null) {
            drawCircle(missed, r - d.missedRing.toPx() / 2, Offset(x(i), d.head.toPx()), style = Stroke(d.missedRing.toPx()))
        } else {
            drawCircle(color, r, Offset(x(i), y))
        }
    }

    /**
     * A test's time centred under its dot, unless it would reach into the chip at [clearOf] or come closer than a chip's
     * padding to the time drawn before it, which ends at [after] (a large font widens the times); returns where the last
     * time drawn ends.
     */
    fun time(i: Int, text: TextLayoutResult, clearOf: ClosedFloatingPointRange<Float>?, after: Float = Float.NEGATIVE_INFINITY): Float = with(scope) {
        val start = x(i) - text.size.width / 2
        val end = start + text.size.width
        val top = chipTop + (chipHeight - text.size.height) / 2
        val clear = (clearOf == null || end < clearOf.start || start > clearOf.endInclusive) && start >= after + d.chipPad.toPx()
        if (clear) drawText(text, topLeft = Offset(start, top))
        if (clear) end else after
    }
}

/** [text] in a chip centred under [x] at the bottom of the chart, kept inside it. */
private fun DrawScope.nameChip(text: TextLayoutResult, x: Float, height: Float, fill: Color) {
    val padH = DoonaLatencyDefaults.nameChipPadH.toPx()
    val width = text.size.width + 2 * padH
    val left = (x - width / 2).coerceAtMost(size.width - width).coerceAtLeast(0f)
    val top = size.height - height
    drawRoundRect(fill, Offset(left, top), Size(width, height), CornerRadius(height / 2))
    drawText(text, topLeft = Offset(left + padH, top + DoonaLatencyDefaults.nameChipPadV.toPx()))
}

/** The latency charts' own metrics (the sample's node detail and 統計). */
internal object DoonaLatencyDefaults {
    /** History: the plot, the gap from it to the times, room above for the top label, the gap to the label strip. */
    val plot = 140.dp
    val labelGap = 8.dp
    val head = 10.dp
    val plotGap = 8.dp

    /** History: the dots (§10.2: Revolut's EPS dots are 8 dp, N04) and a timed-out test's ring. */
    val historyDot = 8.dp
    val missedRing = 1.5.dp

    /** History: the selected test's column behind its dot, reaching this far under the plot, apart from its time's chip. */
    val historyColumn = 24.dp
    val columnBelow = 4.dp

    /** History: the selected time's chip, this much wider than its time on each side, this tall (S7-14). */
    val chipPad = 8.dp
    val chipHeight = 28.dp
    const val CHIP_LIGHT = 0.1f
    const val CHIP_DARK = 0.16f

    /** Spread: the chart, its dots (selected a step larger), the current node's column and its name's chip. */
    val spread = 156.dp
    val spreadDot = 3.5.dp
    val spreadDotSelected = 5.5.dp
    val column = 10.dp
    val axisLabelGap = 8.dp
    val nameChipPadH = 8.dp
    val nameChipPadV = 3.dp
    val nameChipGap = 8.dp
}
