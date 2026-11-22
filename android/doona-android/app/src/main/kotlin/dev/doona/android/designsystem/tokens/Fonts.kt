package dev.doona.android.designsystem.tokens

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import dev.doona.android.R

// doona's face (§3): Noto Sans TC variable from @fontsource-variable/noto-sans-tc 5.3.0, SIL OFL 1.1. tools/fonts.py
// instances it at the five weights the type scale uses, keeps only the glyphs the app shows, and applies doona's
// metric overrides (ascent 100 %, descent 20 %, line gap 0, src/fonts-tc.css:4).
val NotoSansTc = FontFamily(
    Font(R.font.noto_sans_tc_w400, FontWeight.Normal),
    Font(R.font.noto_sans_tc_w500, FontWeight.Medium),
    Font(R.font.noto_sans_tc_w600, FontWeight.SemiBold),
    Font(R.font.noto_sans_tc_w700, FontWeight.Bold),
    Font(R.font.noto_sans_tc_w800, FontWeight.ExtraBold),
)
