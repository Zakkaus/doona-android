package dev.tunbench.preview.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import dev.tunbench.preview.R

/**
 * Google Sans, the typeface of Google Health and Google Home, from Google Fonts under the SIL Open Font
 * License 1.1 (github.com/google/fonts, `ofl/googlesans/GoogleSans[GRAD,opsz,wght].ttf`, read 2026-11-22).
 * The two weights the references use are static instances of that variable file (fontTools instancer,
 * opsz 18, GRAD 0, wght 400 and 500; see NOTICE), so every renderer draws the same weight. It has no
 * CJK glyphs, so Chinese falls back to the system CJK face, as it does in Google's apps.
 */
val GoogleSans = FontFamily(
    Font(R.font.google_sans_regular, FontWeight.Normal),
    Font(R.font.google_sans_medium, FontWeight.Medium),
)

/** A Google Sans text style of [size] sp on a [line] sp line. */
internal fun sans(size: Int, line: Int, weight: FontWeight = FontWeight.Normal) = TextStyle(
    fontFamily = GoogleSans,
    fontSize = size.sp,
    lineHeight = line.sp,
    fontWeight = weight,
    letterSpacing = 0.sp,
)

/** The Material type scale with Google Sans in place of Roboto, so library components match the screen. */
internal fun Typography.inGoogleSans(): Typography = Typography(
    displayLarge = displayLarge.copy(fontFamily = GoogleSans),
    displayMedium = displayMedium.copy(fontFamily = GoogleSans),
    displaySmall = displaySmall.copy(fontFamily = GoogleSans),
    headlineLarge = headlineLarge.copy(fontFamily = GoogleSans),
    headlineMedium = headlineMedium.copy(fontFamily = GoogleSans),
    headlineSmall = headlineSmall.copy(fontFamily = GoogleSans),
    titleLarge = titleLarge.copy(fontFamily = GoogleSans),
    titleMedium = titleMedium.copy(fontFamily = GoogleSans),
    titleSmall = titleSmall.copy(fontFamily = GoogleSans),
    bodyLarge = bodyLarge.copy(fontFamily = GoogleSans),
    bodyMedium = bodyMedium.copy(fontFamily = GoogleSans),
    bodySmall = bodySmall.copy(fontFamily = GoogleSans),
    labelLarge = labelLarge.copy(fontFamily = GoogleSans),
    labelMedium = labelMedium.copy(fontFamily = GoogleSans),
    labelSmall = labelSmall.copy(fontFamily = GoogleSans),
)
