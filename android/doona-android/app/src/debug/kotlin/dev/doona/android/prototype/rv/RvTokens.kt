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
}

internal object RvSize {
    val actionDisc = 52.dp
    val actionGlyph = 22.dp
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
}

internal object RvAlpha {
    /** The state layer: the content colour over the visible shape while pressed. */
    const val PRESSED = 0.1f

    /**
     * The header's fade from the tint to the page, as (position, share of the tint): #11's cyan measured every 50 px
     * from the screen's top to its round actions.
     */
    val HEADER_FADE = listOf(0f to 1f, 0.27f to 0.72f, 0.4f to 0.45f, 0.53f to 0.24f, 0.66f to 0.1f, 0.79f to 0.035f, 1f to 0f)

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
