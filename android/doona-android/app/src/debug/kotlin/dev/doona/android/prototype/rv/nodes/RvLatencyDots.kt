package dev.doona.android.prototype.rv.nodes

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import dev.doona.android.R
import dev.doona.android.designsystem.components.latencyTone
import dev.doona.android.designsystem.rv.Rv
import dev.doona.android.designsystem.rv.RvChartSize
import dev.doona.android.designsystem.rv.RvLatencyDotsSize
import dev.doona.android.designsystem.rv.RvPillInk
import dev.doona.android.designsystem.rv.RvText
import dev.doona.android.designsystem.rv.rvChartMarker
import dev.doona.android.designsystem.rv.rvLatencyAxisMiddle
import dev.doona.android.designsystem.rv.rvLatencyAxisTop
import dev.doona.android.designsystem.rv.rvMarkerChip
import dev.doona.android.designsystem.rv.rvPillSize
import dev.doona.android.designsystem.rv.rvValuePill
import dev.doona.android.designsystem.rv.scripted
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Revolut's EPS dot chart for a node's last tests: one dot per test in its quality's hue, an empty ring for one that
 * timed out (drawn at the top: no answer is the slowest), the axis labels on the right, and every other test's time
 * under its dot. A dotted line marks [median], its value in a pill at the line's right end, where Revolut sets a
 * reference price; an axis label it would cover is left out. The strip for the labels is as wide as the widest of
 * them and the pill, [RvLatencyDotsSize.plotGap] clear of the plot (S7-15). The [selected] test is marked with the library's
 * [rvChartMarker], as 統計's latency chart marks the current node (S7-14, N03). A time the chip would reach is left out.
 * The dots are spread so the widest time's chip fits inside the plot, and a tap picks the nearest test; a single test
 * sits where the latest of many would, half its chip in from the plot's end, its axis chosen to put it on the middle line
 * ([rvLatencyAxisMiddle]), its value in the pill a median has, with no dotted line (the owner's B of item 9). With no test the plot keeps its grid and labels, and 尚無資料 sits at its centre, at the
 * size of an empty card's text, the middle hairline stopping [RvLatencyDotsSize.plotGap] short of it on either side.
 */
@Composable
internal fun RvLatencyDots(
    history: List<Int?>,
    median: Int?,
    times: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Rv.colors
    val slowest = history.filterNotNull().maxOrNull() ?: 0
    // One test sits on the middle line, not alone under the top one (owner, item 7).
    val top = if (history.size == 1) rvLatencyAxisMiddle(slowest) else rvLatencyAxisTop(slowest)
    // The unit on the top label only, as 統計's latency chart labels its axis (L-11).
    val axis = listOf(stringResource(R.string.latency_ms, top), "${top / 2}", "0")
    // The pill at the strip's end: the median's, or one test's own value.
    val pillMs = median ?: history.singleOrNull()
    val pillText = pillMs?.let { stringResource(R.string.latency_ms, it) }
    val look = DotsLook(
        label = Rv.type.pill.copy(color = colors.textSecondary),
        picked = Rv.type.pill.copy(color = colors.positive, fontWeight = FontWeight.SemiBold),
        grid = colors.grid,
        reference = colors.reference,
        chip = colors.down.area,
        pill = RvPillInk(colors.pill, colors.raisedShadow),
        missed = colors.textSecondary,
        dots = history.map { colors.quality(latencyTone(it)) },
    )
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val pad = with(density) { RvChartSize.pillPadH.toPx() }
    val widest = times.maxOfOrNull { measurer.measure(it, look.picked).size.width } ?: 0
    val chipHeight = with(density) { rvMarkerChip(times.firstOrNull()?.let { measurer.measure(it, look.picked).size.height.toFloat() } ?: 0f) }
    val pillWidth = pillText?.let { measurer.measure(scripted(it), look.label).size.width + with(density) { 2 * RvChartSize.pillPadH.toPx() } } ?: 0f
    val labelWidth = axis.maxOf { measurer.measure(scripted(it), look.label).size.width }.toFloat()
    val strip = maxOf(pillWidth, labelWidth) + with(density) { RvLatencyDotsSize.plotGap.toPx() }
    val empty = if (history.isEmpty()) stringResource(R.string.rv_stats_no_data) else null
    val emptySize = empty?.let { measurer.measure(scripted(it), Rv.type.rowSub).size }
    val pick by rememberUpdatedState(onSelect)
    Box(modifier.fillMaxWidth()) {
        Canvas(
            Modifier.testTag(RV_LATENCY_DOTS).fillMaxWidth().height(RvLatencyDotsSize.head + RvLatencyDotsSize.plot + RvChartSize.markerGap + with(density) { chipHeight.toDp() }).pointerInput(history.size, widest, strip) {
                detectTapGestures { tap -> if (history.isNotEmpty()) pick(Columns(size.width - strip, history.size, widest + 2 * pad).at(tap.x)) }
            },
        ) {
            val plot = Plot(this, Columns(size.width - strip, history.size, widest + 2 * pad), strip, chipHeight)
            val pill = pillText?.let { measurer.measure(scripted(it), look.label) }
            val pillY = pillMs?.let { plot.y(it, top) }
            val avoid = if (pill != null && pillY != null) pillY to rvPillSize(pill).height else null
            plot.grid(axis, measurer, look, avoid, clear = emptySize?.let { plot.centred(it.width.toFloat(), it.height.toFloat()) })
            val picked = times.getOrNull(selected)?.let { measurer.measure(it, look.picked) }
            val chip = picked?.let { plot.marker(selected, it, look.chip) }
            if (pill != null && pillY != null) plot.pill(pillY, pill, look, dotted = median != null)
            history.forEachIndexed { i, ms -> plot.dot(i, ms?.let { plot.y(it, top) }, look.dots[i], look.missed) }
            times.forEachIndexed { i, time ->
                if (i != selected && i % 2 == times.lastIndex % 2) plot.time(i, measurer.measure(time, look.label), clearOf = chip)
            }
        }
        // The plot's own box, the grid's width and height, so the line's centre is the plot's.
        if (empty != null) {
            Box(Modifier.padding(top = RvLatencyDotsSize.head, end = with(density) { strip.toDp() }).fillMaxWidth().height(RvLatencyDotsSize.plot), Alignment.Center) {
                RvText(empty, Rv.type.rowSub, color = colors.textSecondary, align = TextAlign.Center)
            }
        }
    }
}

/**
 * Where [count] dots sit across a plot [right] wide: one column each, or, when the widest time's chip is wider than a
 * column, spread from half a [chip] in from either end, so no chip leaves the plot. One dot sits where the latest of many
 * does, half a chip in from the plot's right.
 */
private class Columns(private val right: Float, private val count: Int, chip: Float) {
    val column = right / count
    private val edge = maxOf(column, chip) / 2
    private val step = if (count > 1) (right - 2 * edge) / (count - 1) else 0f
    private val first = if (count == 1) right - chip / 2 else edge

    /** From one dot to the next: the slot the marker's column fills. */
    val pitch = if (count > 1) step else column

    fun x(i: Int) = first + step * i

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
    val pill: RvPillInk,
    val missed: Color,
    val dots: List<Color>,
)

/** The chart's geometry in one [scope]: the [columns] across the plot, the axis labels in a [strip] at the right. */
private class Plot(private val scope: DrawScope, private val columns: Columns, strip: Float, private val chipHeight: Float) {
    private val head = with(scope) { RvLatencyDotsSize.head.toPx() }
    private val bottom = head + with(scope) { RvLatencyDotsSize.plot.toPx() }
    private val right = scope.size.width - strip
    private val chipTop = bottom + with(scope) { RvChartSize.markerGap.toPx() }

    fun x(i: Int) = columns.x(i)

    fun y(ms: Int, top: Int) = bottom - (bottom - head) * ms / top

    /** A box [width] by [height] at the plot's centre, [RvLatencyDotsSize.plotGap] wider on either side. */
    fun centred(width: Float, height: Float): Rect {
        val half = width / 2 + with(scope) { RvLatencyDotsSize.plotGap.toPx() }
        val centre = Offset(right / 2, (head + bottom) / 2)
        return Rect(centre.x - half, centre.y - height / 2, centre.x + half, centre.y + height / 2)
    }

    /**
     * Hairlines at the top, the middle and zero, each with its label at the strip's end; a label within reach of
     * [avoid]'s height (a centre and a height) is left out, and a hairline through [clear] stops at its sides.
     */
    fun grid(labels: List<String>, measurer: TextMeasurer, look: DotsLook, avoid: Pair<Float, Float>?, clear: Rect?) = with(scope) {
        labels.forEachIndexed { i, label ->
            val y = head + (bottom - head) * i / (labels.size - 1)
            if (clear == null || y < clear.top || y > clear.bottom) {
                drawLine(look.grid, Offset(0f, y), Offset(right, y), 1f)
            } else {
                drawLine(look.grid, Offset(0f, y), Offset(clear.left, y), 1f)
                drawLine(look.grid, Offset(clear.right, y), Offset(right, y), 1f)
            }
            val text = measurer.measure(scripted(label), look.label)
            val clear = avoid == null || abs(avoid.first - y) > (avoid.second + text.size.height) / 2
            if (clear) drawText(text, topLeft = Offset(size.width - text.size.width, y - text.size.height / 2))
        }
    }

    /** The selected test's marker, its [time] in the chip; returns the chip's start and end. */
    fun marker(i: Int, time: TextLayoutResult, color: Color): ClosedFloatingPointRange<Float> = with(scope) {
        rvChartMarker(x(i), columns.pitch, RvLatencyDotsSize.dot.toPx(), bottom, time, color)
    }

    /** A value in a pill at the strip's end, centred on [y]; a median's ([dotted]) with its dotted line across the plot. */
    fun pill(y: Float, text: TextLayoutResult, look: DotsLook, dotted: Boolean) = with(scope) {
        if (dotted) {
            val dots = PathEffect.dashPathEffect(floatArrayOf(DOT_LENGTH, RvChartSize.dottedPitch.toPx()))
            drawLine(look.reference, Offset(0f, y), Offset(right, y), RvChartSize.dotted.toPx(), StrokeCap.Round, dots)
        }
        val pill = rvPillSize(text)
        val topLeft = Offset(size.width - pill.width, (y - pill.height / 2).coerceIn(0f, bottom - pill.height))
        rvValuePill(text, topLeft, look.pill)
    }

    /** A test's dot at [y], or an empty ring at the top for one that timed out. */
    fun dot(i: Int, y: Float?, color: Color, missed: Color) = with(scope) {
        val r = RvLatencyDotsSize.dot.toPx() / 2
        if (y == null) {
            drawCircle(missed, r - RvLatencyDotsSize.ring.toPx() / 2, Offset(x(i), head), style = Stroke(RvLatencyDotsSize.ring.toPx()))
        } else {
            drawCircle(color, r, Offset(x(i), y))
        }
    }

    /** A test's time centred under its dot, unless it would reach into the chip at [clearOf]. */
    fun time(i: Int, text: TextLayoutResult, clearOf: ClosedFloatingPointRange<Float>?) = with(scope) {
        val start = x(i) - text.size.width / 2
        val end = start + text.size.width
        val top = chipTop + (chipHeight - text.size.height) / 2
        if (clearOf == null || end < clearOf.start || start > clearOf.endInclusive) drawText(text, topLeft = Offset(start, top))
    }
}

/** The chart's test tag. */
internal const val RV_LATENCY_DOTS = "rv-latency-dots"

/** A dash this short with round caps draws as a dot. */
private const val DOT_LENGTH = 0.01f
