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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.sp
import dev.doona.android.designsystem.components.Tone

/**
 * Colour roles of the sample. Every hue but the latency qualities is the dynamic scheme's: the surfaces are its neutral
 * roles, and the one tinted area of a screen, the activity header's dot map, is drawn in [dots] on the neutral page.
 * Dark is Revolut's black page with cards one step up; light is the same steps from white, with light grey cards.
 */
@Immutable
internal class RvColors(s: ColorScheme, val dark: Boolean) {
    val page = s.surfaceContainerLowest
    val card = if (dark) s.surfaceContainerLow else s.surfaceContainer

    /** The icon disc of a row: one step above the card. */
    val discOnCard = if (dark) s.surfaceContainerHighest else s.surfaceContainerLowest

    /** The round action's disc on the header's fade. */
    val discOnHeader = if (dark) s.surfaceContainerHighest else s.surfaceContainerHigh
    val text = s.onSurface
    val textSecondary = s.onSurfaceVariant
    val positive = s.primary
    val onPositive = s.onPrimary

    /** The dot map and its marker. */
    val dots = s.primary

    /** Latency qualities: the only fixed hues, so good, fair and slow read the same on every wallpaper. */
    val good = Color(if (dark) 0xFF5BC49A else 0xFF13795B)
    val fair = Color(if (dark) 0xFFE7B04E else 0xFF94600A)
    val slow = Color(if (dark) 0xFFFF8A80 else 0xFFB3261E)

    /** A progress bar's empty part. */
    val track = discOnCard

    /**
     * Glass over the content. The floating capsule: white 65 % over the blur in light, as Revolut's 35 to 65 % white
     * (measured §9); white 11 % in dark with a rim of white 8 %, so it does not dissolve into the black page (S5-20). Its
     * solid fill is what it shows collapsed and where the platform cannot blur. The top bar's controls are a step darker,
     * so they read on the light page.
     */
    private val navSolid = if (dark) Color.White.copy(alpha = RvAlpha.NAV_DARK).compositeOver(page) else Color.White
    val navGlass = RvGlassTint(Color.White.copy(alpha = if (dark) RvAlpha.NAV_DARK else RvAlpha.NAV_LIGHT), navSolid.copy(alpha = RvAlpha.GLASS_SOLID))
    val navRim: Color? = if (dark) Color.White.copy(alpha = RvAlpha.NAV_RIM) else null
    val controlGlass = if (dark) {
        RvGlassTint(s.surfaceContainerHighest.copy(alpha = RvAlpha.GLASS_DARK), s.surfaceContainerHighest)
    } else {
        RvGlassTint(s.surfaceContainerHigh.copy(alpha = RvAlpha.CONTROL_GLASS), s.surfaceContainerHigh)
    }

    /** The strip behind any bar once content scrolls under it: the page at 85 % over the blur. */
    val barGlass = RvGlassTint(page.copy(alpha = RvAlpha.BAR_GLASS), page.copy(alpha = RvAlpha.GLASS_SOLID))

    /** The selected item's pill in the capsule: flat and opaque, the capsule's solid fill with the text colour at 8 %. */
    val navPill = s.onSurface.copy(alpha = RvAlpha.NAV_PILL).compositeOver(navSolid)

    /** A pushed page's back disc (measured §4): white with a faint ring in light, a raised container in dark. */
    val backDisc = if (dark) s.surfaceContainerHigh else s.surfaceContainerLowest
    val backRing: Color? = if (dark) null else s.onSurface.copy(alpha = RvAlpha.BACK_RING)

    /** Soft shadows under the capsule and the top bar's controls, in light only. */
    val glassShadow = !dark

    /** A subscription's disc and the glyph on it, one dynamic role each, as Revolut colours its Total wealth rows. */
    val accents = listOf(s.primary to s.onPrimary, s.tertiary to s.onTertiary)

    /** A flag's 1 dp ring (X-18), and the disc a country without artwork gets instead. */
    val flagRing = if (dark) Color.White.copy(alpha = RvAlpha.FLAG_RING_DARK) else Color.Black.copy(alpha = RvAlpha.FLAG_RING_LIGHT)
    val flagFallback = s.surfaceContainerHigh

    /** Traffic has no good or bad direction, so download and upload take two plain roles of the scheme. */
    private val area = if (dark) RvAlpha.AREA_DARK else RvAlpha.AREA_LIGHT
    val down = RvSeries(s.primary, s.primaryContainer, s.onPrimaryContainer, s.primary.copy(alpha = area))
    val up = RvSeries(s.tertiary, s.tertiaryContainer, s.onTertiaryContainer, s.tertiary.copy(alpha = area))

    /** A chart's dotted reference line: light grey, as Revolut's, on either page. */
    val reference = if (dark) s.outline else s.outlineVariant

    /** The detail chart's hairline grid, a tile's neutral fill and its ghost bars: the text colour, very faint. */
    val grid = s.onSurface.copy(alpha = if (dark) RvAlpha.GRID_DARK else RvAlpha.GRID_LIGHT)
    val tileFill = s.onSurface.copy(alpha = RvAlpha.TILE_FILL)
    val ghost = s.onSurface.copy(alpha = RvAlpha.GHOST)

    /** The line down the detail chart while a finger is on it. */
    val scrub = s.outline

    /** A value pill on a chart: a surface pill with a hairline edge in light, a raised container in dark. */
    val pill = if (dark) s.surfaceContainerHighest else s.surfaceContainerLowest
    val pillEdge: Color? = if (dark) null else s.outlineVariant
    val onPill = s.onSurfaceVariant

    /** A raised control on the page: the selected range chip and segment, on a shadow in light, a lighter fill in dark. */
    val raised = if (dark) s.surfaceContainerHighest else s.surfaceContainerLowest
    val raisedShadow = !dark

    /** The segmented control's track, and the round buttons after the range chips. */
    val segmentTrack = if (dark) s.surfaceContainerLow else s.surfaceContainer
    val roundButton = discOnHeader
    val stateLayer = s.onSurface

    fun quality(tone: Tone): Color = when (tone) {
        Tone.Ok -> good
        Tone.Warn -> fair
        Tone.Err -> slow
        Tone.Neutral -> textSecondary
    }
}

/** One traffic direction's colours: its chart line, its tile's disc with the glyph on it, and the detail chart's fill. */
@Immutable
internal class RvSeries(val line: Color, val disc: Color, val onDisc: Color, val area: Color)

@Immutable
internal class RvType {
    val hero = RvTypeScale.hero.style(tabular = true)
    val heroSmall = RvTypeScale.heroSmall.style(tabular = true)
    val caption = RvTypeScale.caption.style()
    val sub = RvTypeScale.sub.style(tabular = true)
    val rowTitle = RvTypeScale.rowTitle.style()

    /** A figure in a row's title size: a trailing value or a legend's rate. */
    val rowValue = RvTypeScale.rowTitle.style(tabular = true)
    val rowSub = RvTypeScale.rowSub.style()
    val action = RvTypeScale.action.style()
    val tileCaption = RvTypeScale.tileCaption.style()
    val tileValue = RvTypeScale.tileValue.style(tabular = true)
    val tileUnit = RvTypeScale.tileUnit.style()
    val cardValue = RvTypeScale.cardValue.style(tabular = true)
    val cardUnit = RvTypeScale.cardUnit.style()
    val pill = RvTypeScale.pill.style(tabular = true)
    val section = RvTypeScale.section.style()
    val stat = RvTypeScale.stat.style(tabular = true)
    val largeTitle = RvTypeScale.largeTitle.style()
    val barTitle = RvTypeScale.barTitle.style()
    val navLabel = RvTypeScale.navLabel.style()
    val flagCode = RvTypeScale.flagCode.style()
    val search = RvTypeScale.search.style()
    val cardTitle = RvTypeScale.cardTitle.style()

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
