package dev.doona.android.designsystem.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import dev.doona.android.designsystem.tokens.DoonaPalette

/** Text on the surfaces: the body colours, and the tone colours that latency values use. */
@Immutable
class DoonaTextColors(p: DoonaPalette) {
    val primary = p.text
    val secondary = p.subtle
    val disabled = p.muted

    /** Latency text: light keeps body text and shows the tone only in the dot. */
    val positive = if (p.dark) p.foam else p.text
    val notice = if (p.dark) p.gold else p.text
    val negative = if (p.dark) p.love else p.text
}

/** Semantic colour roles (§2, "Semantic roles and where they are used"). */
@Immutable
class DoonaColors(p: DoonaPalette) {
    val dark = p.dark
    val window = p.base
    val surface = p.surface
    val card = p.base
    val cardBorder = p.hlMed.copy(alpha = 0.7f)
    val control = p.overlay
    val line = if (p.dark) p.hlMed else p.hlLow
    val strongLine = p.hlMed
    val text = DoonaTextColors(p)
    val accent = p.pine
    val onAccent = p.onAccent
    val selected = p.selected
    val badgeBorder = p.hlHigh
    val switchOff = p.hlHigh
    val switchKnob = p.surface
    val positive = p.foam
    val notice = p.gold
    val negative = p.love
    val neutral = p.subtle

    /** Flag edge: a 0.5 px black 35 % outline in light, none in dark (`--rp-node-flag-edge`, `src/ui/flags.css:8-13`). */
    val flagEdge: Color? = if (p.dark) null else Color.Black.copy(alpha = 0.35f)

    /** Chart series c1…c6 (§2, chart series table). */
    val c1 = p.pine
    val c2 = p.foam
    val c3 = p.iris
    val c4 = p.gold
    val c5 = p.rose
    val c6 = p.love
}
