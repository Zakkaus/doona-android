package dev.doona.android.prototype.rv.stats

import androidx.compose.ui.unit.dp

// Raw values of the 統計 sample, after Revolut's Analytics page as measured on Android (plans/revolut-android-measured.md,
// sections 2, 8 and 10.2) and the iOS study's §5 (plans/revolut-study.md). The page reads them through its components.

internal object RvStatsSpace {
    /** A card's inner padding, Revolut's 16 dp. */
    val pad = 16.dp

    /**
     * Between two paged tiles, and how much of the next tile shows at the screen's edge (measured §2, 03-analytics-a:
     * 188 dp tiles 8 dp apart, the neighbour peeking 4 dp), so the row reads as one that scrolls.
     */
    val tileGap = 8.dp
    val tilePeek = 4.dp

    /** From a card's figure block to its chart, and between a legend's lines. */
    val chartGap = 16.dp
    val legendGap = 10.dp

    /** Under the paged tiles, to their dots. */
    val dotsGap = 10.dp
}

internal object RvStatsSize {
    /** The 已用流量 card, Revolut's 'Spent' (384 x 188 dp at 423 dp): its chart takes the right part of it. */
    val spent = 188.dp
    const val SPENT_CHART_SHARE = 0.56f

    /** The empty grid's rows. */
    const val GRID_ROWS = 6

    /** A paged tile, Revolut's half tile, and its bar chart at the bottom (132 x 54 dp). */
    val tile = 188.dp
    val bars = 54.dp
    const val BAR_SHARE = 0.56f
    val barStub = 3.dp

    /** The delta's filled triangle, Revolut's 8 dp. */
    val delta = 8.dp

    /** The pager's dots: the current one larger and darker. */
    val dot = 8.dp
    val dotIdle = 5.dp
    val dotPitch = 12.dp

    /** The quota ring, Revolut's 'Total assets' (151 dp, 8 dp stroke), the card-coloured gap between its parts (S05), and its legend's rounded square. */
    val ring = 132.dp
    val ringStroke = 8.dp
    val ringGap = 2.dp
    val legendSquare = 9.dp
    val legendRadius = 2.dp

    /**
     * The latency dot chart, after Revolut's EPS chart, in node detail's grammar: dots small enough to keep a gap at a
     * 5.6 dp pitch (47 nodes), the current node's dot a step larger and its column, which stops 4 dp under the plot and
     * 4 dp above its label chip (S04).
     */
    val latency = 156.dp
    val latencyDot = 3.5.dp
    val latencyDotSelected = 5.5.dp
    val column = 10.dp
    val columnBelow = 4.dp
    val labelChipPadH = 8.dp
    val labelChipPadV = 3.dp
    val labelChipGap = 8.dp
    val axisLabelGap = 8.dp

    /** Revolut's analyst bars: 8 dp rounded bars on a track, the figure after them. */
    val ratingBar = 8.dp
    val ratingRow = 28.dp
    val ratingLabel = 72.dp
}

internal object RvStatsAlpha {
    /**
     * A subscription's unused share of its ring segment: its colour this far toward the card, a lighter step of it. With
     * ink and the control grey as the two colours, 0.75 spaces the four greys evenly: ink's rest against the grey used arc
     * next to it is 1.89:1 light and 1.65:1 dark; at 0.6 it matched that grey (S-05).
     */
    const val RING_REST = 0.75f

    /** The analyst bar's gradient starts this light. */
    const val RATING_START = 0.55f
}
