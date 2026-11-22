package dev.doona.android.designsystem.tokens

import androidx.compose.ui.unit.dp

/** Revolut's charts, from the owner's 2026 screenshots: the crypto home tiles and a stock's full-bleed chart. */
object ChartScale {
    /** A square tile's inner padding, its 20 dp corner disc and the glyph in it (measured §10.2). */
    val tilePad = 16.dp
    val tileDisc = 20.dp
    val tileGlyph = 14.dp

    /** A tile's sparkline, across the tile's inner width at its bottom: Revolut's crypto tile plots 148 x 60 dp. */
    val spark = 60.dp
    val sparkStroke = 2.dp

    /** The ghost bars of a tile with no traffic, after Revolut's empty 'Income' card: twelve across the tile. */
    val ghostBar = 5.dp
    const val GHOST_BARS = 12

    /** The detail chart: full width, about 190 dp (measured §10.1); its line ends on the gutter, so the live point stays whole. */
    val detail = 190.dp
    val detailStroke = 1.5.dp
    val detailEnd = 16.dp

    /** The end of a series: a 5 dp dot in the line's colour on a lighter halo (measured §10.1). */
    val liveDot = 5.dp
    val liveHalo = 12.dp

    /** While a finger is on the detail chart: a line down the chart and a dot on the curve, ringed in the page colour. */
    val scrubLine = 1.dp
    val scrubDot = 6.dp
    val scrubRing = 2.dp

    /** Dotted lines: round dots this wide, this far apart (measured §10.1: 1 dp at a 6 dp pitch). */
    val dotted = 1.dp
    val dottedPitch = 6.dp

    /**
     * The one value pill (measured §10.1: 28 dp tall, 8 dp round, 15 sp, a soft shadow; S5-26, T04), on a chart
     * [pillInset] in from its edge, and [pillClear] off the line it labels.
     */
    val pill = 28.dp
    val pillPadH = 8.dp
    val pillRadius = 8.dp
    val pillShadow = 2.dp
    val pillInset = 8.dp
    val pillClear = 6.dp

    /** A range chip under the chart (only the selected one filled and raised; S5-28, T07), and the round buttons after the chips. */
    val range = 36.dp
    val rangePad = 12.dp
    val rangeGap = 4.dp
    val rangeShadow = 2.dp

    /** The chart's tool discs (measured §8: 35 dp, 8 dp apart, ending on the 16 dp margin; T08). */
    val roundButton = 35.dp
    val roundGap = 8.dp
    val roundGlyph = 20.dp

    /** The segmented control (measured §8; S7-02, T02, N10): a 36 dp pill track, the selected segment raised 2 dp inside it. */
    val segment = 36.dp
    val segmentInset = 2.dp

    /** The tile's curve keeps 10 % of its height free above the peak; the detail chart's axis runs to peak × 1.15. */
    const val SPARK_HEADROOM = 1.1f
    const val DETAIL_HEADROOM = 1.15f

    /** The detail chart's hairline grid (measured §10.1): a row every 24.7 dp and a column every 47 dp. */
    val gridColumn = 47.dp
    val gridRow = 24.7.dp

    // U1's sparkline (§7, Charts), kept while the old metric tiles draw it.
    const val FILL_TOP = 0.35f
    const val FILL_BOTTOM = 0.03f
    const val FILL_OPACITY = 0.6f
    const val DOMAIN_LOW = 0.85f
    const val DOMAIN_HIGH = 1.05f
}

/**
 * The 今日用量 card, after Revolut's Analytics 'Spent' tile (measured §10.2: 384 x 188 dp): the text at the start,
 * the stacked steps in the right half above the legend, and a hairline grid every 4 hours and in four rows.
 */
object UsageScale {
    val card = 188.dp
    val pad = 16.dp
    val legendSquare = 9.dp
    val legendRadius = 2.dp
    val legendGap = 6.dp
    val legendItemGap = 16.dp

    /** The chart stops this far above the legend's row. */
    val chartAboveLegend = 10.dp
    val stroke = 1.5.dp
    const val HOURS = 24
    const val GRID_HOURS = 4
    const val GRID_ROWS = 4

    /** The total sits at 80 % of the chart's height, so the day has room to grow. */
    const val HEADROOM = 1.25f
}

/** A home card of Revolut's: its own grey header with a chevron, its figure and its rows (study §3). */
object HomeScale {
    val headerPad = 16.dp
    val headerChevron = 20.dp

    /** The filled triangle before a change, as Revolut's delta (measured §7: 8 dp). */
    val delta = 8.dp
    val deltaGap = 4.dp
}

/** The activity header's dot map, after #07 and #08, whose dots sit 7 px (5.3 dp) apart and 2 px (1.5 dp) across. */
object DotMapScale {
    val pitch = 5.5.dp
    val dot = 2.dp

    /**
     * The selected node's place: centred, this far under the status bar, so its glow (26 dp) ends 14 dp under the top
     * bar's controls (12 + 44 dp) and 30 dp above the caption ([SpaceScale.headerTop]; S5-01).
     */
    val focusTop = 96.dp
    val marker = 6.dp
    val ring = 11.dp
    val ringStroke = 1.5.dp
    val glow = 26.dp

    /** Under the status bar, which has no dots, so the clock never sits on them (S5-03, S8-08), the dots fade in over this. */
    val topFade = 24.dp

    /** Round the hero's text, this far out, the dots are at most [CLEAR_ALPHA] (S5-02, S8-14). */
    val clearPad = 8.dp
    const val CLEAR_ALPHA = 0.12f

    const val ALPHA = 0.62f
    const val RING_ALPHA = 0.55f
    const val GLOW_ALPHA = 0.28f

    /** Dots keep their strength down to this share of the header's height, then fade out by [FADE_END]. */
    const val FADE_START = 0.45f
    const val FADE_END = 0.86f
}
