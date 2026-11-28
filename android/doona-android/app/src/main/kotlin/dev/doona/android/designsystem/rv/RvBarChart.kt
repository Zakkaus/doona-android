package dev.doona.android.designsystem.rv

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.Density
import kotlin.math.ceil

/**
 * A chart's grid (web `Charts` axis grid): hairlines in [RvColors.grid], a column every [every] of the [slots] (4 hours
 * of a day), both edges included, and [RvStatsSize.GRID_ROWS] rows, over all of [modifier]'s box.
 */
@Composable
internal fun RvChartGrid(slots: Int, every: Int, modifier: Modifier = Modifier) {
    val color = Rv.colors.grid
    Canvas(modifier) {
        val hairline = 1f
        for (slot in ((0..slots step every) + slots).distinct()) {
            val x = (size.width * slot / slots).coerceIn(hairline / 2, size.width - hairline / 2)
            drawLine(color, Offset(x, 0f), Offset(x, size.height), hairline)
        }
        for (j in 0 until RvStatsSize.GRID_ROWS) {
            val y = size.height * j / RvStatsSize.GRID_ROWS + hairline / 2
            drawLine(color, Offset(0f, y), Offset(size.width, y), hairline)
        }
    }
}

/**
 * A bar chart (web `Charts` bars), after Revolut's 'Income' card: one rounded bar per slot of [slots] (an hour of a day,
 * a day of a week or a month), in [color], scaled to the busiest of [values]; the slots past the end of [values] (still
 * to come) are short stubs on the track.
 */
@Composable
internal fun RvBarChart(values: List<Long>, color: Color, modifier: Modifier = Modifier, slots: Int = RvUsageSize.HOURS) {
    val stub = Rv.colors.track
    Canvas(modifier.fillMaxWidth().height(RvStatsSize.bars)) {
        val pitch = size.width / slots
        val bar = pitch * RvStatsSize.BAR_SHARE
        val peak = values.maxOrNull()?.takeIf { it > 0 } ?: 1L
        val least = RvStatsSize.barStub.toPx()
        for (h in 0 until slots) {
            val value = values.getOrNull(h)
            val height = if (value == null) least else (size.height * value / peak).coerceAtLeast(least)
            val left = h * pitch + (pitch - bar) / 2
            drawRoundRect(if (value == null) stub else color, Offset(left, size.height - height), Size(bar, height), CornerRadius(bar / 2))
        }
    }
}

/** Revolut's empty 'Income' card: twelve faint bars of made-up [heights] across the width, each fading out downward. */
@Composable
internal fun RvGhostBars(heights: FloatArray, modifier: Modifier = Modifier) {
    val ghost = Rv.colors.ghost
    Canvas(modifier.fillMaxWidth().height(RvChartSize.spark).testTag(RvPart.FRAME)) {
        val bar = RvChartSize.ghostBar.toPx()
        val pitch = (size.width - bar) / (RvChartSize.GHOST_BARS - 1)
        heights.forEachIndexed { i, height ->
            val top = size.height * (1 - height)
            val brush = Brush.verticalGradient(listOf(ghost, Color.Transparent), top, size.height)
            drawRoundRect(brush, Offset(i * pitch, top), Size(bar, size.height - top), CornerRadius(bar / 2))
        }
    }
}

/** Ghost bar heights as shares of the chart's height: download's, and upload's of its own shape (S5-09). */
internal val RV_GHOST_DOWN = floatArrayOf(0.42f, 0.64f, 0.5f, 0.8f, 0.58f, 0.36f, 0.7f, 0.9f, 0.54f, 0.68f, 0.46f, 0.76f)
internal val RV_GHOST_UP = floatArrayOf(0.3f, 0.22f, 0.48f, 0.34f, 0.6f, 0.4f, 0.26f, 0.52f, 0.72f, 0.44f, 0.58f, 0.38f)

/** A latency chart's axis (node detail and 統計): up to the first whole 100 ms that leaves the slowest 5 % below the top. */
internal fun rvLatencyAxisTop(slowest: Int): Int = ceil(maxOf(slowest, 1) * LATENCY_HEADROOM / LATENCY_STEP).toInt() * LATENCY_STEP

/**
 * The axis for a chart of one test, which then sits on its middle line: twice [value], so the middle label is the test's
 * own figure (92 → 184 ms; 20 → 40 ms). A test that timed out ([value] 0) keeps [rvLatencyAxisTop]'s.
 */
internal fun rvLatencyAxisMiddle(value: Int): Int = if (value > 0) 2 * value else rvLatencyAxisTop(value)

private const val LATENCY_HEADROOM = 1.05f
private const val LATENCY_STEP = 100

/**
 * The picked point of a latency chart, as Revolut's EPS chart marks its picked year (§10.2): a column of [fill] behind
 * the dot at [x], one [pitch] wide but no narrower than twice its [dot] and no wider than [RvChartSize.markerColumn],
 * from the top to [RvChartSize.markerBelow] under [plotBottom]; and [label] in a chip of the same fill, the value pill's
 * height and padding, [RvChartSize.markerGap] under the plot, kept inside the chart. Returns the chip's start and end.
 */
internal fun DrawScope.rvChartMarker(x: Float, pitch: Float, dot: Float, plotBottom: Float, label: TextLayoutResult, fill: Color): ClosedFloatingPointRange<Float> {
    val column = pitch.coerceIn(2 * dot, RvChartSize.markerColumn.toPx())
    drawRoundRect(fill, Offset(x - column / 2, 0f), Size(column, plotBottom + RvChartSize.markerBelow.toPx()), CornerRadius(column / 2))
    val padH = RvChartSize.pillPadH.toPx()
    val chip = Size(label.size.width + 2 * padH, rvMarkerChip(label.size.height.toFloat()))
    val left = (x - chip.width / 2).coerceAtMost(size.width - chip.width).coerceAtLeast(0f)
    val top = plotBottom + RvChartSize.markerGap.toPx()
    drawRoundRect(fill, Offset(left, top), chip, CornerRadius(chip.height / 2))
    drawText(label, topLeft = Offset(left + padH, top + (chip.height - label.size.height) / 2))
    return left..(left + chip.width)
}

/** The marker chip's height round a label [text] px tall: the value pill's, or the label's at a large font scale. */
internal fun Density.rvMarkerChip(text: Float): Float = maxOf(RvChartSize.pill.toPx(), text)
