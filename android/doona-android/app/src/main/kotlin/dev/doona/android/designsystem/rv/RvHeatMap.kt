package dev.doona.android.designsystem.rv

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.unit.Constraints
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * One row of an [RvHeatMap]: its [label], the [color] of its hue (a role of [Rv.colors]), a count per column, and what a
 * screen reader hears for its cells ([spoken]: the busy columns in words).
 */
@Immutable
internal class RvHeatRow(val label: String, val color: Color, val counts: List<Int>, val spoken: String)

/**
 * doona web's heat map (`ui/charts/Heatmap.tsx`): a row per category, a column per stretch of time. A cell's tone says how
 * busy its row was then against the row's own busiest column ([rvHeatTone]), so three errors stand out beside a
 * thousand info records: the row's hue at one of five steps ([RvAlpha.HEAT]), or the track when the column is empty.
 * Cells are square-cornered marks, as a legend's square is (the corner scale, [RvSize.cardRadius]), [RvSpace.tight]
 * apart. The labels share one column as wide as the widest, at most [RvHeatMapSize.LABEL_SHARE] of the width, and
 * [RvSpace.trailingGap] before the cells; a row is as tall as its cells or its label, whichever is taller. Under the
 * cells, [columns]' first, middle and last start times in the caption type; the middle one gives way where it would
 * come within [RvSpace.cardGap] of either end, as the web's does.
 */
@Composable
internal fun RvHeatMap(rows: List<RvHeatRow>, columns: List<String>, modifier: Modifier = Modifier) {
    val marks = when {
        columns.isEmpty() -> emptyList()
        columns.size == 1 -> listOf(columns[0])
        columns.size == 2 -> listOf(columns[0], columns[1])
        else -> listOf(columns.first(), columns[columns.size / 2], columns.last())
    }
    Layout(
        contents = listOf(
            { rows.forEach { RvText(it.label, Rv.type.tileCaption, maxLines = Int.MAX_VALUE) } },
            { rows.forEach { HeatCells(it) } },
            { marks.forEach { RvText(it, Rv.type.tileCaption, color = Rv.colors.textSecondary) } },
        ),
        modifier,
    ) { (labels, cells, times), constraints ->
        val width = constraints.maxWidth
        val gap = RvSpace.tight.roundToPx()
        val labelGap = RvSpace.trailingGap.roundToPx()
        val labelMax = (width * RvHeatMapSize.LABEL_SHARE).roundToInt()
        val labelPlaced = labels.map { it.measure(Constraints(maxWidth = labelMax)) }
        val labelWidth = labelPlaced.maxOfOrNull { it.width } ?: 0
        val start = labelWidth + labelGap
        val cellWidth = (width - start).coerceAtLeast(0)
        val cellPlaced = cells.map { it.measure(Constraints.fixed(cellWidth, RvHeatMapSize.cell.roundToPx())) }
        val heights = labelPlaced.indices.map { maxOf(labelPlaced[it].height, cellPlaced[it].height) }
        val rowsHeight = heights.sum() + gap * (heights.size - 1).coerceAtLeast(0)
        val timePlaced = times.map { it.measure(Constraints(maxWidth = cellWidth)) }
        val timesTop = rowsHeight + gap
        val timesHeight = timePlaced.maxOfOrNull { it.height } ?: 0
        val shown = shownMarks(timePlaced, start, width, RvSpace.cardGap.roundToPx())
        layout(width, if (timePlaced.isEmpty()) rowsHeight else timesTop + timesHeight) {
            var y = 0
            heights.forEachIndexed { i, h ->
                labelPlaced[i].place(0, y + (h - labelPlaced[i].height) / 2)
                cellPlaced[i].place(start, y + (h - cellPlaced[i].height) / 2)
                y += h + gap
            }
            shown.forEach { (placeable, x) -> placeable.place(x, timesTop) }
        }
    }
}

/** The marks under the cells with their x: the first at the cells' start, the last at the end, the middle centred. */
private fun shownMarks(marks: List<Placeable>, start: Int, end: Int, room: Int): List<Pair<Placeable, Int>> {
    if (marks.isEmpty()) return emptyList()
    val first = marks.first() to start
    if (marks.size == 1) return listOf(first)
    val last = marks.last() to end - marks.last().width
    if (marks.size == 2) return listOf(first, last)
    val middle = marks[1]
    val x = (start + end - middle.width) / 2
    val fits = x - (start + first.first.width) >= room && last.second - (x + middle.width) >= room
    return if (fits) listOf(first, middle to x, last) else listOf(first, last)
}

/** A row's cells, edge to edge in the width they are given, [RvSpace.tight] apart. */
@Composable
private fun HeatCells(row: RvHeatRow) {
    val track = Rv.colors.track
    Canvas(Modifier, contentDescription = row.spoken) {
        val n = row.counts.size
        if (n == 0) return@Canvas
        val gap = RvSpace.tight.toPx()
        val cell = (size.width - gap * (n - 1)) / n
        val peak = row.counts.max()
        row.counts.forEachIndexed { i, count ->
            val tone = rvHeatTone(count, peak)
            val fill = if (tone == 0) track else row.color.copy(alpha = RvAlpha.HEAT[tone - 1])
            drawRect(fill, Offset(i * (cell + gap), 0f), Size(cell, size.height))
        }
    }
}

/**
 * A cell's tone, 0 for an empty one, else 1 to [RvAlpha.HEAT]'s size by its share of the row's [peak], rounded up so a
 * single record is never drawn empty (doona web's `heatTone`).
 */
internal fun rvHeatTone(count: Int, peak: Int): Int {
    if (count <= 0 || peak <= 0) return 0
    val tones = RvAlpha.HEAT.size
    return ceil(count.toDouble() / peak * tones).toInt().coerceIn(1, tones)
}
