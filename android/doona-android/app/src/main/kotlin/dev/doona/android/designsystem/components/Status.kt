package dev.doona.android.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import dev.doona.android.R
import dev.doona.android.designsystem.rv.Tone
import dev.doona.android.designsystem.rv.latencyTone
import dev.doona.android.designsystem.theme.DoonaColors
import dev.doona.android.designsystem.theme.DoonaTheme
import dev.doona.android.designsystem.theme.DoonaToneInk
import dev.doona.android.designsystem.theme.scripted

fun toneColor(c: DoonaColors, tone: Tone): Color = when (tone) {
    Tone.Ok -> c.good
    Tone.Warn -> c.fair
    Tone.Err -> c.slow
    Tone.Neutral -> c.textSecondary
}

/** The dot of a status, centred on the first line of text of [style] and sized with that line (see [dotSizeFor]). */
@Composable
internal fun LineDot(tone: Tone, style: TextStyle, dot: Dp) {
    Box(Modifier.height(lineHeightOf(style)), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .layoutRole(LayoutRole.Dot)
                .size(dotSizeFor(style, dot))
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
        Text(scripted(text), style = style, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
    }
}

/**
 * Status light (§7): a dot and a label, the dot centred on the label's first line. 10 px dot, gap 8; the dot grows with
 * the font as the label does. A label too long for its room ends in an ellipsis.
 */
@Composable
fun DoonaStatusLight(
    tone: Tone,
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = DoonaTheme.type.body,
    color: Color = DoonaTheme.colors.text,
) = StatusLight(tone, text, modifier, style, color, DoonaTheme.size.statusDot, DoonaTheme.spacing.item)

/** The small status light (§7): the same with an 8 px dot, for counts that sit among text. */
@Composable
fun DoonaSmallStatusLight(
    tone: Tone,
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = DoonaTheme.type.body,
    color: Color = DoonaTheme.colors.text,
) = StatusLight(tone, text, modifier, style, color, DoonaTheme.size.dot, DoonaTheme.spacing.item)

/** The latency text role of a tone: the quality's hue, which reads at 4.5:1 on the page and the card. */
@Composable
private fun latencyColor(tone: Tone): Color = toneColor(DoonaTheme.colors, tone)

/** A tone's ink on the old node tile's fills ([DoonaColors.tileInk]). */
private fun tileColor(ink: DoonaToneInk, tone: Tone): Color = when (tone) {
    Tone.Ok -> ink.good
    Tone.Warn -> ink.fair
    Tone.Err -> ink.slow
    Tone.Neutral -> ink.secondary
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
fun DoonaLatency(ms: Int?, modifier: Modifier = Modifier) = Latency(ms, modifier, latencyColor(latencyTone(ms)))

/** [DoonaLatency] on the old node tile, in its [DoonaColors.tileInk]. */
@Composable
internal fun TileLatency(ms: Int?, modifier: Modifier = Modifier) = Latency(ms, modifier, tileColor(DoonaTheme.colors.tileInk, latencyTone(ms)))

@Composable
private fun Latency(ms: Int?, modifier: Modifier, color: Color) {
    val tone = latencyTone(ms)
    val quality = latencyQuality(tone)
    StatusLight(
        tone,
        latencyText(ms),
        modifier.semantics(mergeDescendants = true) { stateDescription = quality },
        DoonaTheme.type.latency,
        color,
        DoonaTheme.size.dot,
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
    val style = DoonaTheme.type.metricValue
    Row(modifier.semantics(mergeDescendants = true) { stateDescription = quality }, verticalAlignment = Alignment.Top) {
        Text(scripted(latencyText(ms)), style = style, color = latencyColor(tone), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false).layoutRole(LayoutRole.Value))
        Spacer(Modifier.width(DoonaTheme.spacing.dotGap))
        LineDot(tone, style, DoonaTheme.size.dot)
    }
}
