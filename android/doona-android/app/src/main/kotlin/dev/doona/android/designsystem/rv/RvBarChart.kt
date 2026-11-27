package dev.doona.android.designsystem.rv

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp

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
 * a day of a week or a month), in [color], scaled to the busiest of [values]; a slot with no value (null, or past the
 * end of [values]: still to come) is a short stub on the track. [barWidth] fixes each bar's width (統計's own, for a row
 * that fits as many slots as [rvBarSlots] gives) instead of a share of the slot; [peak] scales several charts alike.
 */
@Composable
internal fun RvBarChart(
    values: List<Long?>,
    color: Color,
    modifier: Modifier = Modifier,
    slots: Int = RvUsageSize.HOURS,
    barWidth: Dp? = null,
    peak: Long? = null,
) {
    val stub = Rv.colors.track
    Canvas(modifier.fillMaxWidth().height(RvStatsSize.bars)) {
        val pitch = size.width / slots
        val bar = barWidth?.toPx()?.coerceAtMost(pitch) ?: (pitch * RvStatsSize.BAR_SHARE)
        val top = (peak ?: values.filterNotNull().maxOrNull())?.takeIf { it > 0 } ?: 1L
        val least = RvStatsSize.barStub.toPx()
        for (h in 0 until slots) {
            val value = values.getOrNull(h)
            val height = if (value == null) least else (size.height * value / top).coerceAtLeast(least)
            val left = h * pitch + (pitch - bar) / 2
            drawRoundRect(if (value == null) stub else color, Offset(left, size.height - height), Size(bar, height), CornerRadius(bar / 2))
        }
    }
}

/** How many of 統計's bars ([RvStatsSize.barWidth], [RvStatsSize.barGap] apart) fit across [width]. */
internal fun rvBarSlots(width: Dp): Int = ((width + RvStatsSize.barGap) / (RvStatsSize.barWidth + RvStatsSize.barGap)).toInt().coerceAtLeast(1)
