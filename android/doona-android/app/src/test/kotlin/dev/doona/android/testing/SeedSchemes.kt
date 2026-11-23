package dev.doona.android.testing

import android.annotation.SuppressLint
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import com.google.android.material.color.utilities.DynamicColor
import com.google.android.material.color.utilities.Hct
import com.google.android.material.color.utilities.MaterialDynamicColors
import com.google.android.material.color.utilities.SchemeTonalSpot
import dev.doona.android.designsystem.theme.DoonaColors
import dev.doona.android.designsystem.theme.DoonaTheme

/** Wallpaper colours that stand in for dynamic colour, which Robolectric lacks (a copy of the prototype's, with more seeds). */
enum class Seed(val argb: Int) {
    /** The owner's phone on 2026-11-22 (TONAL_SPOT from the wallpaper). */
    Cyan(0xFF3BF9F5.toInt()),

    /** A warm wallpaper: orange. */
    Orange(0xFFE8743B.toInt()),

    /** A dark accent: deep navy, whose primary is dark on the white card. */
    Navy(0xFF1B2A6B.toInt()),
}

/**
 * The scheme Android 12+ derives from a wallpaper colour: material-color-utilities' Tonal Spot (the
 * system default variant) at standard contrast, as bundled in Material Components for Android.
 */
@SuppressLint("RestrictedApi")
fun seedColorScheme(seed: Int, dark: Boolean): ColorScheme {
    val scheme = SchemeTonalSpot(Hct.fromInt(seed), dark, 0.0)
    val m = MaterialDynamicColors()
    fun c(color: DynamicColor) = Color(color.getArgb(scheme))
    return ColorScheme(
        primary = c(m.primary()),
        onPrimary = c(m.onPrimary()),
        primaryContainer = c(m.primaryContainer()),
        onPrimaryContainer = c(m.onPrimaryContainer()),
        inversePrimary = c(m.inversePrimary()),
        secondary = c(m.secondary()),
        onSecondary = c(m.onSecondary()),
        secondaryContainer = c(m.secondaryContainer()),
        onSecondaryContainer = c(m.onSecondaryContainer()),
        tertiary = c(m.tertiary()),
        onTertiary = c(m.onTertiary()),
        tertiaryContainer = c(m.tertiaryContainer()),
        onTertiaryContainer = c(m.onTertiaryContainer()),
        background = c(m.background()),
        onBackground = c(m.onBackground()),
        surface = c(m.surface()),
        onSurface = c(m.onSurface()),
        surfaceVariant = c(m.surfaceVariant()),
        onSurfaceVariant = c(m.onSurfaceVariant()),
        surfaceTint = c(m.surfaceTint()),
        inverseSurface = c(m.inverseSurface()),
        inverseOnSurface = c(m.inverseOnSurface()),
        error = c(m.error()),
        onError = c(m.onError()),
        errorContainer = c(m.errorContainer()),
        onErrorContainer = c(m.onErrorContainer()),
        outline = c(m.outline()),
        outlineVariant = c(m.outlineVariant()),
        scrim = c(m.scrim()),
        surfaceBright = c(m.surfaceBright()),
        surfaceDim = c(m.surfaceDim()),
        surfaceContainer = c(m.surfaceContainer()),
        surfaceContainerHigh = c(m.surfaceContainerHigh()),
        surfaceContainerHighest = c(m.surfaceContainerHighest()),
        surfaceContainerLow = c(m.surfaceContainerLow()),
        surfaceContainerLowest = c(m.surfaceContainerLowest()),
        primaryFixed = c(m.primaryFixed()),
        primaryFixedDim = c(m.primaryFixedDim()),
        onPrimaryFixed = c(m.onPrimaryFixed()),
        onPrimaryFixedVariant = c(m.onPrimaryFixedVariant()),
        secondaryFixed = c(m.secondaryFixed()),
        secondaryFixedDim = c(m.secondaryFixedDim()),
        onSecondaryFixed = c(m.onSecondaryFixed()),
        onSecondaryFixedVariant = c(m.onSecondaryFixedVariant()),
        tertiaryFixed = c(m.tertiaryFixed()),
        tertiaryFixedDim = c(m.tertiaryFixedDim()),
        onTertiaryFixed = c(m.onTertiaryFixed()),
        onTertiaryFixedVariant = c(m.onTertiaryFixedVariant()),
    )
}

/** The theme every production test and golden renders in: the owner's wallpaper (Cyan), light or [dark]. */
@Composable
fun SeedTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val scheme = remember(dark) { seedColorScheme(Seed.Cyan.argb, dark) }
    DoonaTheme(dark, scheme, content)
}

/** The colour roles of [seed]'s scheme, light or [dark], for checks that need no composition. */
fun seedColors(dark: Boolean, seed: Seed = Seed.Cyan) = DoonaColors(seedColorScheme(seed.argb, dark), dark)
