package dev.doona.android.designsystem.tokens

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Raw values of doona's standard edition. Section numbers (§) refer to /scratch/ssd/honk-android/sketch-doona/TOKENS.md;
// doona CSS px are read 1:1 as dp and sp. Only designsystem/theme reads this file: components and screens use the roles
// there (tools/check-layers.sh enforces it).

/** Spacing scale (§4, `foundations.css:67-79`). */
object SpaceScale {
    /** No space, for a clamp or a padding that is not there; not a step of the scale. */
    val none = 0.dp
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
    val railWidth = 80.dp
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
    val tabUnderline = 2.dp
    val spark = 32.dp
    val sparkStroke = 1.5.dp
    val flagHeight = 12.dp
    val flagEdge = 0.5.dp
    val chartInset = 2.dp

    /** The launcher icon in a list row: the size of Android's list icon. */
    val appIcon = 40.dp

    /** A checkbox's box: doona's 14 grown to a touch size, like its 20 to 25 icons (§6). */
    val checkbox = 20.dp
    val meterTrack = 4.dp

    /** The width of the busy spinner's stroke. */
    val spinnerStroke = 2.dp

    /**
     * The edge of a field's text that fades where the text runs on past the box, at the normal font size: more than
     * twice a glyph, so that the glyph cut at the edge, and the one before it, are already faint. It grows with the text line.
     */
    val fieldFade = 32.dp
    val emptyIcon = 48.dp

    /** Widest dialog; a narrower window leaves the gutter at both sides. */
    val dialogMax = 560.dp

    /** Widest content column: wider windows centre it (D20). */
    val contentMax = 600.dp

    /** Windows shorter than this swap the bottom bar for a rail at the start edge (Material's compact height). */
    val compactHeight = 480.dp
}

/** Durations of the motion that has no input to follow. */
object MotionScale {
    /** The busy spinner's arc turns once in this many milliseconds. */
    const val SPINNER_TURN_MS = 1000
}

/** Alpha of the colours doona derives from a palette colour (§2). */
object AlphaScale {
    /** Light flag edge: black at 35 % (`--rp-node-flag-edge`, `src/ui/flags.css:8-13`). */
    const val FLAG_EDGE = 0.35f

    /** The modal underlay: black at 32 % in light, 55 % in dark (`--rp-underlay`, `foundations.css:6`, `:185`). */
    const val SCRIM_LIGHT = 0.32f
    const val SCRIM_DARK = 0.55f

    /**
     * The outline of a disabled outlined control: a third of an enabled one's strength, so that it is plainly fainter in
     * both themes, where the strong line is only a step off the fill (at half it was 13 levels of 255 against 26 in dark).
     */
    const val DISABLED_LINE = 0.3f
}

/** Chart style (§7, Charts). */
object ChartScale {
    const val FILL_TOP = 0.35f
    const val FILL_BOTTOM = 0.03f
    const val FILL_OPACITY = 0.6f
    const val DOMAIN_LOW = 0.85f
    const val DOMAIN_HIGH = 1.05f
}
