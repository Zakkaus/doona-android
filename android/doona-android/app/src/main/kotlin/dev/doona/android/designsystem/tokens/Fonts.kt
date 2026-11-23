package dev.doona.android.designsystem.tokens

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import dev.doona.android.R

/**
 * Inter 4.001 at the text optical size (opsz 14), Latin only (rv-fonts/make.py; the prototype's rv_inter_* files). It has
 * no 700 or 800: a step that asks for them gets the 600, the heaviest it has, and Compose fakes no bold over 600.
 */
val Inter = FontFamily(
    Font(R.font.inter_w400, FontWeight.Normal),
    Font(R.font.inter_w500, FontWeight.Medium),
    Font(R.font.inter_w600, FontWeight.SemiBold),
)

/** Inter 4.001 at the display optical size (opsz 32), for figures and titles of 20 sp and up. */
val InterDisplay = FontFamily(
    Font(R.font.inter_display_w600, FontWeight.SemiBold),
    Font(R.font.inter_display_w700, FontWeight.Bold),
)

// doona's face for CJK (§3): Noto Sans TC variable from @fontsource-variable/noto-sans-tc 5.3.0, SIL OFL 1.1. tools/fonts.py
// instances it at the five weights the type scale uses, keeps only the glyphs the app shows, and applies doona's
// metric overrides (ascent 100 %, descent 20 %, line gap 0, src/fonts-tc.css:4). Every run of CJK text is set in it.
val NotoSansTc = FontFamily(
    Font(R.font.noto_sans_tc_w400, FontWeight.Normal),
    Font(R.font.noto_sans_tc_w500, FontWeight.Medium),
    Font(R.font.noto_sans_tc_w600, FontWeight.SemiBold),
    Font(R.font.noto_sans_tc_w700, FontWeight.Bold),
    Font(R.font.noto_sans_tc_w800, FontWeight.ExtraBold),
)
