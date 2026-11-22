package dev.doona.android.designsystem.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.doona.android.designsystem.tokens.Dawn
import dev.doona.android.designsystem.tokens.DoonaPalette
import dev.doona.android.designsystem.tokens.Moon
import dev.doona.android.designsystem.tokens.NotoSansTc

// Every colour, size, shape and type value of the app, taken from doona's standard edition.
// Section numbers (§) refer to /scratch/ssd/honk-android/sketch-doona/TOKENS.md; doona CSS px are read 1:1 as dp and sp.

/** Semantic roles (§2, "Semantic roles and where they are used"). */
@Immutable
class DoonaColors(p: DoonaPalette) {
    val dark = p.dark
    val window = p.base
    val sheet = p.surface
    val card = p.base
    val cardBorder = p.hlMed.copy(alpha = 0.7f)
    val controlFill = p.overlay
    val line = if (p.dark) p.hlMed else p.hlLow
    val strongLine = p.hlMed
    val text = p.text
    val textSecondary = p.subtle
    val textDisabled = p.muted
    val accent = p.pine
    val onAccent = p.onAccent
    val selected = p.selected
    val badgeBorder = p.hlHigh
    val switchOff = p.hlHigh
    val switchKnob = p.surface
    val positive = p.foam
    val notice = p.gold
    val negative = p.love
    val neutral = p.subtle

    /** Flag edge: a 0.5 px black 35 % outline in light, none in dark (`--rp-node-flag-edge`, `src/ui/flags.css:8-13`). */
    val flagEdge: Color? = if (p.dark) null else Color.Black.copy(alpha = 0.35f)

    /** Latency text: light keeps body text and shows the tone only in the dot. */
    val positiveText = if (p.dark) p.foam else p.text
    val noticeText = if (p.dark) p.gold else p.text
    val negativeText = if (p.dark) p.love else p.text

    /** Chart series c1…c6 (§2, chart series table). */
    val c1 = p.pine
    val c2 = p.foam
    val c3 = p.iris
    val c4 = p.gold
    val c5 = p.rose
    val c6 = p.love
}

/** Type scale (§3). Line heights are integers, as in doona. */
@Immutable
class DoonaType(private val family: androidx.compose.ui.text.font.FontFamily) {
    private fun style(size: Int, line: Int, weight: Int) = TextStyle(
        fontFamily = family,
        fontSize = size.sp,
        lineHeight = line.sp,
        fontWeight = FontWeight(weight),
        lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
    )

    /** `.rp-h1` 28 / 36 / 800. */
    val pageTitle = style(28, 36, 800)

    /** `.rp-h3` 16 / 20 / 700. */
    val cardTitle = style(16, 20, 700)

    /** `.rp-qlabel` 14 / 20 / 500; also buttons and page links. */
    val label = style(14, 20, 500)

    /** Body and segmented items 14 / 20 / 400. */
    val body = style(14, 20, 400)

    /** Node name and latency 14 / 20 / 700. */
    val bodyStrong = style(14, 20, 700)

    /** `.rp-tile-head`, `.rp-label`, node sub-line 12 / 16 / 400. */
    val caption = style(12, 16, 400)

    /** Badge 12 / 500 (line 1 in a 20 high box). */
    val badge = style(12, 16, 500)

    /** `.rp-big` 22 / 28 / 700. */
    val tileValue = style(22, 28, 700)

    /** Bottom bar label 12 / 16 / 500, current 600. */
    val navLabel = style(12, 16, 500)
    val navLabelCurrent = style(12, 16, 600)

    /** Chart axis tick 11. */
    val axis = style(11, 16, 400)

    /** Tabular figures (§3, last line). */
    val tabular = "tnum"
}

/** Spacing scale (§4, `foundations.css:67-79`). */
@Immutable
object DoonaSpace {
    val half = 2.dp
    val s1 = 4.dp
    val s1h = 6.dp
    val s2 = 8.dp
    val s3 = 12.dp
    val s4 = 16.dp
    val s5 = 20.dp
    val s6 = 24.dp
    val s8 = 32.dp
    val s12 = 48.dp
    val s16 = 64.dp
}

/** Radii (§5, `foundations.css:47-53`). */
@Immutable
object DoonaShape {
    val sm = RoundedCornerShape(6.dp)
    val md = RoundedCornerShape(8.dp)
    val xl = RoundedCornerShape(16.dp)
    val pill = RoundedCornerShape(percent = 50)

    /** Page sheet: 16 on the top corners only. */
    val sheet = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
    val trackXs = RoundedCornerShape(2.dp)

    /** Twemoji flag corner: 57 of 512 units across 16.6 dp (flag_*.xml). */
    val flag = RoundedCornerShape(2.dp)
}

/** Component metrics (§5, §6, §7, §8). */
@Immutable
object DoonaSize {
    /** Control height on touch (§7). */
    val control = 36.dp

    /** Android touch target and row minimum; doona's own touch control is 36. */
    val touch = 48.dp
    val topBar = 64.dp
    val bottomBar = 64.dp
    val logo = 28.dp

    /** Icons: 25 on touch, 20 on the grid, 16 inside a button (§6). */
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
}

/** Chart style (§7, Charts). */
@Immutable
object DoonaChart {
    const val FILL_TOP = 0.35f
    const val FILL_BOTTOM = 0.03f
    const val FILL_OPACITY = 0.6f
    const val DOMAIN_LOW = 0.85f
    const val DOMAIN_HIGH = 1.05f
    val INSET = 2.dp
}

@Immutable
class DoonaTokens(val colors: DoonaColors, val type: DoonaType)

private val LocalDoona = staticCompositionLocalOf<DoonaTokens> { error("DoonaTheme is not set") }

/**
 * Insets of the status bar and the gesture bar. The app reads the window's; a screenshot frame that draws its own
 * bars provides fixed ones.
 */
val LocalFrameInsets = compositionLocalOf<WindowInsets?> { null }

object DoonaTheme {
    val colors: DoonaColors
        @Composable @ReadOnlyComposable
        get() = LocalDoona.current.colors
    val type: DoonaType
        @Composable @ReadOnlyComposable
        get() = LocalDoona.current.type
    val space = DoonaSpace
    val shape = DoonaShape
    val size = DoonaSize

    val insets: WindowInsets
        @Composable
        get() = LocalFrameInsets.current ?: WindowInsets.systemBars

    /** Type sizes allowed by the scale (§3), for checks. */
    val scale: List<TextUnit> = listOf(11, 12, 13, 14, 16, 18, 20, 22, 24, 28).map { it.sp }

    /** Spacing values allowed by the scale (§4), for checks. */
    val spacing: List<Dp> = listOf(2, 4, 6, 8, 10, 12, 16, 20, 24, 32, 40, 48, 64).map { it.dp }
}

/**
 * doona's palettes win over Android's dynamic colour here: doona is the reference, and its palette is the product's
 * identity. M3 gets a matching scheme only so its plumbing (ripple, selection) picks doona's colours.
 */
@Composable
fun DoonaTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val palette = if (dark) Moon else Dawn
    val colors = DoonaColors(palette)
    val tokens = DoonaTokens(colors, DoonaType(NotoSansTc))
    val scheme = if (dark) {
        darkColorScheme(primary = colors.accent, onPrimary = colors.onAccent, background = colors.window, surface = colors.sheet, onSurface = colors.text, onBackground = colors.text)
    } else {
        lightColorScheme(primary = colors.accent, onPrimary = colors.onAccent, background = colors.window, surface = colors.sheet, onSurface = colors.text, onBackground = colors.text)
    }
    MaterialTheme(colorScheme = scheme) {
        CompositionLocalProvider(LocalDoona provides tokens, LocalContentColor provides colors.text, content = content)
    }
}
