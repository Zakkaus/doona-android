package dev.doona.android.designsystem.rv

import androidx.annotation.StringRes
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import dev.doona.android.R
import dev.doona.android.designsystem.theme.DoonaTheme

/** One accent: its name for a screen reader and its primary in the light theme. */
internal class RvAccent(@StringRes val name: Int, val light: Color)

/** The four fixed colours the theme page offers after the first swatch, which is the scheme's own primary (S8-10). */
internal val RV_ACCENTS = listOf(
    RvAccent(R.string.rv_settings_accent_blue, RvAccentColors.blue),
    RvAccent(R.string.rv_settings_accent_purple, RvAccentColors.purple),
    RvAccent(R.string.rv_settings_accent_orange, RvAccentColors.orange),
    RvAccent(R.string.rv_settings_accent_pink, RvAccentColors.pink),
)

/** The accent as a primary: as it is in light, lifted toward white in dark so it keeps its contrast on the black page. */
internal fun RvAccent.primary(dark: Boolean): Color = if (dark) lerp(light, Color.White, RvAccentColors.DARK_LIFT) else light

/**
 * This scheme under swatch [accent]: itself for 0 (the scheme's own primary), else with the [accent]-th of [RV_ACCENTS]
 * for its primary roles, which are what the 活動 page's dots, positive hue and download line read.
 */
internal fun ColorScheme.accented(accent: Int, dark: Boolean): ColorScheme {
    if (accent == 0) return this
    val primary = RV_ACCENTS[accent - 1].primary(dark)
    return copy(
        primary = primary,
        onPrimary = if (dark) RvAccentColors.onPrimaryDark else Color.White,
        primaryContainer = if (dark) lerp(primary, Color.Black, RvAccentColors.CONTAINER_DARK) else lerp(primary, Color.White, RvAccentColors.CONTAINER_LIGHT),
        onPrimaryContainer = onSurface,
    )
}

/** A light and a dark scheme, the wallpaper's in the app and fixed seeds in tests, which pages pass on without opening. */
@Immutable
internal class RvSchemes(private val light: ColorScheme, private val dark: ColorScheme) {
    /** The scheme for [dark] or light under swatch [accent] ([accented]). */
    fun scheme(dark: Boolean, accent: Int = 0): ColorScheme = (if (dark) this.dark else light).accented(accent, dark)

    /** The RV roles of the light or [dark] scheme, without an accent: the theme page's mode pictures. */
    fun colors(dark: Boolean): RvColors = RvColors(scheme(dark), dark)

    /** The scheme's own primary, the first swatch. */
    fun primary(dark: Boolean): Color = scheme(dark).primary
}

/** The wallpaper's two schemes ([rvSystemScheme]). */
@Composable
internal fun rvSystemSchemes(): RvSchemes = RvSchemes(rvSystemScheme(false), rvSystemScheme(true))

/** RV's tokens on [schemes]' light or [dark] scheme under swatch [accent]. */
@Composable
internal fun RvTheme(schemes: RvSchemes, dark: Boolean, accent: Int, content: @Composable () -> Unit) {
    RvTheme(schemes.scheme(dark, accent), dark, content)
}

/** RV's tokens on the scheme the old pages' [DoonaTheme] wears, for an RV page the app shows among the old ones. */
@Composable
internal fun RvOverDoona(content: @Composable () -> Unit) {
    RvTheme(MaterialTheme.colorScheme, DoonaTheme.colors.dark, content)
}

/** The old pages' [DoonaTheme] on the scheme the RV theme around it was built from, for an old page inside an RV one. */
@Composable
internal fun RvOldPage(content: @Composable () -> Unit) {
    DoonaTheme(Rv.colors.dark, LocalRvScheme.current, content)
}
