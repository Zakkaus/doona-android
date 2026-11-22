package dev.doona.android.prototype.rv

import android.os.Build
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.sp
import dev.doona.android.designsystem.components.Tone
import kotlin.math.max
import kotlin.math.min

/**
 * Colour roles of the sample. Every hue but the latency qualities is the dynamic scheme's: the surfaces are its neutral
 * roles, and the one tinted area of a screen, the activity header's dot map, is drawn in [dots] on the neutral page.
 * Light is Revolut's flat grey page with white cards (measured §1–2); dark is its black page with cards one step up.
 * Secondary ink and the marks of controls are computed against the surfaces they sit on, never assumed to pass.
 */
@Immutable
internal class RvColors(s: ColorScheme, val dark: Boolean) {
    val page = if (dark) s.surfaceContainerLowest else s.surfaceContainerLow
    val card = if (dark) s.surfaceContainerLow else s.surfaceContainerLowest

    /** The icon disc of a row: one step above the card. */
    val discOnCard = if (dark) s.surfaceContainerHighest else s.surfaceContainerHigh

    /** The round action's disc on the page's header, and the chart's tool discs on the page (measured §1: #ebebf0). */
    val discOnHeader = if (dark) s.surfaceContainerHighest else s.surfaceContainerHigh
    val text = s.onSurface
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
    private val navSolid = if (dark) Color.White.copy(alpha = RvAlpha.NAV_DARK).compositeOver(page) else Color.White
    val navRim: Color? = if (dark) Color.White.copy(alpha = RvAlpha.NAV_RIM) else null
    val controlGlass = if (dark) {
        RvGlassTint(Color.White.copy(alpha = RvAlpha.NAV_DARK), navSolid.copy(alpha = RvAlpha.GLASS_SOLID))
    } else {
        RvGlassTint(s.surfaceContainerHigh.copy(alpha = RvAlpha.CONTROL_GLASS), s.surfaceContainerHigh)
    }
    val controlRim: Color? = navRim

    /** The strip behind any bar once content scrolls under it: the page at 85 % over the blur. */
    val barGlass = RvGlassTint(page.copy(alpha = RvAlpha.BAR_GLASS), page.copy(alpha = RvAlpha.GLASS_SOLID))

    /** The selected item's pill in the capsule: flat and opaque, the capsule's solid fill with the text colour at 8 %. */
    val navPill = s.onSurface.copy(alpha = RvAlpha.NAV_PILL).compositeOver(navSolid)

    /** A pushed page's back disc (measured §4): white with a faint ring in light, a raised container in dark. */
    val backDisc = if (dark) s.surfaceContainerHigh else s.surfaceContainerLowest
    val backRing: Color? = if (dark) null else s.onSurface.copy(alpha = RvAlpha.BACK_RING)

    /** Soft shadows under the capsule, the top bar's controls, raised controls and value pills: in light only. */
    val glassShadow = !dark
    val raisedShadow = !dark

    /** The segmented control's track over whatever it sits on, and the raised segment or range chip on it (measured §8). */
    val segmentTrack = if (dark) Color.White.copy(alpha = RvAlpha.SEGMENT_DARK) else s.onSurface.copy(alpha = RvAlpha.SEGMENT_LIGHT)
    val raised = if (dark) s.surfaceContainerHighest else s.surfaceContainerLowest
    val raisedRim: Color? = if (dark) Color.White.copy(alpha = RvAlpha.RAISED_RIM) else null

    /** The one value pill, on charts, the median and 統計's chips: opaque, on a soft shadow in light (S5-26, T04). */
    val pill = if (dark) s.surfaceContainerHighest else s.surfaceContainerHigh

    /**
     * Every secondary text: subtitles, stat labels, captions, unselected chips and segments, nav labels, legends, pill
     * text. The lightest step from outline toward onSurfaceVariant with 4.6:1 on every surface it sits on (X-09).
     */
    val textSecondary = firstPassing(s.outline, s.onSurfaceVariant, RvContrast.TEXT, listOf(page, card, navSolid, pill, segmentTrack.compositeOver(page)))

    /** The marks of controls that are not text: chevrons, a switch's off track, a radio's ring; 3:1 on page and card. */
    val control = firstPassing(s.outlineVariant, s.outline, RvContrast.MARK, listOf(page, card))

    /**
     * The capsule over the blur: its solid fill at the lightest alpha from [RvAlpha.NAV_GLASS_MIN] up at which its labels
     * keep 4.5:1 even where the page's text colour lies under it at full strength, so no row shows through under the
     * labels while the capsule moves over the content (item 22). Collapsed it shows the solid fill.
     */
    val navGlass = RvGlassTint(navOver(navSolid, s.onSurface), navSolid.copy(alpha = RvAlpha.GLASS_SOLID))

    /** Latency qualities: the only fixed hues, darkened (light) or lightened (dark) until they read on the card and their chip. */
    val good = readableHue(Color(if (dark) 0xFF5BC49A else 0xFF13795B))
    val fair = readableHue(Color(if (dark) 0xFFE7B04E else 0xFF94600A))
    val slow = readableHue(Color(if (dark) 0xFFFF8A80 else 0xFFB3261E))

    /** A progress bar's empty part. */
    val track = discOnCard

    /**
     * An error's glyph, message and field ring: the slow latency's red, the scheme's one red (Revolut's negative,
     * measured §1), already readable on page, card and its chip. A destructive row keeps its title in ink and only its
     * glyph in this hue (measured §13.2).
     */
    val error = slow

    /** The scrim under a modal sheet (measured §2). */
    val scrim = Color.Black.copy(alpha = RvAlpha.SCRIM)

    /**
     * The switch (Addendum A, item 1): off, Revolut's light thumb (#fafafa in both themes) over the [control] track; on,
     * the accent thumb over a tint of it between the card and the accent. Each thumb keeps 3:1 against its track, and each
     * track, or its outline, 3:1 against the card. The on track is the first tint that passes both; where none does (a dark
     * accent on a white card), the deepest tint that keeps the thumb at 3:1, outlined in the accent.
     */
    val switchOnThumb = s.primary
    val switchOnTrack = switchTrack(s.primary)
    val switchOnRim: Color? = s.primary.takeIf { rvContrast(switchOnTrack, card) < RvContrast.MARK }
    val switchOffThumb = SWITCH_OFF_THUMB

    /** Categories that are not a direction (統計's quota ring, per subscription): ink and the control grey, never [down] or [up]. */
    val categories = listOf(s.onSurface, control)

    /** A flag's 1 dp ring (X-18), and the disc a country without artwork gets instead. */
    val flagRing = if (dark) Color.White.copy(alpha = RvAlpha.FLAG_RING_DARK) else Color.Black.copy(alpha = RvAlpha.FLAG_RING_LIGHT)
    val flagFallback = s.surfaceContainerHigh

    /** Traffic has no good or bad direction, so download and upload take two plain roles of the scheme. */
    val down = series(s.primary)
    val up = series(s.tertiary)

    /** A sparkline's dotted opening line: light grey on either page. A detail chart's reference line: secondary ink (measured §10.1). */
    val reference = if (dark) s.outline else s.outlineVariant
    val detailReference = textSecondary

    /** The detail chart's hairline grid, a tile's neutral fill and its ghost bars: the text colour, very faint. */
    val grid = s.onSurface.copy(alpha = if (dark) RvAlpha.GRID_DARK else RvAlpha.GRID_LIGHT)
    val tileFill = s.onSurface.copy(alpha = RvAlpha.TILE_FILL)
    val ghost = s.onSurface.copy(alpha = RvAlpha.GHOST)

    val roundButton = discOnHeader
    val stateLayer = s.onSurface

    fun quality(tone: Tone): Color = when (tone) {
        Tone.Ok -> good
        Tone.Warn -> fair
        Tone.Err -> slow
        Tone.Neutral -> textSecondary
    }

    /** A direction's roles: its line, its tile's disc (the line at one alpha over the card), the detail fill and the band. */
    private fun series(line: Color) = RvSeries(
        line = line,
        disc = line.copy(alpha = RvAlpha.SERIES_DISC).compositeOver(card),
        area = line.copy(alpha = if (dark) RvAlpha.AREA_DARK else RvAlpha.AREA_LIGHT),
        band = line.copy(alpha = if (dark) RvAlpha.BAND_DARK else RvAlpha.BAND_LIGHT),
    )

    /** The on track for [thumb]: see [switchOnTrack]. */
    private fun switchTrack(thumb: Color): Color {
        val tints = (0..RvContrast.STEPS).map { lerp(card, thumb, it.toFloat() / RvContrast.STEPS) }
        val keepsThumb = tints.filter { rvContrast(thumb, it) >= RvContrast.MARK }
        return keepsThumb.firstOrNull { rvContrast(it, card) >= RvContrast.MARK } ?: keepsThumb.lastOrNull() ?: card
    }

    /**
     * [hue], or the first step from it toward black (light) or white (dark) that reads at 4.5:1 on page, card and its
     * chip on either (node detail's status chip sits on the page, N02).
     */
    private fun readableHue(hue: Color): Color {
        val toward = if (dark) Color.White else Color.Black
        return (0..RvContrast.STEPS).map { lerp(hue, toward, it.toFloat() / RvContrast.STEPS) }.first { c ->
            val chip = c.copy(alpha = RvAlpha.STATUS_CHIP)
            listOf(page, card, chip.compositeOver(card), chip.compositeOver(page)).all { rvContrast(c, it) >= RvContrast.TEXT_MIN }
        }
    }
}

/** One traffic direction's colours: its chart line, its tile's disc, the detail chart's fill and 今日用量's band. */
@Immutable
internal class RvSeries(val line: Color, val disc: Color, val area: Color, val band: Color)

/** WCAG contrast targets: text with a margin, text at the minimum, and marks that are not text; [STEPS] of a ramp. */
internal object RvContrast {
    const val TEXT = 4.6f
    const val TEXT_MIN = 4.5f
    const val MARK = 3f
    const val STEPS = 20
}

/** [solid] at the first alpha from [RvAlpha.NAV_GLASS_MIN] to [RvAlpha.GLASS_SOLID] whose labels read at 4.5:1 over [under]. */
private fun RvColors.navOver(solid: Color, under: Color): Color =
    (0..RvContrast.STEPS).map { solid.copy(alpha = RvAlpha.NAV_GLASS_MIN + (RvAlpha.GLASS_SOLID - RvAlpha.NAV_GLASS_MIN) * it / RvContrast.STEPS) }
        .firstOrNull { rvContrast(textSecondary, it.compositeOver(under)) >= RvContrast.TEXT_MIN } ?: solid.copy(alpha = RvAlpha.GLASS_SOLID)

/** Revolut's off thumb, in both themes (its dark theme's off switch is #fafafa on #525254). */
private val SWITCH_OFF_THUMB = Color(0xFFFAFAFA)

/** The WCAG contrast ratio of two opaque colours. */
internal fun rvContrast(a: Color, b: Color): Float {
    val la = a.luminance()
    val lb = b.luminance()
    return (max(la, lb) + LUMINANCE_FLARE) / (min(la, lb) + LUMINANCE_FLARE)
}

/** The first of [from] to [to] in [RvContrast.STEPS] steps that reaches [ratio] on every one of [on]; [to] when none does. */
private fun firstPassing(from: Color, to: Color, ratio: Float, on: List<Color>): Color =
    (0..RvContrast.STEPS).map { lerp(from, to, it.toFloat() / RvContrast.STEPS) }.firstOrNull { c -> on.all { rvContrast(c, it) >= ratio } } ?: to

private const val LUMINANCE_FLARE = 0.05f

@Immutable
internal class RvType {
    val hero = RvTypeScale.hero.style(tabular = true)
    val heroSmall = RvTypeScale.heroSmall.style(tabular = true)
    val detailFigure = RvTypeScale.detailFigure.style(tabular = true)
    val detailUnit = RvTypeScale.detailUnit.style()
    val caption = RvTypeScale.caption.style()
    val sub = RvTypeScale.sub.style(tabular = true)
    val rowTitle = RvTypeScale.rowTitle.style()

    /** A figure in a row's title size: a trailing value or a legend's rate. */
    val rowValue = RvTypeScale.rowTitle.style(tabular = true)
    val rowSub = RvTypeScale.rowSub.style()
    val action = RvTypeScale.action.style()
    val segment = RvTypeScale.segment.style()
    val tileCaption = RvTypeScale.tileCaption.style()
    val tileValue = RvTypeScale.tileValue.style(tabular = true)
    val tileUnit = RvTypeScale.tileUnit.style()
    val cardValue = RvTypeScale.cardValue.style(tabular = true)
    val cardUnit = RvTypeScale.cardUnit.style()
    val pill = RvTypeScale.pill.style(tabular = true)
    val section = RvTypeScale.section.style()
    val statLabel = RvTypeScale.statLabel.style()
    val stat = RvTypeScale.stat.style(tabular = true)
    val field = RvTypeScale.field.style()
    val largeTitle = RvTypeScale.largeTitle.style()
    val barTitle = RvTypeScale.barTitle.style()
    val navLabel = RvTypeScale.navLabel.style()
    val flagCode = RvTypeScale.flagCode.style()
    val search = RvTypeScale.search.style()
    val cardTitle = RvTypeScale.cardTitle.style()
    val seeAll = RvTypeScale.seeAll.style()

    // Tabular figures only where figures line up or change in place: Inter's tnum also widens the hyphen of a name.
    private fun RvTypeStep.style(tabular: Boolean = false) = TextStyle(
        fontFamily = if (display) RvInterDisplay else RvInter,
        fontWeight = weight,
        fontSize = size.sp,
        lineHeight = line.sp,
        fontFeatureSettings = if (tabular) RvTypeScale.TABULAR else null,
    )
}

@Immutable
internal class RvTokens(val colors: RvColors, val type: RvType)

private val LocalRv = staticCompositionLocalOf<RvTokens> { error("RvTheme is not set") }

internal object Rv {
    val colors: RvColors
        @Composable @ReadOnlyComposable
        get() = LocalRv.current.colors
    val type: RvType
        @Composable @ReadOnlyComposable
        get() = LocalRv.current.type
}

@Composable
internal fun RvTheme(scheme: ColorScheme, dark: Boolean, content: @Composable () -> Unit) {
    val tokens = remember(scheme, dark) { RvTokens(RvColors(scheme, dark), RvType()) }
    CompositionLocalProvider(LocalRv provides tokens, content = content)
}

/** The wallpaper's scheme on Android 12 and later; below that there is no wallpaper colour, so Material's baseline. */
@Composable
internal fun rvSystemScheme(dark: Boolean): ColorScheme {
    val context = LocalContext.current
    return when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
}

/** [text] with every run of CJK set in Noto Sans TC; the rest keeps the style's Inter. */
internal fun scripted(text: String): AnnotatedString = buildAnnotatedString {
    var start = 0
    while (start < text.length) {
        val cjk = text[start].isCjk()
        var end = start + 1
        while (end < text.length && (text[end].isCjk() == cjk || text[end] == ' ')) end++
        if (cjk) withStyle(SpanStyle(fontFamily = RvNoto)) { append(text, start, end) } else append(text, start, end)
        start = end
    }
}

private fun Char.isCjk() = code >= CJK_START

/** CJK Radicals Supplement: everything from here up is set in Noto. */
private const val CJK_START = 0x2E80
