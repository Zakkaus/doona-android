package dev.doona.android.designsystem.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemBars
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
import androidx.compose.ui.unit.TextUnit
import dev.doona.android.designsystem.tokens.Dawn
import dev.doona.android.designsystem.tokens.Moon
import dev.doona.android.designsystem.tokens.NotoSansTc
import dev.doona.android.designsystem.tokens.TypeScale

@Immutable
class DoonaTokens(val colors: DoonaColors, val type: DoonaType)

private val LocalDoona = staticCompositionLocalOf<DoonaTokens> { error("DoonaTheme is not set") }

/**
 * Insets of the status bar and the gesture bar. The app reads the window's; a screenshot frame that draws its own
 * bars provides fixed ones.
 */
val LocalFrameInsets = compositionLocalOf<WindowInsets?> { null }

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

    val insets: WindowInsets
        @Composable
        get() = LocalFrameInsets.current ?: WindowInsets.systemBars

    /** Type sizes allowed by the scale (§3), for checks. */
    val scale: List<TextUnit> = TypeScale.sizes
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
    val text = colors.text.primary
    val scheme = if (dark) {
        darkColorScheme(primary = colors.accent, onPrimary = colors.onAccent, background = colors.window, surface = colors.surface, onSurface = text, onBackground = text)
    } else {
        lightColorScheme(primary = colors.accent, onPrimary = colors.onAccent, background = colors.window, surface = colors.surface, onSurface = text, onBackground = text)
    }
    MaterialTheme(colorScheme = scheme) {
        CompositionLocalProvider(LocalDoona provides tokens, LocalContentColor provides text, content = content)
    }
}
