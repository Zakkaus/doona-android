package dev.doona.android.designsystem.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import dev.doona.android.designsystem.theme.DoonaTheme

/**
 * A used/total bar for traffic (§7, the kv meter): the [valueText] over a 4 dp track in the track colour, [fraction] of
 * it filled with the accent. The text is on its own lines above the bar and wraps when it is long, so it never touches
 * the bar: a `tight` gap lies between the last line and the track at any font size. [fraction] outside 0 to 1 is held
 * to it. A screen reader hears one progress: its range and, merged into it, the value text once.
 */
@Composable
fun DoonaMeter(fraction: Float, valueText: String, modifier: Modifier = Modifier) {
    val c = DoonaTheme.colors
    val shown = fraction.coerceIn(0f, 1f)
    Column(
        modifier.semantics(mergeDescendants = true) { progressBarRangeInfo = ProgressBarRangeInfo(shown, 0f..1f) },
        verticalArrangement = Arrangement.spacedBy(DoonaTheme.spacing.tight),
    ) {
        Text(valueText, style = DoonaTheme.type.bodyTabular, color = c.text.primary)
        Box(
            Modifier
                .layoutRole(LayoutRole.Bar)
                .fillMaxWidth()
                .height(DoonaTheme.size.meterTrack)
                .clip(DoonaTheme.shape.pill)
                .background(c.track),
        ) {
            Box(Modifier.fillMaxWidth(shown).fillMaxHeight().background(c.accent))
        }
    }
}

/**
 * What a list or a page shows when it has nothing: an [icon] of 48 dp in the neutral icon colour, a [title], one [line]
 * under it that says what to do or why it is empty, and an optional action: a primary button labelled [actionLabel]
 * that calls [onAction], shown only when both are given. All of it is centred, with the inset of a card at the sides,
 * and the text wraps when it is long. It takes the width its parent gives it and the height of its content; the screen
 * puts it where the list would be.
 */
@Composable
fun DoonaEmptyState(
    @DrawableRes icon: Int,
    title: String,
    line: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val c = DoonaTheme.colors
    Column(modifier.fillMaxWidth().padding(horizontal = DoonaTheme.spacing.cardInset), horizontalAlignment = Alignment.CenterHorizontally) {
        DoonaIcon(icon, c.icon, size = DoonaTheme.size.emptyIcon)
        DoonaGap(DoonaTheme.spacing.block)
        // The title and the line are one thing to a screen reader; the action is its own.
        // The texts take the full width and centre inside it: a centred paragraph is laid out as wide as the room it is given.
        Column(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(DoonaTheme.spacing.tight)) {
            Text(title, style = DoonaTheme.type.cardTitle, color = c.text.primary, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().semantics { heading() })
            Text(line, style = DoonaTheme.type.body, color = c.text.secondary, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        }
        if (actionLabel != null && onAction != null) {
            DoonaGap(DoonaTheme.spacing.section)
            DoonaButton(actionLabel, onAction)
        }
    }
}
