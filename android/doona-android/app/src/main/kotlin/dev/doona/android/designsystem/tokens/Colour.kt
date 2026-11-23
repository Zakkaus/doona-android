package dev.doona.android.designsystem.tokens

import androidx.compose.ui.graphics.Color

/** Alphas the colour roles derive from the scheme's colours. */
object AlphaScale {
    /** The state layer: the content colour over the visible shape while pressed. */
    const val PRESSED = 0.1f

    /** Every chart's hairline grid, in the text colour: about 1.2:1 against the card in both themes (L-11). */
    const val GRID_LIGHT = 0.09f
    const val GRID_DARK = 0.09f

    /** A tile's fill under its sparkline: neutral, the text colour, fading to nothing at the bottom. */
    const val TILE_FILL = 0.08f

    /** The detail chart's fill under its line: the series colour at the line, linear to nothing at the plot's bottom. */
    const val AREA_LIGHT = 0.13f
    const val AREA_DARK = 0.22f

    /** 今日用量's stacked bands: both directions at this one alpha, so only the hue tells them apart (S5-13). */
    const val BAND_LIGHT = 0.3f
    const val BAND_DARK = 0.36f

    /** A tile's corner disc: its series' line colour at this alpha over the card, the same tone for both (S5-11). */
    const val SERIES_DISC = 0.16f

    /** A latency's chip: its hue at this alpha over the card (N02). */
    const val STATUS_CHIP = 0.14f

    /** A value pill's soft shadow in light: two steps of black under it (S5-26, T04). */
    const val PILL_SHADOW_NEAR = 0.08f
    const val PILL_SHADOW_FAR = 0.04f

    /** The floating call to action's soft shadow in light (S7-16). */
    const val CTA_SHADOW = 0.2f

    /** The segmented control's track: the text colour at 6 % in light, white at 8 % in dark (S7-02, T02). */
    const val SEGMENT_LIGHT = 0.06f
    const val SEGMENT_DARK = 0.08f

    /** The ghost bars of a tile with no traffic, in the text colour. */
    const val GHOST = 0.05f

    /** The soft halo round the live point. */
    const val HALO = 0.22f

    /** Whatever is not available: a range the build does not offer yet, a disabled label. */
    const val DISABLED = 0.38f

    /** Glass tints: the top bar's controls over the blur in light, dense enough that a title under them stays a blur (L-08), and any glass's solid fill. */
    const val GLASS_SOLID = 0.94f
    const val CONTROL_GLASS = 0.85f

    /** A raised segment or chip's 1 dp rim in dark, so it reads on a card's track (L-07). */
    const val RAISED_RIM = 0.12f

    /** The strip behind a bar: the page at 85 % over the blur (S5-24). */
    const val BAR_GLASS = 0.85f

    /**
     * The capsule's solid fill in dark: white 11 % over the page, with a rim of white 8 % (S5-20, item 8). Over the blur
     * its fill starts from Revolut's 65 % (measured §9; S5-22) and rises until its labels read over any row (item 22).
     */
    const val NAV_DARK = 0.11f
    const val NAV_RIM = 0.08f
    const val NAV_GLASS_MIN = 0.65f

    /** The selected pill: the capsule's solid fill with the text colour at 8 % on it, flat and opaque (S5-19). */
    const val NAV_PILL = 0.08f

    /** The back disc's ring in light (measured §4: about 1 px of #f2f2f2 round #fcfcfc) and a flag's ring (X-18). */
    const val BACK_RING = 0.06f
    const val FLAG_RING_LIGHT = 0.08f
    const val FLAG_RING_DARK = 0.1f

    /** The soft shadows under the capsule and the top bar's controls, in light (measured §4, §9). */
    const val NAV_SHADOW = 0.16f
    const val CONTROL_SHADOW = 0.06f

    // U1 alphas the sample has no match for, kept while the old dialog and buttons draw with them.

    /** The modal underlay: black at 32 % in light, 55 % in dark (`--rp-underlay`, `foundations.css:6`, `:185`). */
    const val SCRIM_LIGHT = 0.32f
    const val SCRIM_DARK = 0.55f

    /**
     * The outline of a disabled outlined control: a third of an enabled one's strength, so that it is plainly fainter in
     * both themes, where the strong line is only a step off the fill.
     */
    const val DISABLED_LINE = 0.3f
}

/** WCAG contrast targets (rv-final-design §Tokens): text with a margin, text at the minimum, and marks that are not text. */
object ContrastScale {
    const val TEXT = 4.6f
    const val TEXT_MIN = 4.5f
    const val MARK = 3f

    /** Steps of a ramp from one colour to another when a role searches for the first that passes. */
    const val STEPS = 20

    /** WCAG's flare added to both luminances of a contrast ratio. */
    const val LUMINANCE_FLARE = 0.05f
}

/** The only fixed hues: everything else is the dynamic scheme's. */
object HueScale {
    /** Latency qualities, before they are darkened (light) or lightened (dark) until they read on the card and their chip (N02). */
    val goodLight = Color(0xFF13795B)
    val goodDark = Color(0xFF5BC49A)
    val fairLight = Color(0xFF94600A)
    val fairDark = Color(0xFFE7B04E)
    val slowLight = Color(0xFFB3261E)
    val slowDark = Color(0xFFFF8A80)

    /** Revolut's off thumb, in both themes (its dark theme's off switch is #fafafa on #525254; fix round 2, item 1). */
    val switchOffThumb = Color(0xFFFAFAFA)
}
