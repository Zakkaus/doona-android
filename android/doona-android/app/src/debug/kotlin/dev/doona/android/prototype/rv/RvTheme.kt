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

    /** An unselected destination of the bottom navigation: a step under the secondary text, as Revolut's grey tabs. */
    val navIdle = s.outline

    /** A hairline round a flag in light, where white fields meet the light grey card; dark needs none. */
    val flagEdge: Color? = if (dark) null else s.outlineVariant

    /** Traffic has no good or bad direction, so download and upload take two plain roles of the scheme. */
    val down = RvSeries(s.primary, s.primaryContainer, s.onPrimaryContainer)
    val up = RvSeries(s.tertiary, s.tertiaryContainer, s.onTertiaryContainer)

    /** A chart's dotted reference lines (light grey, as Revolut's) and its faint vertical grid. */
    val reference = s.outline
    val grid = s.outlineVariant

    /** A value pill on a chart: Revolut's small dark chip, the inverse surface in light. */
    val pill = if (dark) s.surfaceContainerHighest else s.inverseSurface
    val onPill = if (dark) s.onSurface else s.inverseOnSurface

    /** The selected range under the detail chart: a chip raised off the page. */
    val chip = if (dark) s.surfaceContainerHighest else s.surfaceContainerHigh
    val stateLayer = s.onSurface

    fun quality(tone: Tone): Color = when (tone) {
        Tone.Ok -> good
        Tone.Warn -> fair
        Tone.Err -> slow
        Tone.Neutral -> textSecondary
    }
}

/** One traffic direction's colours: its chart line and its tile's disc with the glyph on it. */
@Immutable
internal class RvSeries(val line: Color, val disc: Color, val onDisc: Color)

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
    val tileValue = RvTypeScale.tileValue.style(tabular = true)
    val pill = RvTypeScale.pill.style(tabular = true)
    val title = RvTypeScale.title.style()
    val navLabel = RvTypeScale.navLabel.style()

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
