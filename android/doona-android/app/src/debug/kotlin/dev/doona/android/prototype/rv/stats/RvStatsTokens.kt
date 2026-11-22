package dev.doona.android.prototype.rv.stats

import androidx.compose.ui.unit.dp

// Raw values of the 統計 sample, after Revolut's Analytics page as measured on Android (plans/revolut-android-measured.md,
// sections 2, 8 and 10.2) and the iOS study's §5 (plans/revolut-study.md). The page reads them through its components.

internal object RvStatsSpace {
    /** A card's inner padding, Revolut's 16 dp. */
    val pad = 16.dp

    /** From the chips to the first card. */
    val chipsGap = 16.dp

    /** Between two paged tiles, as between any side-by-side cards (Addendum A: 16 dp). */
    val tileGap = 16.dp

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
    val stepStroke = 1.5.dp
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

    /** The quota ring, Revolut's 'Total assets' (151 dp, 8 dp stroke), and its legend's rounded square. */
    val ring = 132.dp
    val ringStroke = 8.dp
    val ringGap = 3.dp
    val legendSquare = 9.dp
    val legendRadius = 2.dp

    /** The latency dot chart, after Revolut's EPS chart: dots, the current node's column and its label chip. */
    val latency = 156.dp
    val latencyDot = 6.dp
    val latencyDotSelected = 10.dp
    val column = 16.dp
    val labelChipPadH = 8.dp
    val labelChipPadV = 3.dp
    val labelChipGap = 6.dp
    val axisLabelGap = 8.dp

    /** Revolut's analyst bars: 8 dp rounded bars on a track, the figure after them. */
    val ratingBar = 8.dp
    val ratingRow = 28.dp
    val ratingLabel = 72.dp
}

internal object RvStatsAlpha {
    /** A subscription's unused share of its ring segment, in the segment's colour. */
    const val RING_REST = 0.24f

    /** The analyst bar's gradient starts this light. */
    const val RATING_START = 0.55f

    /** The stepped area's line, in the secondary text colour. */
    const val STEP_LINE = 0.7f
}
