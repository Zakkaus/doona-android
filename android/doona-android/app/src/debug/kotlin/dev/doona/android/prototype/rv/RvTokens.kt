package dev.doona.android.prototype.rv

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

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

    /** A round action's label keeps this far in from its column's edges, so two labels shrunk to fit stay apart. */
    val actionLabelInset = 4.dp

    /**
     * From the status bar to the hero's caption: the top bar's row (6 + 44 dp), then 102 dp, as Revolut sets its caption
     * about 100 dp under the bar; the map's marker sits in that space, clear of the bar's glass.
     */
    val headerTop = 152.dp
    val heroToActions = 64.dp

    /**
     * Under a tab home's bar (策略, 連線), Revolut's rule (rv-fix-4e, 36 Android screens with the search bar): on a flat
     * page the first block sits [tabTop] under the bar's controls (Payments' banner 14.1 dp under them), and the blocks
     * under it sit [tabBlockGap] apart (a caption, a control row, the first card). 活動's hero keeps its own top
     * ([headerTop]) and puts its first card [tabBlockGap] under its round actions, as Revolut's first card sits 16 dp
     * under the last element of its hero.
     */
    val tabTop = 14.dp
    val tabBlockGap = 16.dp

    /** From the round actions' labels to the first card (measured §5: the action row ends 1360 px, the card starts 1400). */
    val actionsToCard = 16.dp
    val subGap = 6.dp
    val tight = 4.dp

    /**
     * Above a row's title and below its subtitle (Revolut's rows: 16.1 dp, `07-set-*.xml`, `01-home-b.xml`): with the
     * 26 dp title line and the 24 dp subtitle line directly under it, a row is 58 or 82 dp, and one whose text wraps grows
     * by its lines, as Revolut's rows grow to 105.5 and 129 dp.
     */
    val rowTextInset = 16.dp

    /** Between two tiles side by side: Revolut's crypto tiles sit 16 dp apart (measured §10.2). */
    val tileGap = 16.dp

    /** From the detail page's segmented control to its hero, and from its figures to its chart. */
    val chartGap = 24.dp

    /** An outside section title: this far under what is above it, and this far above its card (measured §13.4). */
    val sectionAbove = 24.dp
    val sectionBelow = 12.dp

    /** A footnote ([RvFootnote]) sits this far under its card, on the gutter as the section titles (spacing audit row 5). */
    val footnoteAbove = 8.dp

    /** A card's in-card header row sits this far under the card's top, so the card's padding is the same at both ends. */
    val headerInset = 4.dp

    /**
     * The page header's rhythm ([RvPageHeader]; spacing audit 2026-11-23, after Revolut's Analytics page): a caption sits
     * this far under the large title's line box, as Revolut's New features page sets its description under the title.
     */
    val captionGap = 4.dp

    /**
     * From the title or its caption to the first card or section title, which then adds nothing above itself: Revolut's
     * pushed pages put their first card 76.1 dp under the back disc (revolut-ux3 `st0.xml`: the disc ends at 117.6 dp, the
     * first row starts at 193.7), so the card starts 126.5 dp under the status bar here, where Revolut's starts 126.2. With
     * the title's 14 dp ([RvChrome.largeTitleTop]) and its 38 dp line box, that leaves 24.
     */
    val headerGap = 24.dp

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

    /** In an empty state ([RvEmptyState]), from its title to its line (revolut-ux3 §4: the subtitle 10 dp below). */
    val emptyLine = 10.dp

    /** In a notice card ([RvBanner]), from its text to its action's pill (revolut-ux3 §3: 16 dp). */
    val noticeToAction = 16.dp
}

internal object RvSize {
    /** A round action's disc (measured §6: 48 dp). */
    val actionDisc = 48.dp

    /** Material Symbols draw in a 20 dp live area of their 24 dp box, the width of #11's '+'. */
    val actionGlyph = 24.dp

    /** A row's leading visual, disc or flag, and the glyph in a disc: about 18 dp of ink (measured §7, §12.2). */
    val rowDisc = 40.dp
    val rowGlyph = 22.dp

    /** A notice card's glyph in its 40 dp disc (revolut-ux3 §3 and its 'For doona' notice: 24 dp), larger than a row's. */
    val noticeGlyph = 24.dp

    /** A detail page's header mark, over its name: node detail's flag and a connection's app disc, one size on both. */
    val headerDisc = 48.dp

    /** A row with a subtitle, and one without (measured §7: 82 and 58 dp). */
    val rowTwoLine = 82.dp
    val rowOneLine = 58.dp

    val inlineGlyph = 16.dp
    val dot = 8.dp

    /** The welcome page's duck: the size Revolut's New features card gives its sparkle (measured §13.3: 48 dp). */
    val emptyGlyph = 48.dp

    /**
     * The corner scale (revolut-ux3 §2), the only radii of the sample: every card, popup, tile and banner takes
     * [cardRadius]; a highlight inside a card [highlightRadius]; buttons, pills, chips, discs and the capsule are fully
     * round; a sheet takes [RvSheetSize.radius]. A small mark (a legend's square) has none.
     */
    val cardRadius = 20.dp

    /** A highlight or a picture inside a card: the card's 20 dp less Revolut's 4 dp inset (revolut-ux3 §2). */
    val highlightRadius = 16.dp

    /** A list card's 'See all' row (S7-12, Revolut's 56 dp). */
    val seeAll = 56.dp
    val touch = 48.dp
    val navGlyph = 24.dp
    val progress = 5.dp

    /** A busy bar ([RvBusy]) in a row's trailing slot or in a round action's disc: a glyph's width. */
    val busy = 24.dp

    /** The current node's check (measured §7 `avatar_badgeBottom`): a 16 dp disc, its ring in the card's colour. */
    val badge = 16.dp
    val badgeRing = 2.dp
    val badgeCheck = 1.5.dp
    val badgeOverhang = 2.dp
}

/**
 * The menu's geometry: Revolut's long-press popup (revolut-ux3 §1: 200 dp wide, 20 dp round, rows of 50 dp with a 24 dp
 * glyph at 16 dp and a 19 sp semibold label), held clear of the screen's edges.
 */
internal object RvMenuSize {
    val minWidth = 200.dp
    val maxWidth = 280.dp

    /** One item: Revolut's popup row, 50 dp, shorter than a list's 58 dp row. */
    val row = 50.dp

    /** An anchored menu opens this far under its anchor, its end on the anchor's end. */
    val anchorGap = 8.dp

    /** The menu keeps the gutter from the window's edges, and room round it for its shadow inside the popup's window. */
    val margin = RvSpace.gutter
    val shadowRoom = RvChrome.navShadow
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
     * The selected node's place: centred, this far under the status bar, so its glow (26 dp) ends 20 dp under the top
     * bar's controls (6 + 44 dp) and 30 dp above the caption ([RvSpace.headerTop]).
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
     * The one value pill (measured §10.1: 28 dp tall, 15 sp, a soft shadow; fully round, as every pill, revolut-ux3 §2),
     * on a chart [pillInset] in from its edge, and [pillClear] off the line it labels.
     */
    val pill = 28.dp
    val pillPadH = 8.dp
    val pillShadow = 2.dp
    val pillInset = 8.dp
    val pillClear = 6.dp

    /**
     * A latency chart's picked point ([rvChartMarker]): its column at its widest (node detail's, a picked year on
     * Revolut's EPS chart), how far the column reaches under the plot, and from the plot to the chip.
     */
    val markerColumn = 24.dp
    val markerBelow = 4.dp
    val markerGap = 8.dp

    /** A range chip under the chart (only the selected one filled and raised), and the round buttons after the chips. */
    val range = 36.dp
    val rangePad = 12.dp
    val rangeGap = 4.dp
    val rangeShadow = 2.dp

    /**
     * How far the selected chip's shadow shows under its 36 dp pill in light (proto-rv-policy-picker-light: the pill ends
     * at 299 dp, its shadow at 302.5), so a gap under a row of chips measures from what the eye sees, as under a flat chip.
     */
    val rangeShadowReach = 3.5.dp

    /** The chart's tool discs (measured §8: 35 dp, 8 dp apart, ending on the 16 dp margin). */
    val roundButton = 35.dp
    val roundGap = 8.dp
    val roundGlyph = 20.dp

    /**
     * The segmented control in the owner's look E (rv-fix-4e; slider study §3, after Revolut's 3-icon track and FlClash):
     * a 40 dp pill track, the selected segment raised 2 dp inside it, a 20 dp glyph 6 dp before a label that has one.
     */
    val segment = 40.dp
    val segmentInset = 2.dp
    val segmentGlyph = 20.dp
    val segmentGlyphGap = 6.dp

    /** The tile's curve keeps 10 % of its height free above the peak; the detail chart's axis runs to peak × 1.15. */
    const val SPARK_HEADROOM = 1.1f
    const val DETAIL_HEADROOM = 1.15f

    /** The detail chart's hairline grid (measured §10.1): a row every 24.7 dp and a column every 47 dp. */
    val gridColumn = 47.dp
    val gridRow = 24.7.dp
}

/**
 * The only fixed hues: the latency qualities in light and in dark, before [RvColors] darkens or lightens them until they
 * read on the card, and Revolut's off switch thumb, #fafafa in both themes.
 */
internal object RvHue {
    val goodLight = Color(0xFF13795B)
    val goodDark = Color(0xFF5BC49A)
    val fairLight = Color(0xFF94600A)
    val fairDark = Color(0xFFE7B04E)
    val slowLight = Color(0xFFB3261E)
    val slowDark = Color(0xFFFF8A80)
    val switchOffThumb = Color(0xFFFAFAFA)
}

internal object RvAlpha {
    /** The state layer: the content colour over the visible shape while pressed. */
    const val PRESSED = 0.1f

    /**
     * A round disc lightening while pressed: the card's white at 60 % over it in light; in dark, where the disc is
     * already the lightest container, the text colour at 10 %, as strong as the state layer.
     */
    const val DISC_LIT_LIGHT = 0.6f
    const val DISC_LIT_DARK = 0.1f

    /** Every chart's hairline grid, in the text colour: about 1.2:1 against the card in both themes (L-11). */
    const val GRID_LIGHT = 0.09f
    const val GRID_DARK = 0.09f

    /** A tile's fill under its sparkline: neutral, the text colour, fading to nothing at the bottom. */
    const val TILE_FILL = 0.08f

    /** The world map's land, in the text colour. */
    const val LAND_LIGHT = 0.08f
    const val LAND_DARK = 0.14f

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

    /** The scrim under a modal sheet (measured §2: #333333 over the page, about black at 80 %). */
    const val SHEET_SCRIM = 0.8f

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
 * 6 dp under the status bar (活動's duck, search pill and discs; a pushed page's back disc and title), a strip of blurred
 * page behind the row once content scrolls under it, and a glass capsule 16 dp in from the sides whose bottom sits 40 dp
 * above the screen's edge, 60 dp tall, that shrinks to icons only, 336 x 52 dp, while the content scrolls up.
 */
internal object RvChrome {
    /**
     * The controls' row: its top this far under the status bar, every control a 44 dp disc or pill in a 48 dp target.
     * Revolut's back disc sits 6 dp under its status bar (revolut-ux3 §5), and every pushed page's bar is this one.
     */
    val barTop = 6.dp

    /**
     * A tab home's bar (活動, 策略, 連線: the duck, the search capsule, the tab's discs) sits higher: Revolut's tab bars
     * put their 44 dp controls 4 dp under the status bar (rv-fix-4e, 36 Android screens with the search bar).
     */
    val tabBarTop = 4.dp
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
     * A page's large title, or the mark above it, this far under the bar's controls ([RvPageHeader] then sets what
     * follows), the same on every page: the title's ink starts 69 dp under the status bar, where Revolut's pushed pages
     * put it at 68.2 (revolut-ux3 §5). With the controls' 6 + 44 dp, that is 14. The first card follows at
     * [RvSpace.headerGap].
     */
    val largeTitleTop = 14.dp

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
}

/**
 * The 今日用量 card, after Revolut's Analytics 'Spent' tile (measured §10.2: 384 x 188 dp): the text at the start,
 * the stacked steps in the right half above the legend, and a hairline grid every 4 hours and in four rows.
 */
internal object RvUsageSize {
    val card = 188.dp
    val pad = 16.dp
    val legendSquare = 9.dp
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

internal object RvHomeSize {
    /** The filled triangle before a change, as Revolut's delta (measured §7: 8 dp). */
    val delta = 8.dp
    val deltaGap = 4.dp
}
