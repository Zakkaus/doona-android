package dev.doona.android.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import dev.doona.android.R
import dev.doona.android.designsystem.theme.DoonaColors
import dev.doona.android.designsystem.theme.DoonaTheme

enum class Tone { Ok, Warn, Err, Neutral }

/** Latency colours (`src/ui/Tile.tsx:79`): under 100 ms ok, under 300 ms warn, otherwise err. */
fun latencyTone(ms: Int?): Tone = when {
    ms == null -> Tone.Neutral
    ms < 100 -> Tone.Ok
    ms < 300 -> Tone.Warn
    else -> Tone.Err
}

fun toneColor(c: DoonaColors, tone: Tone): Color = when (tone) {
    Tone.Ok -> c.positive
    Tone.Warn -> c.notice
    Tone.Err -> c.negative
    Tone.Neutral -> c.neutral
}

/** The dot of a status, centred on the first line of text of [style]. */
@Composable
private fun LineDot(tone: Tone, style: TextStyle, dot: Dp) {
    Box(Modifier.height(lineHeightOf(style)), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(dot)
                .clip(DoonaTheme.shape.pill)
                .background(toneColor(DoonaTheme.colors, tone)),
        )
    }
}

@Composable
private fun StatusLight(tone: Tone, text: String, modifier: Modifier, style: TextStyle, color: Color, dot: Dp, gap: Dp) {
    Row(modifier, verticalAlignment = Alignment.Top) {
        LineDot(tone, style, dot)
        Spacer(Modifier.width(gap))
        Text(text, style = style, color = color, maxLines = 1, softWrap = false)
    }
}

/** Status light (§7): a dot and a label, the dot centred on the label's first line. 10 px dot, gap 8. */
@Composable
fun DoonaStatusLight(
    tone: Tone,
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = DoonaTheme.type.body,
    color: Color = DoonaTheme.colors.text.primary,
) = StatusLight(tone, text, modifier, style, color, DoonaTheme.size.dot, DoonaTheme.spacing.item)

/** The small status light (§7): the same with an 8 px dot, for counts that sit among text. */
@Composable
fun DoonaSmallStatusLight(
    tone: Tone,
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = DoonaTheme.type.body,
    color: Color = DoonaTheme.colors.text.primary,
) = StatusLight(tone, text, modifier, style, color, DoonaTheme.size.dotSmall, DoonaTheme.spacing.item)

/** The latency text role of a tone: light keeps body text and shows the tone only in the dot. */
@Composable
private fun latencyColor(tone: Tone): Color {
    val text = DoonaTheme.colors.text
    return when (tone) {
        Tone.Ok -> text.positive
        Tone.Warn -> text.notice
        Tone.Err -> text.negative
        Tone.Neutral -> text.secondary
    }
}

/** The word for a latency's tone, so that the quality is said and not only coloured. */
@Composable
private fun latencyQuality(tone: Tone): String = stringResource(
    when (tone) {
        Tone.Ok -> R.string.latency_good
        Tone.Warn -> R.string.latency_fair
        Tone.Err -> R.string.latency_slow
        Tone.Neutral -> R.string.latency_untested
    },
)

@Composable
private fun latencyText(ms: Int?): String = ms?.let { stringResource(R.string.latency_ms, it) } ?: "—"

/**
 * Latency (§2): bold tabular value coloured by the tone's text role, 8 px dot before it, gap 6. A screen reader reads
 * the value with its quality (fast, fair, slow, not tested) as the state.
 */
@Composable
fun DoonaLatency(ms: Int?, modifier: Modifier = Modifier) {
    val tone = latencyTone(ms)
    val quality = latencyQuality(tone)
    StatusLight(
        tone,
        latencyText(ms),
        modifier.semantics(mergeDescendants = true) { stateDescription = quality },
        DoonaTheme.type.latency,
        latencyColor(tone),
        DoonaTheme.size.dotSmall,
        DoonaTheme.spacing.dotGap,
    )
}

/**
 * Latency as the value of a metric tile: the tile's figure (`.rp-big`) with the tone's dot after it. The dot is part of
 * the component and inside its bounds, so the figure starts where the figure of a tile without a dot starts.
 */
@Composable
fun DoonaMetricLatency(ms: Int?, modifier: Modifier = Modifier) {
    val tone = latencyTone(ms)
    val quality = latencyQuality(tone)
    val style = DoonaTheme.type.tileValue
    Row(modifier.semantics(mergeDescendants = true) { stateDescription = quality }, verticalAlignment = Alignment.Top) {
        Text(latencyText(ms), style = style, color = latencyColor(tone), maxLines = 1, softWrap = false, modifier = Modifier.layoutRole(LayoutRole.Value))
        Spacer(Modifier.width(DoonaTheme.spacing.dotGap))
        LineDot(tone, style, DoonaTheme.size.dotSmall)
    }
}

/** Badge (§7): at least 20 high, growing with the font size, padding 0 × 8, radius 6, 1 px hl-high border, 12/500 subtle. */
@Composable
fun DoonaBadge(text: String, modifier: Modifier = Modifier) {
    val c = DoonaTheme.colors
    Box(
        modifier
            .heightIn(min = DoonaTheme.size.badgeHeight)
            .border(DoonaTheme.size.border, c.badgeBorder, DoonaTheme.shape.badge)
            .padding(horizontal = DoonaTheme.spacing.item),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = DoonaTheme.type.badge, color = c.text.secondary, maxLines = 1)
    }
}
