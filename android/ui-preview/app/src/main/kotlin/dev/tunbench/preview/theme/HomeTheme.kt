package dev.tunbench.preview.theme

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

// The app's one token set, after Google Home's home tab. Sizes, shapes and type are measured from
// Google Home's Play Store screenshots, scaled to a 412 dp wide screen. Colour has
// no fixed hue: every colour is a role of the dynamic scheme (the wallpaper's on API 31+), and below is
// the fallback for older devices and screenshots.

/** The fallback's one seed: Google Home's measured primary blue. */
const val FALLBACK_SEED = 0xFF0B57D0

// Tonal Spot (the system default variant) from [FALLBACK_SEED] at standard contrast, computed with
// material-color-utilities as bundled in Material Components for Android 1.14.0 (the test helper
// `seedColorScheme`), so it is the scheme a phone with that wallpaper colour would get.
internal val HomeLightScheme = lightColorScheme(
    primary = Color(0xFF495D92),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDAE2FF),
    onPrimaryContainer = Color(0xFF304578),
    inversePrimary = Color(0xFFB2C5FF),
    secondary = Color(0xFF585E71),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFDCE2F9),
    onSecondaryContainer = Color(0xFF404659),
    tertiary = Color(0xFF735471),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFED7F9),
    onTertiaryContainer = Color(0xFF5A3D59),
    background = Color(0xFFFAF8FF),
    onBackground = Color(0xFF1A1B21),
    surface = Color(0xFFFAF8FF),
    onSurface = Color(0xFF1A1B21),
    surfaceVariant = Color(0xFFE1E2EC),
    onSurfaceVariant = Color(0xFF45464F),
    surfaceTint = Color(0xFF495D92),
    inverseSurface = Color(0xFF2F3036),
    inverseOnSurface = Color(0xFFF1F0F7),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF93000A),
    outline = Color(0xFF757780),
    outlineVariant = Color(0xFFC5C6D0),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFFFAF8FF),
    surfaceDim = Color(0xFFDAD9E0),
    surfaceContainer = Color(0xFFEEEDF4),
    surfaceContainerHigh = Color(0xFFE8E7EF),
    surfaceContainerHighest = Color(0xFFE2E2E9),
    surfaceContainerLow = Color(0xFFF4F3FA),
    surfaceContainerLowest = Color(0xFFFFFFFF),
)

internal val HomeDarkScheme = darkColorScheme(
    primary = Color(0xFFB2C5FF),
    onPrimary = Color(0xFF172E60),
    primaryContainer = Color(0xFF304578),
    onPrimaryContainer = Color(0xFFDAE2FF),
    inversePrimary = Color(0xFF495D92),
    secondary = Color(0xFFC0C6DD),
    onSecondary = Color(0xFF2A3042),
    secondaryContainer = Color(0xFF404659),
    onSecondaryContainer = Color(0xFFDCE2F9),
    tertiary = Color(0xFFE1BBDD),
    onTertiary = Color(0xFF412741),
    tertiaryContainer = Color(0xFF5A3D59),
    onTertiaryContainer = Color(0xFFFED7F9),
    background = Color(0xFF121318),
    onBackground = Color(0xFFE2E2E9),
    surface = Color(0xFF121318),
    onSurface = Color(0xFFE2E2E9),
    surfaceVariant = Color(0xFF45464F),
    onSurfaceVariant = Color(0xFFC5C6D0),
    surfaceTint = Color(0xFFB2C5FF),
    inverseSurface = Color(0xFFE2E2E9),
    inverseOnSurface = Color(0xFF2F3036),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    outline = Color(0xFF8F909A),
    outlineVariant = Color(0xFF45464F),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF38393F),
    surfaceDim = Color(0xFF121318),
    surfaceContainer = Color(0xFF1E1F25),
    surfaceContainerHigh = Color(0xFF282A2F),
    surfaceContainerHighest = Color(0xFF33343A),
    surfaceContainerLow = Color(0xFF1A1B21),
    surfaceContainerLowest = Color(0xFF0D0E13),
)

/**
 * Opacity of the live chart's area over its primary line: Material's dragged state-layer opacity
 * (m3.material.io/foundations/interaction/states/state-layers), the strongest of the standard steps.
 */
const val CHART_FILL_ALPHA = 0.16f

object HomeDims {
    /** The tinted zone from the status bar to the page (measured 83 dp). */
    val topBarHeight = 80.dp
    val margin = 16.dp

    /** Zone to chips. Chips to the first card is also 16: [gap] plus [chipRowBottom]. */
    val chipRowTop = 16.dp
    val chipRowBottom = 6.dp

    /** Between chips, tiles and cards, across and down (measured 9–11). */
    val gap = 10.dp
    val chipHeight = 56.dp
    val chipPadding = 24.dp
    val chipIcon = 24.dp
    val chipIconGap = 8.dp
    val corner = 28.dp
    val liveHeight = 212.dp
    val liveInset = 24.dp
    val liveTop = 18.dp

    /** The band the label row keeps clear: the chart's full scale (8 Gbit/s) reaches only this far down. */
    val liveHeadroom = 56.dp
    val liveDot = 8.dp
    val liveDotGap = 8.dp
    val neutralHeight = 80.dp
    val tilePadding = 16.dp
    val tileIcon = 24.dp
    val tileIconGap = 12.dp
    val controlHeight = 212.dp
    val controlPadding = 16.dp

    /** The thermostat's step pill, used once at the row's end as Home's automation rows place their run button. */
    val pillWidth = 70.dp
    val pillHeight = 56.dp
}

object HomeShapes {
    val card = RoundedCornerShape(HomeDims.corner)
    val pill = CircleShape
}

/** Home's type, Google Sans: sizes from cap and ascender heights, snapped to Material's sizes. */
object HomeType {
    val title = sans(16, 24)
    val chip = sans(16, 24, FontWeight.Medium)
    val liveLabel = sans(14, 20, FontWeight.Medium)
    val cardHeader = sans(16, 24, FontWeight.Medium)
    val figure = sans(60, 64)
    val figureCaption = sans(14, 20, FontWeight.Medium)
    val neutral = sans(14, 20)
}
