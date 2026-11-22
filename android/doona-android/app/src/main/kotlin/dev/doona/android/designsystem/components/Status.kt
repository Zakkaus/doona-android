package dev.doona.android.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
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

/**
 * Status light (§7): a dot and a label, the dot centred on the label's first line. 10 px dot, 8 px for small and
 * for latency; gap 8, 6 for latency (§4).
 */
@Composable
fun DoonaStatusLight(
    tone: Tone,
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = DoonaTheme.type.body,
    color: Color = DoonaTheme.colors.text.primary,
    small: Boolean = false,
    gap: Dp = DoonaTheme.spacing.item,
) {
    Row(modifier, verticalAlignment = Alignment.Top) {
        Box(Modifier.height(lineHeightOf(style)), contentAlignment = Alignment.Center) {
            Box(
                Modifier
                    .size(if (small) DoonaTheme.size.dotSmall else DoonaTheme.size.dot)
                    .clip(DoonaTheme.shape.pill)
                    .background(toneColor(DoonaTheme.colors, tone)),
            )
        }
        Spacer(Modifier.width(gap))
        Text(text, style = style, color = color, maxLines = 1, softWrap = false)
    }
}

/**
 * Latency (§2): bold tabular value coloured by the tone's text role, 8 px dot, gap 6. [alignValue] marks the value of
 * a metric tile: the dot hangs in the tile's padding, so the number starts on the tile's value column, where the
 * tiles' values that have no dot start. The caller must have 12 dp of padding to its left.
 */
@Composable
fun DoonaLatency(ms: Int?, style: TextStyle = DoonaTheme.type.bodyStrong, modifier: Modifier = Modifier, alignValue: Boolean = false) {
    val text = DoonaTheme.colors.text
    val tone = latencyTone(ms)
    val color = when (tone) {
        Tone.Ok -> text.positive
        Tone.Warn -> text.notice
        Tone.Err -> text.negative
        Tone.Neutral -> text.secondary
    }
    // A hanging dot sits centred in the 16 dp padding to its left: 4 to the edge, 8 wide, 4 to the number.
    val gap = if (alignValue) DoonaTheme.spacing.tight else DoonaTheme.spacing.dotGap
    val lead = with(LocalDensity.current) { (DoonaTheme.size.dotSmall + gap).roundToPx() }
    DoonaStatusLight(
        modifier = if (alignValue) modifier.layoutRole(LayoutRole.Value).hangLeading(lead) else modifier,
        tone = tone,
        text = ms?.let { stringResource(R.string.latency_ms, it) } ?: "—",
        style = style.copy(fontFeatureSettings = DoonaTheme.type.tabular),
        color = color,
        small = true,
        gap = gap,
    )
}

/** Badge (§7): 20 high, padding 0 × 8, radius 6, 1 px hl-high border, 12/500 subtle. */
@Composable
fun DoonaBadge(text: String) {
    val c = DoonaTheme.colors
    Box(
        Modifier
            .height(DoonaTheme.size.badgeHeight)
            .border(DoonaTheme.size.border, c.badgeBorder, DoonaTheme.shape.badge)
            .padding(horizontal = DoonaTheme.spacing.item),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = DoonaTheme.type.badge, color = c.text.secondary, maxLines = 1)
    }
}

/** Reports the node [lead] px narrower and places it that far left, so its first [lead] px hang outside its bounds. */
private fun Modifier.hangLeading(lead: Int) = layout { measurable, constraints ->
    val room = if (constraints.hasBoundedWidth) constraints.maxWidth + lead else constraints.maxWidth
    val placeable = measurable.measure(constraints.copy(minWidth = 0, maxWidth = room))
    layout(placeable.width - lead, placeable.height) { placeable.place(-lead, 0) }
}
