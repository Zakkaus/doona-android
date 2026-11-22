package dev.doona.android.designsystem.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import dev.doona.android.designsystem.tokens.AlphaScale
import dev.doona.android.designsystem.tokens.ContrastScale
import dev.doona.android.designsystem.tokens.HueScale
import kotlin.math.max
import kotlin.math.min

/**
 * Colour roles. Every hue but the latency qualities is the dynamic scheme's: the surfaces are its neutral roles, and the
 * one tinted area of a screen, the activity header's dot map, is drawn in [dots] on the neutral page. Light is Revolut's
 * flat grey page with white cards (measured §1–2; A10, T11, N13, S06); dark is its black page with cards one step up.
 * Secondary ink and the marks of controls are computed against the surfaces they sit on, never assumed to pass.
 */
@Immutable
class DoonaColors(s: ColorScheme, val dark: Boolean) {
    val page = if (dark) s.surfaceContainerLowest else s.surfaceContainerLow
    val card = if (dark) s.surfaceContainerLow else s.surfaceContainerLowest

    /** The icon disc of a row: one step above the card. */
    val discOnCard = if (dark) s.surfaceContainerHighest else s.surfaceContainerHigh

    /** The round action's disc on the page's header, and the chart's tool discs on the page (measured §1: #ebebf0). */
    val discOnHeader = if (dark) s.surfaceContainerHighest else s.surfaceContainerHigh
    val text = s.onSurface

    /** The accent: the scheme's primary, and what sits on it. */
    val positive = s.primary
    val onPositive = s.onPrimary

    /** The dot map and its marker while connected; neutral while not (S5-10). */
    val dots = s.primary
    val dotsIdle = s.outlineVariant

    /**
     * Glass over the content. The floating capsule's solid fill is white in light and white 11 % over the page in dark,
     * one step above the black page as Revolut's dark controls are (#232325 on #000000), with a rim of white 8 % (S5-20,
     * item 8). It shows that fill collapsed and where the platform cannot blur; over the blur it shows the fill at
     * [navGlass]'s alpha. The top bar's controls are a step darker in light, so they read on the light page; in dark they
     * are white 11 % over the blur with the same rim (L-02).
     */
    private val navSolid = if (dark) Color.White.copy(alpha = AlphaScale.NAV_DARK).compositeOver(page) else Color.White
    val navRim: Color? = if (dark) Color.White.copy(alpha = AlphaScale.NAV_RIM) else null
    val controlGlass = if (dark) {
        DoonaGlassTint(Color.White.copy(alpha = AlphaScale.NAV_DARK), navSolid.copy(alpha = AlphaScale.GLASS_SOLID))
    } else {
        DoonaGlassTint(s.surfaceContainerHigh.copy(alpha = AlphaScale.CONTROL_GLASS), s.surfaceContainerHigh)
    }
    val controlRim: Color? = navRim

    /** The strip behind any bar once content scrolls under it: the page at 85 % over the blur (S5-24). */
    val barGlass = DoonaGlassTint(page.copy(alpha = AlphaScale.BAR_GLASS), page.copy(alpha = AlphaScale.GLASS_SOLID))

    /** The selected item's pill in the capsule: flat and opaque, the capsule's solid fill with the text colour at 8 % (S5-19). */
    val navPill = s.onSurface.copy(alpha = AlphaScale.NAV_PILL).compositeOver(navSolid)

    /** A pushed page's back disc (measured §4; X-04): white with a faint ring in light, a raised container in dark. */
    val backDisc = if (dark) s.surfaceContainerHigh else s.surfaceContainerLowest
    val backRing: Color? = if (dark) null else s.onSurface.copy(alpha = AlphaScale.BACK_RING)

    /** Soft shadows under the capsule, the top bar's controls, raised controls and value pills: in light only. */
    val glassShadow = !dark
    val raisedShadow = !dark

    /** The segmented control's track over whatever it sits on, and the raised segment or range chip on it (measured §8). */
    val segmentTrack = if (dark) Color.White.copy(alpha = AlphaScale.SEGMENT_DARK) else s.onSurface.copy(alpha = AlphaScale.SEGMENT_LIGHT)
    val raised = if (dark) s.surfaceContainerHighest else s.surfaceContainerLowest
    val raisedRim: Color? = if (dark) Color.White.copy(alpha = AlphaScale.RAISED_RIM) else null

    /** The one value pill, on charts, the median and 統計's chips: opaque, on a soft shadow in light (S5-26, T04). */
    val pill = if (dark) s.surfaceContainerHighest else s.surfaceContainerHigh

    /**
     * Every secondary text: subtitles, stat labels, captions, unselected chips and segments, nav labels, legends, pill
     * text. The lightest step from outline toward onSurfaceVariant with 4.6:1 on every surface it sits on (X-09).
     */
    val textSecondary = firstPassing(s.outline, s.onSurfaceVariant, ContrastScale.TEXT, listOf(page, card, navSolid, pill, segmentTrack.compositeOver(page)))

    /** The marks of controls that are not text: chevrons, a switch's off track, a radio's ring, a checkbox's box; 3:1 on page and card (S05). */
    val control = firstPassing(s.outlineVariant, s.outline, ContrastScale.MARK, listOf(page, card))

    /**
     * The capsule over the blur: its solid fill at the lightest alpha from [AlphaScale.NAV_GLASS_MIN] up at which its labels
     * keep 4.5:1 even where the page's text colour lies under it at full strength, so no row shows through under the
     * labels while the capsule moves over the content (item 22). Collapsed it shows the solid fill.
     */
    val navGlass = DoonaGlassTint(navOver(navSolid, s.onSurface), navSolid.copy(alpha = AlphaScale.GLASS_SOLID))

    /** Latency qualities: the only fixed hues, darkened (light) or lightened (dark) until they read on the card and their chip (N02). */
    val good = readableHue(if (dark) HueScale.goodDark else HueScale.goodLight)
    val fair = readableHue(if (dark) HueScale.fairDark else HueScale.fairLight)
    val slow = readableHue(if (dark) HueScale.slowDark else HueScale.slowLight)

    /**
     * The old node tile's ink: its fills ([discOnCard], and [navPill] when current) are surfaces the sample never puts
     * text on, so its secondary text and latency hues take the first steps that also read at 4.5:1 there. The sample's
     * own roles above stay exactly the prototype's.
     */
    val tileInk = DoonaToneInk(
        secondary = firstPassing(
            s.outline,
            s.onSurfaceVariant,
            ContrastScale.TEXT,
            listOf(page, card, navSolid, pill, discOnCard, navPill, segmentTrack.compositeOver(page)),
        ),
        good = readableHue(if (dark) HueScale.goodDark else HueScale.goodLight, listOf(discOnCard, navPill)),
        fair = readableHue(if (dark) HueScale.fairDark else HueScale.fairLight, listOf(discOnCard, navPill)),
        slow = readableHue(if (dark) HueScale.slowDark else HueScale.slowLight, listOf(discOnCard, navPill)),
    )

    /** A progress bar's empty part. */
    val track = discOnCard

    /**
     * The switch (Addendum A, item 1; fix round 2): off, Revolut's light thumb (#fafafa in both themes) over the [control]
     * track; on, the accent thumb over a tint of it between the card and the accent. Each thumb keeps 3:1 against its
     * track, and each track, or its outline, 3:1 against the card. The on track is the first tint that passes both; where
     * none does (a dark accent on a white card), the deepest tint that keeps the thumb at 3:1, outlined in the accent.
     */
    val switchOnThumb = s.primary
    val switchOnTrack = switchTrack(s.primary)
    val switchOnRim: Color? = s.primary.takeIf { contrastRatio(switchOnTrack, card) < ContrastScale.MARK }
    val switchOffThumb = HueScale.switchOffThumb

    /** Categories that are not a direction (統計's quota ring, per subscription): ink and the control grey, never [down] or [up]. */
    val categories = listOf(s.onSurface, control)

    /** A flag's 1 dp ring (X-18), and the disc a country without artwork gets instead. */
    val flagRing = if (dark) Color.White.copy(alpha = AlphaScale.FLAG_RING_DARK) else Color.Black.copy(alpha = AlphaScale.FLAG_RING_LIGHT)
    val flagFallback = s.surfaceContainerHigh

    /** Traffic has no good or bad direction, so download and upload take two plain roles of the scheme. */
    val down = series(s.primary)
    val up = series(s.tertiary)

    /** A sparkline's dotted opening line: light grey on either page. A detail chart's reference line: secondary ink (measured §10.1). */
    val reference = if (dark) s.outline else s.outlineVariant
    val detailReference = textSecondary

    /** The detail chart's hairline grid, a tile's neutral fill and its ghost bars: the text colour, very faint (L-11). */
    val grid = s.onSurface.copy(alpha = if (dark) AlphaScale.GRID_DARK else AlphaScale.GRID_LIGHT)
    val tileFill = s.onSurface.copy(alpha = AlphaScale.TILE_FILL)
    val ghost = s.onSurface.copy(alpha = AlphaScale.GHOST)

    val roundButton = discOnHeader
    val stateLayer = s.onSurface

    // Roles of the old components that the sample has no match for, derived from the scheme with the same contrast rules.

    /** A text button's label: the accent, or the first step from it toward the text colour that reads at 4.5:1 on every fill it sits on. */
    val accentText = firstPassing(s.primary, s.onSurface, ContrastScale.TEXT_MIN, listOf(page, card, discOnCard))

    /** A disabled label: the text colour at the disabled alpha. WCAG sets no minimum for an inactive control. */
    val textDisabled = s.onSurface.copy(alpha = AlphaScale.DISABLED)

    /** A divider and a badge's outline: decorative, the scheme's outline variant. */
    val line = s.outlineVariant

    /** The outline of a disabled outlined button: [control] at a third of its strength, dimmed with its label. */
    val disabledLine = control.copy(alpha = AlphaScale.DISABLED_LINE)

    /** The fill of a destructive button and its label: the scheme's error pair, which Material keeps at 4.5:1. */
    val destructive = s.error
    val onDestructive = s.onError

    /** A status chip's tint of [hue] (node detail's 'Market closed' chip). */
    fun statusChip(hue: Color): Color = hue.copy(alpha = AlphaScale.STATUS_CHIP)

    /** A live point's halo round its dot of [color]. */
    fun halo(color: Color): Color = color.copy(alpha = AlphaScale.HALO)

    /** The underlay of a modal: the scheme's scrim at doona's alpha for the theme. */
    val scrim: Color = s.scrim.copy(alpha = if (dark) AlphaScale.SCRIM_DARK else AlphaScale.SCRIM_LIGHT)

    /** A direction's roles: its line, its tile's disc (the line at one alpha over the card), the detail fill and the band. */
    private fun series(line: Color) = DoonaSeries(
        line = line,
        disc = line.copy(alpha = AlphaScale.SERIES_DISC).compositeOver(card),
        area = line.copy(alpha = if (dark) AlphaScale.AREA_DARK else AlphaScale.AREA_LIGHT),
        band = line.copy(alpha = if (dark) AlphaScale.BAND_DARK else AlphaScale.BAND_LIGHT),
    )

    /** The on track for [thumb]: see [switchOnTrack]. */
    private fun switchTrack(thumb: Color): Color {
        val tints = ramp(card, thumb)
        val keepsThumb = tints.filter { contrastRatio(thumb, it) >= ContrastScale.MARK }
        return keepsThumb.firstOrNull { contrastRatio(it, card) >= ContrastScale.MARK } ?: keepsThumb.lastOrNull() ?: card
    }

    /**
     * [hue], or the first step from it toward black (light) or white (dark) that reads at 4.5:1 on page, card and its
     * chip on either (node detail's status chip sits on the page, N02), and on any [more] fills.
     */
    private fun readableHue(hue: Color, more: List<Color> = emptyList()): Color = ramp(hue, if (dark) Color.White else Color.Black).first { c ->
        val chip = statusChip(c)
        (listOf(page, card, chip.compositeOver(card), chip.compositeOver(page)) + more).all { contrastRatio(c, it) >= ContrastScale.TEXT_MIN }
    }

    /** [solid] at the first alpha from [AlphaScale.NAV_GLASS_MIN] to [AlphaScale.GLASS_SOLID] whose labels read at 4.5:1 over [under]. */
    private fun navOver(solid: Color, under: Color): Color = (0..ContrastScale.STEPS)
        .map { solid.copy(alpha = AlphaScale.NAV_GLASS_MIN + (AlphaScale.GLASS_SOLID - AlphaScale.NAV_GLASS_MIN) * it / ContrastScale.STEPS) }
        .firstOrNull { contrastRatio(textSecondary, it.compositeOver(under)) >= ContrastScale.TEXT_MIN } ?: solid.copy(alpha = AlphaScale.GLASS_SOLID)
}

/** Secondary text and the latency hues of a component that draws on fills the sample never puts text on. */
@Immutable
class DoonaToneInk(val secondary: Color, val good: Color, val fair: Color, val slow: Color)

/** One traffic direction's colours: its chart line, its tile's disc, the detail chart's fill and 今日用量's band. */
@Immutable
class DoonaSeries(val line: Color, val disc: Color, val area: Color, val band: Color)

/** A glass surface's tints: [over] the blur, and [solid] where there is no blur. */
@Immutable
class DoonaGlassTint(val over: Color, val solid: Color)

/** The WCAG contrast ratio of two opaque colours. */
fun contrastRatio(a: Color, b: Color): Float {
    val la = a.luminance()
    val lb = b.luminance()
    return (max(la, lb) + ContrastScale.LUMINANCE_FLARE) / (min(la, lb) + ContrastScale.LUMINANCE_FLARE)
}

/** [from] to [to] in [ContrastScale.STEPS] steps. */
private fun ramp(from: Color, to: Color): List<Color> = (0..ContrastScale.STEPS).map { lerp(from, to, it.toFloat() / ContrastScale.STEPS) }

/** The first of [from] to [to] in [ContrastScale.STEPS] steps that reaches [ratio] on every one of [on]; [to] when none does. */
private fun firstPassing(from: Color, to: Color, ratio: Float, on: List<Color>): Color =
    ramp(from, to).firstOrNull { c -> on.all { contrastRatio(c, it) >= ratio } } ?: to
