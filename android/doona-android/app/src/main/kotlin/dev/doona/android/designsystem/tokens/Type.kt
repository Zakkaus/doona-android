package dev.doona.android.designsystem.tokens

import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** One step of the type scale: size and line height in sp (integers, as measured), weight, and the display or text cut of Inter. */
@Immutable
class TypeStep(sizeSp: Int, lineSp: Int, val weight: FontWeight, val display: Boolean = false) {
    val size = sizeSp.sp
    val line = lineSp.sp
}

/** Type scale: the sample's steps (measured §11 unless noted), then U1's steps for the old components. */
object TypeScale {
    /** 活動's hero (measured §5; A04): the integer 44 sp, its decimals and unit 32 sp, one weight, one baseline. */
    val hero = TypeStep(44, 53, FontWeight.Bold, display = true)
    val heroSmall = TypeStep(32, 40, FontWeight.Bold, display = true)

    /** A detail page's figure (measured §10.1, the coin header; T13, S5-30): 33 sp with its decimals, the unit 22 sp. */
    val detailFigure = TypeStep(33, 40, FontWeight.Bold, display = true)
    val detailUnit = TypeStep(22, 28, FontWeight.SemiBold, display = true)

    /** The hero's caption (measured §5: 17 sp medium), and the line under a figure (17 sp). */
    val caption = TypeStep(17, 24, FontWeight.Medium)
    val sub = TypeStep(17, 24, FontWeight.Normal)

    /** A row's title and subtitle (measured §7: 19 sp semibold, 17 sp regular; X-06, S8-02). */
    val rowTitle = TypeStep(19, 26, FontWeight.SemiBold)
    val rowSub = TypeStep(17, 24, FontWeight.Normal)

    /** A round action's label and a range chip (measured §6, §8: 17 sp semibold); a segment (19 sp semibold). */
    val action = TypeStep(17, 24, FontWeight.SemiBold)
    val segment = TypeStep(19, 26, FontWeight.SemiBold)
    val tileCaption = TypeStep(13, 18, FontWeight.Normal)
    val tileValue = TypeStep(24, 30, FontWeight.Bold, display = true)
    val tileUnit = TypeStep(15, 20, FontWeight.SemiBold, display = true)

    /** A chart card's figure, after Revolut's Analytics page: the tile's figure when there is no traffic. */
    val cardValue = TypeStep(28, 34, FontWeight.Bold, display = true)
    val cardUnit = TypeStep(17, 22, FontWeight.SemiBold, display = true)

    /** The one value pill (measured §10.1: 15 sp semibold). */
    val pill = TypeStep(15, 20, FontWeight.SemiBold)

    /** An outside section title (measured §7: 20 sp bold; X-05, X-17). */
    val section = TypeStep(20, 26, FontWeight.Bold, display = true)

    /** A stat row (measured §11; T10): the label 19 sp medium in secondary ink, the value 19 sp regular. */
    val statLabel = TypeStep(19, 26, FontWeight.Medium)
    val stat = TypeStep(19, 26, FontWeight.Normal)

    /** The capsule's label (A07, measured). */
    val navLabel = TypeStep(15, 18, FontWeight.SemiBold)

    /** A pushed page's large title (S8-03), and the compact one it collapses to beside the back disc (measured §4). */
    val largeTitle = TypeStep(32, 38, FontWeight.Bold, display = true)
    val barTitle = TypeStep(20, 26, FontWeight.SemiBold, display = true)

    /** The two letters on a flag's fallback disc (X-18). */
    val flagCode = TypeStep(13, 16, FontWeight.SemiBold)

    /** The search pill's placeholder (measured §4: 17 sp) and a card's in-card header (measured §7: 19 sp medium). */
    val search = TypeStep(17, 22, FontWeight.Normal)
    val cardTitle = TypeStep(19, 26, FontWeight.Medium)

    /** A list card's 'See all' row (measured §7: centred, 19 sp bold). */
    val seeAll = TypeStep(19, 26, FontWeight.Bold)

    /** Font feature setting for tabular figures: only where figures line up or change in place. */
    const val TABULAR = "tnum"

    // U1's steps (sketch-doona TOKENS.md §3), kept at their sizes and weights while the old components read them; steps of
    // 20 sp and up take Inter's display cut, the rest the text cut.

    /** `.rp-h1`. */
    val h1 = TypeStep(28, 36, FontWeight.ExtraBold, display = true)

    /** `.rp-h3`. */
    val h3 = TypeStep(16, 20, FontWeight.Bold)

    /** `.rp-qlabel`, buttons and page links. */
    val label = TypeStep(14, 20, FontWeight.Medium)
    val body = TypeStep(14, 20, FontWeight.Normal)
    val bodyStrong = TypeStep(14, 20, FontWeight.Bold)

    /** `.rp-tile-head`, `.rp-label`, node sub-line. */
    val footnote = TypeStep(12, 16, FontWeight.Normal)
    val footnoteMedium = TypeStep(12, 16, FontWeight.Medium)
    val footnoteSemibold = TypeStep(12, 16, FontWeight.SemiBold)

    /** `.rp-big`. */
    val big = TypeStep(22, 28, FontWeight.Bold, display = true)

    /** Chart axis tick. */
    val tick = TypeStep(11, 16, FontWeight.Normal)
}
