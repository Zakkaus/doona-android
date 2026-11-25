package dev.doona.android.designsystem.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemBars
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import dev.doona.android.designsystem.rv.LocalFrameInsets

@Immutable
class DoonaTokens(val colors: DoonaColors, val type: DoonaType)

private val LocalDoona = staticCompositionLocalOf<DoonaTokens> { error("DoonaTheme is not set") }

/** The roles every component and screen reads: colours and type follow the theme, the rest is the same in both. */
object DoonaTheme {
    val colors: DoonaColors
        @Composable @ReadOnlyComposable
        get() = LocalDoona.current.colors
    val type: DoonaType
        @Composable @ReadOnlyComposable
        get() = LocalDoona.current.type
    val spacing = DoonaSpacing
    val shape = DoonaShape
    val size = DoonaSize
    val chart = DoonaChart
    val chrome = DoonaChrome
    val elevation = DoonaElevation
    val glass = DoonaGlass

    val insets: WindowInsets
        @Composable
        get() = LocalFrameInsets.current ?: WindowInsets.systemBars

    /** Whether the window is too short for a bar at the bottom: the shell puts a rail at the start edge instead. */
    val compactHeight: Boolean
        @Composable @ReadOnlyComposable
        get() = LocalConfiguration.current.screenHeightDp < size.compactHeight.value
}

/**
 * The theme: every colour role derives from [scheme], by default the wallpaper's dynamic scheme (minSdk 31, so it always
 * exists on a device). Tests pass a scheme seeded from a fixed colour, since Robolectric has no wallpaper. Material's
 * theme gets the same scheme only as plumbing (ripple, selection); components read [DoonaTheme].
 */
@Composable
fun DoonaTheme(dark: Boolean = isSystemInDarkTheme(), scheme: ColorScheme = dynamicScheme(dark), content: @Composable () -> Unit) {
    // The roles are built once per scheme: a staticCompositionLocalOf recomposes everything under it when it changes.
    val tokens = remember(scheme, dark) { DoonaTokens(DoonaColors(scheme, dark), DoonaType()) }
    MaterialTheme(colorScheme = scheme) {
        CompositionLocalProvider(LocalDoona provides tokens, LocalContentColor provides tokens.colors.text, content = content)
    }
}

/** The wallpaper's scheme, light or [dark]. */
@Composable
private fun dynamicScheme(dark: Boolean): ColorScheme {
    val context = LocalContext.current
    return remember(context, dark) { if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context) }
}
