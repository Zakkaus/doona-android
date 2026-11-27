package dev.doona.android.designsystem.rv

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** One share of an [RvWaffle]: its [label], its [color] (a role of [Rv.colors]), its [count], and, given, its [text]. */
@Immutable
internal class RvWaffleShare(val label: String, val color: Color, val count: Int, val text: String? = null)

/**
 * doona web's waffle (`ui/charts/Waffle.tsx`): a hundred cells split by share ([rvWaffleCells]), ten by ten in the shares'
 * order, so proportions read by eye. The grid is centred in the width, at most [RvWaffleSize.width] wide, its cells
 * square-cornered marks as the heat map's are, [RvSpace.tight] apart. Under it the shares' key in the one legend style
 * ([RvLegendItem]): the squares and labels in a wrapping line, or, when the shares carry their [RvWaffleShare.text], a
 * line each with that text at the end, as the web's legend gives the counts. [spoken] is what a screen reader hears for
 * the grid.
 */
@Composable
internal fun RvWaffle(shares: List<RvWaffleShare>, spoken: String, modifier: Modifier = Modifier) {
    val cells = remember(shares) { rvWaffleCells(shares.map { it.count }) }
    val track = Rv.colors.track
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(RvSpace.barGap)) {
        Canvas(
            Modifier.align(Alignment.CenterHorizontally).widthIn(max = RvWaffleSize.width).fillMaxWidth().aspectRatio(1f),
            contentDescription = spoken,
        ) {
            val gap = RvSpace.tight.toPx()
            val cell = (size.width - gap * (RvWaffleSize.SIDE - 1)) / RvWaffleSize.SIDE
            var i = 0
            shares.forEachIndexed { s, share ->
                repeat(cells[s]) {
                    drawRect(share.color, Offset(i % RvWaffleSize.SIDE * (cell + gap), i / RvWaffleSize.SIDE * (cell + gap)), Size(cell, cell))
                    i++
                }
            }
            // Nothing to share: the hundred cells are the empty track.
            while (i < RvWaffleSize.SIDE * RvWaffleSize.SIDE) {
                drawRect(track, Offset(i % RvWaffleSize.SIDE * (cell + gap), i / RvWaffleSize.SIDE * (cell + gap)), Size(cell, cell))
                i++
            }
        }
        if (shares.any { it.text != null }) {
            Column(verticalArrangement = Arrangement.spacedBy(RvSpace.tight)) {
                shares.forEach { RvLegendItem(it.color, it.label, Modifier.fillMaxWidth(), Rv.colors.text, it.text) }
            }
        } else {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(RvUsageSize.legendItemGap), verticalArrangement = Arrangement.spacedBy(RvSpace.tight)) {
                shares.forEach { RvLegendItem(it.color, it.label) }
            }
        }
    }
}

/**
 * Whole cells of a hundred for each count, by largest remainder so they always add up; a count above zero is never drawn
 * as none, its cell taken from the largest share (doona web's `waffleCells`).
 */
internal fun rvWaffleCells(counts: List<Int>): IntArray {
    val cells = RvWaffleSize.SIDE * RvWaffleSize.SIDE
    val total = counts.sum()
    if (total <= 0) return IntArray(counts.size)
    val exact = counts.map { it.toDouble() / total * cells }
    val whole = IntArray(counts.size) { exact[it].toInt() }
    var left = cells - whole.sum()
    for (i in counts.indices.sortedWith(compareByDescending<Int> { exact[it] - whole[it] }.thenBy { it })) {
        if (left == 0) break
        whole[i]++
        left--
    }
    for (i in counts.indices) {
        if (counts[i] > 0 && whole[i] == 0) {
            whole[whole.indices.maxBy { whole[it] }]--
            whole[i] = 1
        }
    }
    return whole
}

/** The waffle: ten cells a side, the grid at most as wide as the web's on a phone (267 px). */
internal object RvWaffleSize {
    const val SIDE = 10
    val width = 267.dp
}
