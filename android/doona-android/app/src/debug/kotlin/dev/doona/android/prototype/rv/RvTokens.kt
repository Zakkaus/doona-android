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

    /** Between stacked cards (measured §2: 16 dp). */
    val cardGap = 16.dp

    /** Start and end padding of a row inside a card, and of the chart card (measured §7: 16 dp). */
    val rowPadding = 16.dp

    /** From a row's leading visual to its text (measured §7: 16 dp). */
    val rowGap = 16.dp
    val trailingGap = 12.dp

    /** From a round action's disc to its label (measured §6: 8 dp). */
    val discToLabel = 8.dp

    /**
     * From the status bar to the hero's caption: the top bar's row (12 + 44 dp), then 96 dp, as Revolut sets its caption
     * about 100 dp under the bar; the map's marker sits in that space, clear of the bar's glass.
     */
    val headerTop = 152.dp
    val heroToActions = 64.dp

    /** From the round actions' labels to the first card (measured §5: the action row ends 1360 px, the card starts 1400). */
    val actionsToCard = 16.dp
    val subGap = 6.dp
    val tight = 4.dp

    /** Between two tiles side by side: Revolut's crypto tiles sit 16 dp apart (measured §10.2). */
    val tileGap = 16.dp

    /** From the detail page's segmented control to its hero, and from its figures to its chart. */
    val chartGap = 24.dp

    /** From the detail chart to its range chips. */
    val chipGap = 12.dp

    /** An outside section title: this far under what is above it, and this far above its card (measured §13.4). */
    val sectionAbove = 24.dp
    val sectionBelow = 12.dp

    /** A card's in-card header row sits this far under the card's top, so the card's padding is the same at both ends. */
    val headerInset = 4.dp

    /**
     * The page header's rhythm ([RvPageHeader]; spacing audit 2026-11-23, after Revolut's Analytics page): a caption sits
     * this far under the large title's line box, as Revolut's New features page sets its description under the title.
     */
    val captionGap = 4.dp

    /**
     * From the title or its caption to the first card or section title, which then adds nothing above itself: the gap
     * between cards (spacing audit row 2; Revolut's 設定 puts its first card 79 dp under the bar's controls, 07-settings-a
     * (41,494): the title 24 dp under them, its 38 dp line, then 16).
     */
    val headerGap = 16.dp

    /**
     * Round a control row on the page (a page's filter chips, a status chip): from the title block to it, and from it to
     * the first card (measured Analytics, 03-analytics-a: the chips at 202-242 dp, the first tile at 266, 87 dp under the
     * bar's controls).
     */
    val controlGap = 24.dp

    /** Between two control rows stacked in one (a node list's search, then its order), as a section title to its card. */
    val controlStack = 12.dp

    /** From a page's mark (a node's flag) to the large title under it. */
    val markToTitle = 12.dp

    /** In a control card ([RvControlCard]), from the control to the caption under it. */
    val controlToCaption = 12.dp

    /** Between a card's lines of text and its progress bar. */
    val barGap = 14.dp
}

internal object RvSize {
    /** A round action's disc (measured §6: 48 dp). */
    val actionDisc = 48.dp

    /** Material Symbols draw in a 20 dp live area of their 24 dp box, the width of #11's '+'. */
    val actionGlyph = 24.dp

    /** A row's leading visual, disc or flag, and the glyph in a disc: about 18 dp of ink (measured §7, §12.2). */
    val rowDisc = 40.dp
    val rowGlyph = 22.dp

    /** A row with a subtitle, and one without (measured §7: 82 and 58 dp). */
    val rowTwoLine = 82.dp
    val rowOneLine = 58.dp
    val inlineGlyph = 16.dp
    val dot = 8.dp

    /** Every card's corners, 設定's and 主題's included (measured §2: 20 dp, N-06). */
    val cardRadius = 20.dp

    /** A list card's 'See all' row (S7-12, Revolut's 56 dp). */
    val seeAll = 56.dp
    val touch = 48.dp
    val navGlyph = 24.dp
    val progress = 5.dp

    /** The current node's check (measured §7 `avatar_badgeBottom`): a 16 dp disc, its ring in the card's colour. */
    val badge = 16.dp
    val badgeRing = 2.dp
    val badgeCheck = 1.5.dp
    val badgeOverhang = 2.dp
}

/** The one chevron (measured §7): 6.7 x 12.2 dp of 2 dp stroke in a 24 dp box. */
internal object RvChevronSize {
    val box = 24.dp
    val width = 6.7.dp
    val height = 12.2.dp
    val stroke = 2.dp
}

/** A card's in-card header (measured §7 `listSubheader`): a 48 dp row. */
internal object RvHeaderSize {
    val row = 48.dp
}

/** The activity header's dot map, after #07 and #08, whose dots sit 7 px (5.3 dp) apart and 2 px (1.5 dp) across. */
internal object RvDots {
    val pitch = 5.5.dp
    val dot = 2.dp

    /**
     * The selected node's place: centred, this far under the status bar, so its glow (26 dp) ends 14 dp under the top
     * bar's controls (12 + 44 dp) and 30 dp above the caption ([RvSpace.headerTop]).
     */
    val focusTop = 96.dp
    val marker = 6.dp
    val ring = 11.dp
    val ringStroke = 1.5.dp
    val glow = 26.dp

    /** Under the status bar, which has no dots, so the clock never sits on them (S5-03), the dots fade in over this. */
    val topFade = 24.dp

    /** Round the hero's text, this far out, the dots are at most [CLEAR_ALPHA] (S5-02). */
    val clearPad = 8.dp
    const val CLEAR_ALPHA = 0.12f

    const val ALPHA = 0.62f
    const val RING_ALPHA = 0.55f
    const val GLOW_ALPHA = 0.28f

    /** Dots keep their strength down to this share of the header's height, then fade out by [FADE_END]. */
    const val FADE_START = 0.45f
    const val FADE_END = 0.86f
}

/** Revolut's charts, from the owner's 2026 screenshots: the crypto home tiles and a stock's full-bleed chart. */
internal object RvChartSize {
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
     * The one value pill (measured §10.1: 28 dp tall, 8 dp round, 15 sp, a soft shadow), on a chart [pillInset] in from
     * its edge, and [pillClear] off the line it labels.
     */
    val pill = 28.dp
    val pillPadH = 8.dp
    val pillRadius = 8.dp
    val pillShadow = 2.dp
    val pillInset = 8.dp
    val pillClear = 6.dp

    /** A range chip under the chart (only the selected one filled and raised), and the round buttons after the chips. */
    val range = 36.dp
    val rangePad = 12.dp
    val rangeGap = 4.dp
    val rangeShadow = 2.dp

    /** The chart's tool discs (measured §8: 35 dp, 8 dp apart, ending on the 16 dp margin). */
    val roundButton = 35.dp
    val roundGap = 8.dp
    val roundGlyph = 20.dp

    /** The segmented control (measured §8): a 36 dp pill track, the selected segment raised 2 dp inside it. */
    val segment = 36.dp
    val segmentInset = 2.dp

    /** The tile's curve keeps 10 % of its height free above the peak; the detail chart's axis runs to peak × 1.15. */
    const val SPARK_HEADROOM = 1.1f
    const val DETAIL_HEADROOM = 1.15f

    /** The detail chart's hairline grid (measured §10.1): a row every 24.7 dp and a column every 47 dp. */
    val gridColumn = 47.dp
    val gridRow = 24.7.dp
}

internal object RvAlpha {
    /** The state layer: the content colour over the visible shape while pressed. */
    const val PRESSED = 0.1f

    /** Every chart's hairline grid, in the text colour: about 1.2:1 against the card in both themes (L-11). */
    const val GRID_LIGHT = 0.09f
    const val GRID_DARK = 0.09f

    /** A tile's fill under its sparkline: neutral, the text colour, fading to nothing at the bottom. */
    const val TILE_FILL = 0.08f

    /** The detail chart's fill under its line: the series colour at the line, linear to nothing at the plot's bottom. */
    const val AREA_LIGHT = 0.13f
    const val AREA_DARK = 0.22f

    /** 今日用量's stacked bands: both directions at this one alpha, so only the hue tells them apart (S5-13). */
    const val BAND_LIGHT = 0.3f
    const val BAND_DARK = 0.36f

    /** A tile's corner disc: its series' line colour at this alpha over the card, the same tone for both (S5-11). */
    const val SERIES_DISC = 0.16f

    /** A latency's chip: its hue at this alpha over the card. */
    const val STATUS_CHIP = 0.14f

    /** A value pill's soft shadow in light: two steps of black under it. */
    const val PILL_SHADOW_NEAR = 0.08f
    const val PILL_SHADOW_FAR = 0.04f

    /** The floating call to action's soft shadow in light (S7-16). */
    const val CTA_SHADOW = 0.2f

    /** The segmented control's track: the text colour at 6 % in light, white at 8 % in dark (S7-02, T02). */
    const val SEGMENT_LIGHT = 0.06f
    const val SEGMENT_DARK = 0.08f

    /** The ghost bars of a tile with no traffic, in the text colour. */
    const val GHOST = 0.05f

    /** The soft halo round the live point. */
    const val HALO = 0.22f

    /** A range the build does not offer yet. */
    const val DISABLED = 0.38f

    /** Glass tints: the top bar's controls over the blur in light, dense enough that a title under them stays a blur (L-08), and any glass's solid fill. */
    const val GLASS_SOLID = 0.94f
    const val CONTROL_GLASS = 0.85f

    /** A raised segment or chip's 1 dp rim in dark, so it reads on a card's track (L-07). */
    const val RAISED_RIM = 0.12f

    /** The strip behind a bar: the page at 85 % over the blur (S5-24). */
    const val BAR_GLASS = 0.85f

    /**
     * The capsule's solid fill in dark: white 11 % over the page, with a rim of white 8 % (S5-20, item 8). Over the blur
     * its fill starts from Revolut's 65 % (measured §9) and rises until its labels read over any row (item 22).
     */
    const val NAV_DARK = 0.11f
    const val NAV_RIM = 0.08f
    const val NAV_GLASS_MIN = 0.65f

    /** The selected pill: the capsule's solid fill with the text colour at 8 % on it, flat and opaque (S5-19). */
    const val NAV_PILL = 0.08f

    /** The back disc's ring in light (measured §4: about 1 px of #f2f2f2 round #fcfcfc) and a flag's ring. */
    const val BACK_RING = 0.06f
    const val FLAG_RING_LIGHT = 0.08f
    const val FLAG_RING_DARK = 0.1f
}

/**
 * The chrome of every page, after Revolut's measured top bar (§4) and floating bottom bar (§9): a row of 44 dp controls
 * 12 dp under the status bar (活動's duck, search pill and discs; a pushed page's back disc and title), a strip of blurred
 * page behind the row once content scrolls under it, and a glass capsule 16 dp in from the sides whose bottom sits 40 dp
 * above the screen's edge, 60 dp tall, that shrinks to icons only, 336 x 52 dp, while the content scrolls up.
 */
internal object RvChrome {
    /** The controls' row: its top this far under the status bar, every control a 44 dp disc or pill in a 48 dp target. */
    val barTop = 12.dp
    val control = 44.dp
    val controlGlyph = 24.dp
    val touchPad = (RvSize.touch - control) / 2

    /** The strip behind the row: solid to the controls' bottom plus [stripBelow], then fading out over [stripFade]. */
    val stripBelow = 8.dp
    val stripFade = 12.dp

    /** The strip is fully in once the content has scrolled this far; a large title hands over to the bar's over this. */
    val shadeAfter = 48.dp
    val collapse = 32.dp
    val markGlyph = 28.dp

    /** From the duck to the search pill and from the pill to the buttons; between the two buttons. */
    val controlGap = 10.dp
    val buttonGap = 8.dp

    /** The search pill's magnifier, inset from its start, and the gap to the placeholder. */
    val searchGlyph = 24.dp
    val searchInset = 8.dp

    /** A pushed page's compact title starts this far from the screen's edge: after the back disc and a gutter. */
    val titleStart = 76.dp

    /**
     * A page's large title, or the mark above it, this far under the bar's controls ([RvPageHeader] then sets what follows):
     * Revolut's 設定 card starts 79 dp under the controls (§2, 07-settings-a), which is this, the title's line and 16 dp.
     */
    val largeTitleTop = 24.dp

    val navInset = 16.dp
    val navBottom = 40.dp

    /**
     * From the last card to the capsule at a page's end, the same on every page with the capsule: Revolut's measures give
     * none, so it is 活動's, as the round-2 audits measured it (S02, S-01: 28 dp).
     */
    val navGap = 28.dp

    /** The capsule's bottom stays this far above a taller system bar, so it never sits on the gesture handle. */
    val navAboveInset = 16.dp
    val navHeight = 60.dp
    val navCollapsedHeight = 52.dp
    val navCollapsedWidth = 336.dp

    /** The selected item's inner pill, inset this far from the capsule's edges: 52 dp tall in the 60 dp capsule. */
    val navPillInset = 4.dp
    val navGlyph = 24.dp
    val navCollapsedGlyph = 20.dp
    val navLabelGap = 2.dp

    /** The label's line: 15 sp semibold whole, so glyph, gap and label (44 dp) sit inside the 52 dp pill. */
    val navLabel = 18.dp
    val navRim = 1.dp

    /**
     * The glass blur's radius, and how far past a glass surface's edges the blur reads the content, so its edges blur
     * what lies there; then the shadows under the capsule and the top bar's controls (light only).
     */
    val blur = 20.dp
    val blurReach = 40.dp
    val navShadow = 12.dp
    val navShadowDrop = 4.dp
    val controlShadow = 6.dp
    val controlShadowDrop = 1.dp

    /** The ring round a flag, so white fields keep their edge on any surface, and round the back disc in light. */
    val ring = 1.dp

    const val NAV_SHADOW_ALPHA = 0.16f
    const val CONTROL_SHADOW_ALPHA = 0.06f
    const val COLLAPSE_MS = 220
}

/**
 * The 今日用量 card, after Revolut's Analytics 'Spent' tile (measured §10.2: 384 x 188 dp): the text at the start,
 * the stacked steps in the right half above the legend, and a hairline grid every 4 hours and in four rows.
 */
internal object RvUsageSize {
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
internal object RvHomeSize {
    val headerPad = 16.dp
    val headerChevron = 20.dp

    /** The filled triangle before a change, as Revolut's delta (measured §7: 8 dp). */
    val delta = 8.dp
    val deltaGap = 4.dp
}

/** One step of the type scale: size and line height in sp, weight, display or text cut. */
internal class RvTypeStep(val size: Int, val line: Int, val weight: FontWeight, val display: Boolean = false)

internal object RvTypeScale {
    /** 活動's hero (measured §5): the integer 44 sp, its decimals and unit 32 sp, one weight, one baseline. */
    val hero = RvTypeStep(44, 53, FontWeight.Bold, display = true)
    val heroSmall = RvTypeStep(32, 40, FontWeight.Bold, display = true)

    /** A detail page's figure (measured §10.1, the coin header): 33 sp with its decimals, the unit 22 sp. */
    val detailFigure = RvTypeStep(33, 40, FontWeight.Bold, display = true)
    val detailUnit = RvTypeStep(22, 28, FontWeight.SemiBold, display = true)

    /** The hero's caption (measured §5: 17 sp medium), and the line under a figure (17 sp). */
    val caption = RvTypeStep(17, 24, FontWeight.Medium)
    val sub = RvTypeStep(17, 24, FontWeight.Normal)

    /** A row's title and subtitle (measured §7: 19 sp semibold, 17 sp regular). */
    val rowTitle = RvTypeStep(19, 26, FontWeight.SemiBold)
    val rowSub = RvTypeStep(17, 24, FontWeight.Normal)

    /** A round action's label and a range chip (measured §6, §8: 17 sp semibold); a segment (19 sp semibold). */
    val action = RvTypeStep(17, 24, FontWeight.SemiBold)
    val segment = RvTypeStep(19, 26, FontWeight.SemiBold)
    val tileCaption = RvTypeStep(13, 18, FontWeight.Normal)
    val tileValue = RvTypeStep(24, 30, FontWeight.Bold, display = true)
    val tileUnit = RvTypeStep(15, 20, FontWeight.SemiBold, display = true)

    /** A chart card's figure, after Revolut's Analytics page: the tile's figure when there is no traffic. */
    val cardValue = RvTypeStep(28, 34, FontWeight.Bold, display = true)
    val cardUnit = RvTypeStep(17, 22, FontWeight.SemiBold, display = true)

    /** The one value pill (measured §10.1: 15 sp semibold). */
    val pill = RvTypeStep(15, 20, FontWeight.SemiBold)

    /** An outside section title (measured §7: 20 sp bold). */
    val section = RvTypeStep(20, 26, FontWeight.Bold, display = true)

    /** A stat row (measured §11): the label 19 sp medium in secondary ink, the value 19 sp regular. */
    val statLabel = RvTypeStep(19, 26, FontWeight.Medium)
    val stat = RvTypeStep(19, 26, FontWeight.Normal)
    val navLabel = RvTypeStep(15, 18, FontWeight.SemiBold)

    /** A pushed page's large title, and the compact one it collapses to beside the back disc (measured §4). */
    val largeTitle = RvTypeStep(32, 38, FontWeight.Bold, display = true)
    val barTitle = RvTypeStep(20, 26, FontWeight.SemiBold, display = true)

    /** The two letters on a flag's fallback disc. */
    val flagCode = RvTypeStep(13, 16, FontWeight.SemiBold)

    /** The search pill's placeholder (measured §4: 17 sp) and a card's in-card header (measured §7: 19 sp medium). */
    val search = RvTypeStep(17, 22, FontWeight.Normal)
    val cardTitle = RvTypeStep(19, 26, FontWeight.Medium)

    /** A list card's 'See all' row (measured §7: centred, 19 sp bold). */
    val seeAll = RvTypeStep(19, 26, FontWeight.Bold)
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
