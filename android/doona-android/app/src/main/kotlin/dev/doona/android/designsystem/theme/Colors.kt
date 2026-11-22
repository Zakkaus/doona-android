package dev.doona.android.designsystem.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import dev.doona.android.designsystem.tokens.AlphaScale
import dev.doona.android.designsystem.tokens.DoonaPalette

/** Text on the surfaces: the body colours, and the tone colours that latency values use. */
@Immutable
class DoonaTextColors(p: DoonaPalette) {
    val primary = p.text
    val secondary = p.subtleText
    val disabled = p.muted

    /** Latency text: light keeps body text and shows the tone only in the dot. */
    val positive = if (p.dark) p.foam else p.text
    val notice = if (p.dark) p.gold else p.text
    val negative = if (p.dark) p.loveText else p.text
}

/** Semantic colour roles (§2, "Semantic roles and where they are used"). */
@Immutable
class DoonaColors(p: DoonaPalette) {
    val dark = p.dark
    val window = p.base
    val surface = p.surface
    val card = p.card
    val control = p.overlay
    val line = if (p.dark) p.hlMed else p.hlLow
    val strongLine = p.hlMed
    val text = DoonaTextColors(p)
    val accent = p.pine

    /** The one colour of a decorative glyph: icons in tiles and rows are neutral, so colour is left to status and the accent. */
    val icon = p.subtleText
    val onAccent = p.onAccent
    val selected = p.selected
    val badgeBorder = p.hlHigh

    /**
     * Switch, off: the track fill, and the outline and knob drawn over it in the secondary text colour. The outline is
     * 3:1 against the card and the knob against the track, which `hlHigh` was not (1.5:1 and 2.1:1); ContrastTest holds it.
     */
    val switchOff = p.overlay
    val switchOffLine = p.subtleText

    /** Switch, on: the knob over the accent track. */
    val switchKnob = p.surface
    val positive = p.foam
    val notice = p.gold
    val negative = p.love
    val neutral = p.subtle

    /** Flag edge: a 0.5 px black 35 % outline in light, none in dark (`--rp-node-flag-edge`, `src/ui/flags.css:8-13`). */
    val flagEdge: Color? = if (p.dark) null else Color.Black.copy(alpha = AlphaScale.FLAG_EDGE)

    /**
     * The two series of the traffic chart (download, upload). A chart needs a hue to tell lines apart; nothing else on a
     * page uses one for decoration, so there are no more series than a chart shows.
     */
    val series1 = p.pine
    val series2 = p.gold
}
