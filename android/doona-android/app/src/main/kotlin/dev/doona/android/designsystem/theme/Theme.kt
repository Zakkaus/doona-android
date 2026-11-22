package dev.doona.android.designsystem.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemBars
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import dev.doona.android.designsystem.tokens.Dawn
import dev.doona.android.designsystem.tokens.Moon
import dev.doona.android.designsystem.tokens.NotoSansTc

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
}

/**
 * doona's palettes win over Android's dynamic colour here: doona is the reference, and its palette is the product's
 * identity. M3 gets a matching scheme only so its plumbing (ripple, selection) picks doona's colours.
 */
@Composable
fun DoonaTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    // The roles are built once per theme: a staticCompositionLocalOf recomposes everything under it when it changes.
    val colors = remember(dark) { DoonaColors(if (dark) Moon else Dawn) }
    val type = remember { DoonaType(NotoSansTc) }
    val tokens = remember(colors, type) { DoonaTokens(colors, type) }
    val scheme = remember(colors) { materialScheme(colors) }
    MaterialTheme(colorScheme = scheme) {
        CompositionLocalProvider(LocalDoona provides tokens, LocalContentColor provides colors.text.primary, content = content)
    }
}

private fun materialScheme(c: DoonaColors): ColorScheme {
    val text = c.text.primary
    return if (c.dark) {
        darkColorScheme(primary = c.accent, onPrimary = c.onAccent, background = c.window, surface = c.surface, onSurface = text, onBackground = text)
    } else {
        lightColorScheme(primary = c.accent, onPrimary = c.onAccent, background = c.window, surface = c.surface, onSurface = text, onBackground = text)
    }
}
