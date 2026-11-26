package dev.doona.android.designsystem.rv

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import dev.doona.android.R

// The sample's type scale and fonts, measured as RvTokens.kt's values (~/code/doona-v1/rv-spec.md). RvTheme.kt builds
// the roles of RvType from them; the screens read the roles.

/** One step of the type scale: size and line height in sp, weight, display or text cut. */
internal class RvTypeStep(val size: Int, val line: Int, val weight: FontWeight, val display: Boolean = false)

internal object RvTypeScale {
    /** 活動's hero (measured §5): the integer 44 sp, its decimals and unit 32 sp, one weight, one baseline. */
    val hero = RvTypeStep(44, 53, FontWeight.Bold, display = true)
    val heroSmall = RvTypeStep(32, 40, FontWeight.Bold, display = true)

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

    /**
     * A chart card's figure, after Revolut's Analytics page (measured §10.2: the 'Spent' tile's figure, 29 sp bold): the
     * tile's figure when there is no traffic, and 流量詳情's rate.
     */
    val cardValue = RvTypeStep(28, 34, FontWeight.Bold, display = true)
    val cardUnit = RvTypeStep(17, 22, FontWeight.SemiBold, display = true)

    /** The one value pill (measured §10.1: 15 sp semibold). */
    val pill = RvTypeStep(15, 20, FontWeight.SemiBold)

    /** An outside section title (measured §7: 20 sp bold). */
    val section = RvTypeStep(20, 26, FontWeight.Bold, display = true)

    /** A stat row (measured §11): the label 19 sp medium in secondary ink, the value 19 sp regular. */
    val statLabel = RvTypeStep(19, 26, FontWeight.Medium)
    val stat = RvTypeStep(19, 26, FontWeight.Normal)

    /** What a text field holds and its placeholder: the stat value's 19 sp regular (measured §11). */
    val field = RvTypeStep(19, 26, FontWeight.Normal)
    val navLabel = RvTypeStep(15, 18, FontWeight.SemiBold)

    /** A primary button's label (measured §8 and revolut-ux3 §4: 22 sp semibold in the 52 dp pill). */
    val button = RvTypeStep(22, 28, FontWeight.SemiBold)

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

/** Inter 4 at the text optical size (opsz 14), Latin only (rv-fonts/make.py): the files of tokens/Fonts.kt's Inter. */
internal val RvInter = FontFamily(
    Font(R.font.inter_w400, FontWeight.Normal),
    Font(R.font.inter_w500, FontWeight.Medium),
    Font(R.font.inter_w600, FontWeight.SemiBold),
)

/** Inter 4 at the display optical size (opsz 32), for the hero number. */
internal val RvInterDisplay = FontFamily(
    Font(R.font.inter_display_w600, FontWeight.SemiBold),
    Font(R.font.inter_display_w700, FontWeight.Bold),
)

/** The app's Noto Sans TC subset, for every run of CJK text. */
internal val RvNoto = FontFamily(
    Font(R.font.noto_sans_tc_w400, FontWeight.Normal),
    Font(R.font.noto_sans_tc_w500, FontWeight.Medium),
    Font(R.font.noto_sans_tc_w600, FontWeight.SemiBold),
    Font(R.font.noto_sans_tc_w700, FontWeight.Bold),
)
