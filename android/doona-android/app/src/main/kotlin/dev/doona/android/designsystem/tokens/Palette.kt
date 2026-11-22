package dev.doona.android.designsystem.tokens

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

// Section numbers (§) refer to /scratch/ssd/honk-android/sketch-doona/TOKENS.md.

/** Raw Rosé Pine palette (§2): Dawn for light, Moon for dark, doona's default `rose-pine/moon`. */
@Immutable
data class DoonaPalette(
    val base: Color,
    val surface: Color,
    val overlay: Color,
    val muted: Color,
    val subtle: Color,
    /** `subtle` darkened (Dawn) or lightened (Moon) to 4.5:1 on every fill secondary text sits on, which `subtle` itself misses. */
    val subtleText: Color,
    val text: Color,
    val love: Color,
    /** `love` lightened to 4.5:1 on Moon's fills, for error text; Dawn uses body text for tones, so it keeps `love`. */
    val loveText: Color,
    val gold: Color,
    val rose: Color,
    val pine: Color,
    val foam: Color,
    val iris: Color,
    val hlLow: Color,
    val hlMed: Color,
    val hlHigh: Color,
    /** `--rp-selected`: accent 10 % over overlay, measured. */
    val selected: Color,
    val onAccent: Color,
    val dark: Boolean,
)

val Dawn = DoonaPalette(
    base = Color(0xFFFAF4ED),
    surface = Color(0xFFFFFAF3),
    overlay = Color(0xFFF2E9E1),
    muted = Color(0xFF9893A5),
    subtle = Color(0xFF797593),
    subtleText = Color(0xFF605C7A),
    text = Color(0xFF464261),
    love = Color(0xFFB4637A),
    loveText = Color(0xFFB4637A),
    gold = Color(0xFFEA9D34),
    rose = Color(0xFFD7827E),
    pine = Color(0xFF286983),
    foam = Color(0xFF56949F),
    iris = Color(0xFF907AA9),
    hlLow = Color(0xFFF4EDE8),
    hlMed = Color(0xFFDFDAD9),
    hlHigh = Color(0xFFCECACD),
    selected = Color(0xFFDEDCD8),
    onAccent = Color(0xFFFFFAF3),
    dark = false,
)

val Moon = DoonaPalette(
    base = Color(0xFF232136),
    surface = Color(0xFF2A273F),
    overlay = Color(0xFF393552),
    muted = Color(0xFF6E6A86),
    subtle = Color(0xFF908CAA),
    subtleText = Color(0xFFACA9C4),
    text = Color(0xFFE0DEF4),
    love = Color(0xFFEB6F92),
    loveText = Color(0xFFE796B4),
    gold = Color(0xFFF6C177),
    rose = Color(0xFFEA9A97),
    pine = Color(0xFF3E8FB0),
    foam = Color(0xFF9CCFD8),
    iris = Color(0xFFC4A7E7),
    hlLow = Color(0xFF2A283E),
    hlMed = Color(0xFF44415A),
    hlHigh = Color(0xFF56526E),
    selected = Color(0xFF3A3E5B),
    onAccent = Color(0xFF191724),
    dark = true,
)
