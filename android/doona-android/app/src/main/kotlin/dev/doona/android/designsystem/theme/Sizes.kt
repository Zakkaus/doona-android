package dev.doona.android.designsystem.theme

import androidx.compose.runtime.Immutable
import dev.doona.android.designsystem.tokens.ChartScale
import dev.doona.android.designsystem.tokens.ChevronScale
import dev.doona.android.designsystem.tokens.HeaderScale
import dev.doona.android.designsystem.tokens.RadiusScale
import dev.doona.android.designsystem.tokens.SizeScale

/** Component metrics. */
@Immutable
object DoonaSize {
    /** A round action's disc and its glyph. */
    val actionDisc = SizeScale.actionDisc
    val actionGlyph = SizeScale.actionGlyph

    /** A row's leading visual (disc or flag) and the glyph in a disc; a row's height with and without a subtitle. */
    val rowDisc = SizeScale.rowDisc
    val rowGlyph = SizeScale.rowGlyph
    val rowTwoLine = SizeScale.rowTwoLine
    val rowOneLine = SizeScale.rowOneLine

    /** A glyph inside a line of text or a button. */
    val inlineGlyph = SizeScale.inlineGlyph

    /** A status dot. */
    val dot = SizeScale.dot
    val cardRadius = RadiusScale.card
    val seeAll = SizeScale.seeAll

    /** Android touch target and row minimum. */
    val touch = SizeScale.touch
    val navGlyph = SizeScale.navGlyph
    val progress = SizeScale.progress

    /** The current node's check badge: its disc, its ring in the card's colour, its check's stroke, how far it hangs out. */
    val badge = SizeScale.badge
    val badgeRing = SizeScale.badgeRing
    val badgeCheck = SizeScale.badgeCheck
    val badgeOverhang = SizeScale.badgeOverhang

    /** A hairline: a ring, a rim, an outline. */
    val ring = SizeScale.ring

    /** The chevron's box, its ink and its stroke. */
    val chevronBox = ChevronScale.box
    val chevronWidth = ChevronScale.width
    val chevronHeight = ChevronScale.height
    val chevronStroke = ChevronScale.stroke

    /** A card's in-card header row. */
    val headerRow = HeaderScale.row

    // The old components' metrics, U1's until the page lanes replace them.

    /** Control height on touch (§7). */
    val control = SizeScale.control
    val topBar = SizeScale.topBar
    val bottomBar = SizeScale.bottomBar
    val railWidth = SizeScale.railWidth
    val logo = SizeScale.logo

    /** Icons: 25 on touch, 20 on the grid (§6). */
    val icon = SizeScale.icon
    val iconSmall = SizeScale.iconSmall
    val hubPillWidth = SizeScale.hubPillWidth
    val hubPillHeight = SizeScale.hubPillHeight
    val borderStrong = SizeScale.borderStrong
    val statusDot = SizeScale.statusDot
    val badgeHeight = SizeScale.badgeHeight
    val nodeTileMin = SizeScale.nodeTileMin
    val tabUnderline = SizeScale.tabUnderline
    val spark = SizeScale.spark
    val sparkStroke = SizeScale.sparkStroke
    val flag = SizeScale.flag
    val flagLetters = SizeScale.flagLetters
    val flagEdge = SizeScale.flagEdge
    val chartInset = SizeScale.chartInset
    val appIcon = SizeScale.appIcon
    val checkbox = SizeScale.checkbox
    val meterTrack = SizeScale.meterTrack
    val spinnerStroke = SizeScale.spinnerStroke
    val fieldFade = SizeScale.fieldFade
    val emptyIcon = SizeScale.emptyIcon
    val dialogMax = SizeScale.dialogMax
    val contentMax = SizeScale.contentMax
    val compactHeight = SizeScale.compactHeight
}

/** Chart metrics and style: the sample's tiles, detail chart, pills, chips and segments, and U1's sparkline. */
@Immutable
object DoonaChart {
    val tilePad = ChartScale.tilePad
    val tileDisc = ChartScale.tileDisc
    val tileGlyph = ChartScale.tileGlyph
    val spark = ChartScale.spark
    val sparkStroke = ChartScale.sparkStroke
    val ghostBar = ChartScale.ghostBar
    const val GHOST_BARS = ChartScale.GHOST_BARS
    val detail = ChartScale.detail
    val detailStroke = ChartScale.detailStroke
    val detailEnd = ChartScale.detailEnd
    val liveDot = ChartScale.liveDot
    val liveHalo = ChartScale.liveHalo
    val scrubLine = ChartScale.scrubLine
    val scrubDot = ChartScale.scrubDot
    val scrubRing = ChartScale.scrubRing
    val dotted = ChartScale.dotted
    val dottedPitch = ChartScale.dottedPitch
    val pill = ChartScale.pill
    val pillPadH = ChartScale.pillPadH
    val pillRadius = ChartScale.pillRadius
    val pillInset = ChartScale.pillInset
    val pillClear = ChartScale.pillClear
    val range = ChartScale.range
    val rangePad = ChartScale.rangePad
    val rangeGap = ChartScale.rangeGap
    val roundButton = ChartScale.roundButton
    val roundGap = ChartScale.roundGap
    val roundGlyph = ChartScale.roundGlyph
    val segment = ChartScale.segment
    val segmentInset = ChartScale.segmentInset
    const val SPARK_HEADROOM = ChartScale.SPARK_HEADROOM
    const val DETAIL_HEADROOM = ChartScale.DETAIL_HEADROOM
    val gridColumn = ChartScale.gridColumn
    val gridRow = ChartScale.gridRow

    // U1's sparkline.
    const val FILL_TOP = ChartScale.FILL_TOP
    const val FILL_BOTTOM = ChartScale.FILL_BOTTOM
    const val FILL_OPACITY = ChartScale.FILL_OPACITY
    const val DOMAIN_LOW = ChartScale.DOMAIN_LOW
    const val DOMAIN_HIGH = ChartScale.DOMAIN_HIGH
}
