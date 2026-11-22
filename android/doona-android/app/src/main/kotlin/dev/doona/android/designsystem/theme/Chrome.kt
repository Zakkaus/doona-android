package dev.doona.android.designsystem.theme

import androidx.compose.runtime.Immutable
import dev.doona.android.designsystem.tokens.ChromeScale
import dev.doona.android.designsystem.tokens.DotMapScale
import dev.doona.android.designsystem.tokens.HomeScale
import dev.doona.android.designsystem.tokens.UsageScale

/** A page's chrome: the top bar's row of controls, the strip behind it, a pushed page's titles, and the floating capsule. */
@Immutable
object DoonaChrome {
    val barTop = ChromeScale.barTop
    val control = ChromeScale.control
    val controlGlyph = ChromeScale.controlGlyph

    /** Half the difference between a control and its touch target: the padding that grows the one to the other. */
    val touchPad = (DoonaSize.touch - ChromeScale.control) / 2
    val stripBelow = ChromeScale.stripBelow
    val stripFade = ChromeScale.stripFade
    val shadeAfter = ChromeScale.shadeAfter
    val collapse = ChromeScale.collapse
    val markGlyph = ChromeScale.markGlyph
    val controlGap = ChromeScale.controlGap
    val buttonGap = ChromeScale.buttonGap
    val searchGlyph = ChromeScale.searchGlyph
    val searchInset = ChromeScale.searchInset
    val titleStart = ChromeScale.titleStart
    val largeTitleTop = ChromeScale.largeTitleTop
    val largeTitleBottom = ChromeScale.largeTitleBottom
    val navInset = ChromeScale.navInset
    val navBottom = ChromeScale.navBottom

    /** From the last card to the capsule: every page with the capsule ends this far above it. */
    val navGap = ChromeScale.navGap
    val navAboveInset = ChromeScale.navAboveInset
    val navHeight = ChromeScale.navHeight
    val navCollapsedHeight = ChromeScale.navCollapsedHeight
    val navCollapsedWidth = ChromeScale.navCollapsedWidth
    val navPillInset = ChromeScale.navPillInset
    val navGlyph = ChromeScale.navGlyph
    val navCollapsedGlyph = ChromeScale.navCollapsedGlyph
    val navLabelGap = ChromeScale.navLabelGap
    val navLabel = ChromeScale.navLabel
}

/** 活動's dot map. */
@Immutable
object DoonaDotMap {
    val pitch = DotMapScale.pitch
    val dot = DotMapScale.dot
    val focusTop = DotMapScale.focusTop
    val marker = DotMapScale.marker
    val ring = DotMapScale.ring
    val ringStroke = DotMapScale.ringStroke
    val glow = DotMapScale.glow
    val topFade = DotMapScale.topFade
    val clearPad = DotMapScale.clearPad
    const val CLEAR_ALPHA = DotMapScale.CLEAR_ALPHA
    const val ALPHA = DotMapScale.ALPHA
    const val RING_ALPHA = DotMapScale.RING_ALPHA
    const val GLOW_ALPHA = DotMapScale.GLOW_ALPHA
    const val FADE_START = DotMapScale.FADE_START
    const val FADE_END = DotMapScale.FADE_END
}

/** The 今日用量 card and a home card's header. */
@Immutable
object DoonaUsage {
    val card = UsageScale.card
    val pad = UsageScale.pad
    val legendSquare = UsageScale.legendSquare
    val legendGap = UsageScale.legendGap
    val legendItemGap = UsageScale.legendItemGap
    val chartAboveLegend = UsageScale.chartAboveLegend
    val stroke = UsageScale.stroke
    const val HOURS = UsageScale.HOURS
    const val GRID_HOURS = UsageScale.GRID_HOURS
    const val GRID_ROWS = UsageScale.GRID_ROWS
    const val HEADROOM = UsageScale.HEADROOM
    val homeHeaderPad = HomeScale.headerPad
    val homeHeaderChevron = HomeScale.headerChevron
    val delta = HomeScale.delta
    val deltaGap = HomeScale.deltaGap
}
