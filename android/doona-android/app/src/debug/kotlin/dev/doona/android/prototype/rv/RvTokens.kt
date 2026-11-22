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
    val legendGap = 16.dp
    val axisGap = 8.dp

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
    val chartHeight = 132.dp
    val chartStroke = 2.dp
    val gridStroke = 1.dp
    val dot = 8.dp
    val dotRing = 2.dp
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

internal object RvAlpha {
    /** The state layer: the content colour over the visible shape while pressed. */
    const val PRESSED = 0.1f

    /** The chart's grid lines. */
    const val GRID = 0.6f

    /** The top of the area under the chart's main series, fading to nothing at the zero line. */
    const val FILL = 0.24f
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
    val axis = RvTypeStep(13, 16, FontWeight.Normal)
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
