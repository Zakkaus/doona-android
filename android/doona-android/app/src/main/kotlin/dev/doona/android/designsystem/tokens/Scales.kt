package dev.doona.android.designsystem.tokens

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Raw values of doona's standard edition. Section numbers (§) refer to /scratch/ssd/honk-android/sketch-doona/TOKENS.md;
// doona CSS px are read 1:1 as dp and sp. Only designsystem/theme reads this file: components and screens use the roles
// there (tools/check-layers.sh enforces it).

/** Spacing scale (§4, `foundations.css:67-79`). */
object SpaceScale {
    val half = 2.dp
    val s1 = 4.dp
    val s1h = 6.dp
    val s2 = 8.dp
    val s2h = 10.dp
    val s3 = 12.dp
    val s4 = 16.dp
    val s5 = 20.dp
    val s6 = 24.dp
    val s8 = 32.dp
    val s10 = 40.dp
    val s12 = 48.dp
    val s16 = 64.dp
    val all = listOf(half, s1, s1h, s2, s2h, s3, s4, s5, s6, s8, s10, s12, s16)
}

/** One step of the type scale (§3): size, line height (an integer, as in doona) and weight. */
@Immutable
class TypeStep(sizeSp: Int, lineSp: Int, val weight: Int) {
    val size = sizeSp.sp
    val line = lineSp.sp
}

/** Type scale (§3). */
object TypeScale {
    /** `.rp-h1`. */
    val h1 = TypeStep(28, 36, 800)

    /** `.rp-h3`. */
    val h3 = TypeStep(16, 20, 700)

    /** `.rp-qlabel`, buttons and page links. */
    val label = TypeStep(14, 20, 500)
    val body = TypeStep(14, 20, 400)
    val bodyStrong = TypeStep(14, 20, 700)

    /** `.rp-tile-head`, `.rp-label`, node sub-line. */
    val caption = TypeStep(12, 16, 400)
    val captionMedium = TypeStep(12, 16, 500)
    val captionSemibold = TypeStep(12, 16, 600)

    /** `.rp-big`. */
    val big = TypeStep(22, 28, 700)

    /** Chart axis tick. */
    val tick = TypeStep(11, 16, 400)

    /** Font feature setting for tabular figures (§3, last line). */
    const val TABULAR = "tnum"
    val sizes = listOf(11, 12, 13, 14, 16, 18, 20, 22, 24, 28).map { it.sp }
}

/** Radii (§5, `foundations.css:47-53`). */
object RadiusScale {
    val xs = 2.dp
    val sm = 6.dp
    val md = 8.dp
    val xl = 16.dp
    const val PILL_PERCENT = 50
}

/** Component metrics (§5, §6, §7, §8). The design is flat: no elevation, only 1 and 2 dp borders. */
object SizeScale {
    val control = 36.dp
    val touch = 48.dp
    val topBar = 64.dp
    val bottomBar = 64.dp
    val logo = 28.dp
    val icon = 25.dp
    val iconSmall = 20.dp
    val iconInline = 16.dp
    val hubPillWidth = 56.dp
    val hubPillHeight = 32.dp
    val border = 1.dp
    val borderStrong = 2.dp
    val dot = 10.dp
    val dotSmall = 8.dp
    val badgeHeight = 20.dp
    val nodeTileMin = 56.dp
    val switchWidth = 32.dp
    val switchHeight = 16.dp
    val switchKnob = 12.dp
    val tabUnderline = 2.dp
    val spark = 32.dp
    val sparkStroke = 1.5.dp
    val flagHeight = 12.dp
    val flagEdge = 0.5.dp
    val chartInset = 2.dp

    /** Widest content column: wider windows centre it (D20). */
    val contentMax = 600.dp
}

/** Chart style (§7, Charts). */
object ChartScale {
    const val FILL_TOP = 0.35f
    const val FILL_BOTTOM = 0.03f
    const val FILL_OPACITY = 0.6f
    const val DOMAIN_LOW = 0.85f
    const val DOMAIN_HIGH = 1.05f
}
