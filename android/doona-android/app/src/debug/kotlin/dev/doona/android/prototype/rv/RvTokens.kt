package dev.doona.android.prototype.rv

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.doona.android.R

// Raw values of the Revolut sample, measured from the store shots at a 412 dp screen (~/code/doona-v1/rv-spec.md, with
// the shot each one comes from). Only RvTheme.kt and the components read them; the screen reads the roles.

internal object RvSpace {
    val gutter = 16.dp
    val cardGap = 18.dp

    /** Start and end padding of a row inside a card, and of the chart card. */
    val rowPadding = 18.dp

    /** From a row's icon disc to its text. */
    val rowGap = 18.dp
    val trailingGap = 12.dp
    val discToLabel = 10.dp
    val headerTop = 72.dp
    val heroToActions = 64.dp
    val actionsToCard = 32.dp
    val subGap = 6.dp
    val tight = 4.dp

    /** Between two tiles side by side, as between two cards. */
    val tileGap = 18.dp

    /** From a tile's text to its sparkline. */
    val sparkGap = 14.dp

    /** From the detail page's segmented control to its hero, and from its figures to its chart. */
    val chartGap = 24.dp

    /** From the detail chart to its range chips. */
    val chipGap = 12.dp

    /** From the range chips to the next section, and from a section's heading to its card. */
    val sectionGap = 32.dp
    val headingGap = 12.dp

    /** From a screen's title block to its first card. */
    val titleToCard = 24.dp

    /** Between a card's lines of text and its progress bar. */
    val barGap = 14.dp
}

internal object RvSize {
    val actionDisc = 52.dp

    /** Material Symbols draw in a 20 dp live area of their 24 dp box, the width of #11's '+'. */
    val actionGlyph = 24.dp
    val rowDisc = 44.dp
    val rowGlyph = 20.dp
    val rowTwoLine = 84.dp
    val rowOneLine = 56.dp
    val inlineGlyph = 16.dp
    val chevron = 20.dp
    val dot = 8.dp
    val cardRadius = 18.dp
    val touch = 48.dp
    val topBar = 56.dp
    val navBar = 64.dp
    val navGlyph = 24.dp
    val progress = 5.dp

    /** The check on a selected node's flag, #13's badge: a disc in a ring of the card's colour. */
    val badge = 20.dp
    val badgeRing = 2.dp
    val badgeGlyph = 14.dp
    val badgeOverhang = 4.dp
}

/** The activity header's dot map, after #07 and #08, whose dots sit 7 px (5.3 dp) apart and 2 px (1.5 dp) across. */
internal object RvDots {
    val pitch = 5.5.dp
    val dot = 2.dp

    /** The selected node's place: centred, this far under the status bar, above the connection state. */
    val focusTop = 40.dp
    val marker = 6.dp
    val ring = 11.dp
    val ringStroke = 1.5.dp
    val glow = 26.dp

    const val ALPHA = 0.62f
    const val RING_ALPHA = 0.55f
    const val GLOW_ALPHA = 0.28f

    /** Dots keep their strength down to this share of the header's height, then fade out by [FADE_END]. */
    const val FADE_START = 0.45f
    const val FADE_END = 0.86f
}

/** Revolut's charts, from the owner's 2026 screenshots: the crypto home tiles and a stock's full-bleed chart. */
internal object RvChartSize {
    /** A tile's inner padding, its caption disc and the glyph in it. */
    val tilePad = 14.dp
    val tileDisc = 28.dp
    val tileGlyph = 16.dp

    /** A tile's sparkline, across the tile's inner width at its bottom. */
    val spark = 84.dp
    val sparkStroke = 2.dp

    /** The ghost bars of a tile with no traffic, after Revolut's empty 'Income' card: twelve across the tile. */
    val ghostBar = 5.dp
    const val GHOST_BARS = 12

    /** The detail chart: full width; its line ends on the gutter, so the live point and its halo stay whole. */
    val detail = 175.dp
    val detailStroke = 1.5.dp
    val detailEnd = 16.dp
    val liveDot = 6.dp
    val liveHalo = 14.dp

    /** While a finger is on the detail chart: a line down the chart and a dot on the curve, ringed in the page colour. */
    val scrubLine = 1.dp
    val scrubDot = 6.dp
    val scrubRing = 2.dp

    /** Dotted lines: round dots this wide, this far apart. */
    val dotted = 1.dp
    val dottedPitch = 4.dp

    /** A value pill on the chart, this far in from the chart's right edge. */
    val pillPadH = 8.dp
    val pillPadV = 3.dp
    val pillRadius = 6.dp
    val pillInset = 8.dp

    /** A range chip under the chart (the selected one raised), and the round buttons after the chips. */
    val range = 36.dp
    val rangePad = 12.dp
    val rangeGap = 4.dp
    val rangeShadow = 2.dp
    val roundButton = 40.dp
    val roundGlyph = 20.dp

    /** The segmented control: a pill track, the selected segment raised inside it. */
    val segment = 40.dp
    val segmentInset = 2.dp

    /** The tile's curve keeps 10 % of its height free above the peak; the detail chart's axis runs to peak × 1.15. */
    const val SPARK_HEADROOM = 1.1f
    const val DETAIL_HEADROOM = 1.15f

    /** The detail chart's hairline grid: a vertical line every 43 dp from the start, and four rows. */
    val gridColumn = 43.dp
    const val GRID_ROWS = 4

    /** The detail fill is gone this far down from the peak's height to the chart's bottom. */
    const val FILL_END = 0.7f
}

internal object RvAlpha {
    /** The state layer: the content colour over the visible shape while pressed. */
    const val PRESSED = 0.1f

    /** The detail chart's hairline grid, in the text colour: about 6 % in light, almost invisible in dark. */
    const val GRID_LIGHT = 0.06f
    const val GRID_DARK = 0.025f

    /** A tile's fill under its sparkline: neutral, the text colour, fading to nothing at the bottom. */
    const val TILE_FILL = 0.08f

    /** The start and low pills sit over the line, so the line shows through them. */
    const val PILL_OVER_LINE = 0.8f

    /** The detail chart's fill under its line, the series colour, fading out well above the bottom. */
    const val AREA_LIGHT = 0.16f
    const val AREA_DARK = 0.22f

    /** The ghost bars of a tile with no traffic, in the text colour. */
    const val GHOST = 0.05f

    /** The soft halo round the live point. */
    const val HALO = 0.22f

    /** A range the build does not offer yet. */
    const val DISABLED = 0.38f
}

/** One step of the type scale: size and line height in sp, weight, display or text cut. */
internal class RvTypeStep(val size: Int, val line: Int, val weight: FontWeight, val display: Boolean = false)

internal object RvTypeScale {
    val hero = RvTypeStep(42, 48, FontWeight.Bold, display = true)
    val heroSmall = RvTypeStep(24, 32, FontWeight.SemiBold, display = true)
    val caption = RvTypeStep(16, 22, FontWeight.Normal)
    val sub = RvTypeStep(15, 20, FontWeight.Medium)
    val rowTitle = RvTypeStep(17, 24, FontWeight.Medium)
    val rowSub = RvTypeStep(15, 20, FontWeight.Normal)
    val action = RvTypeStep(15, 20, FontWeight.Medium)
    val tileCaption = RvTypeStep(13, 18, FontWeight.Normal)
    val tileValue = RvTypeStep(24, 30, FontWeight.Bold, display = true)
    val tileUnit = RvTypeStep(15, 20, FontWeight.SemiBold, display = true)

    /** A chart card's figure, after Revolut's Analytics page: the tile's figure when there is no traffic. */
    val cardValue = RvTypeStep(28, 34, FontWeight.Bold, display = true)
    val cardUnit = RvTypeStep(17, 22, FontWeight.SemiBold, display = true)
    val pill = RvTypeStep(12, 16, FontWeight.Medium)
    val section = RvTypeStep(20, 26, FontWeight.Bold, display = true)
    val stat = RvTypeStep(16, 22, FontWeight.Medium)
    val title = RvTypeStep(34, 40, FontWeight.Bold, display = true)
    val navLabel = RvTypeStep(12, 16, FontWeight.Medium)
    const val TABULAR = "tnum"
}

/** Inter 4 at the text optical size (opsz 14), Latin only (rv-fonts/make.py). */
internal val RvInter = FontFamily(
    Font(R.font.rv_inter_w400, FontWeight.Normal),
    Font(R.font.rv_inter_w500, FontWeight.Medium),
    Font(R.font.rv_inter_w600, FontWeight.SemiBold),
)

/** Inter 4 at the display optical size (opsz 32), for the hero number. */
internal val RvInterDisplay = FontFamily(
    Font(R.font.rv_inter_display_w600, FontWeight.SemiBold),
    Font(R.font.rv_inter_display_w700, FontWeight.Bold),
)

/** The app's Noto Sans TC subset, for every run of CJK text. */
internal val RvNoto = FontFamily(
    Font(R.font.noto_sans_tc_w400, FontWeight.Normal),
    Font(R.font.noto_sans_tc_w500, FontWeight.Medium),
    Font(R.font.noto_sans_tc_w600, FontWeight.SemiBold),
    Font(R.font.noto_sans_tc_w700, FontWeight.Bold),
)
