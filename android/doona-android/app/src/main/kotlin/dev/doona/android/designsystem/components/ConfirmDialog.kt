package dev.doona.android.designsystem.components

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.offset
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.doona.android.designsystem.theme.DoonaTheme

/** What a [DoonaConfirmDialog] asks for: [Default] a choice, [Destructive] the removal of something, in the destructive fill. */
enum class DoonaConfirmKind { Default, Destructive }

/**
 * A confirmation: a modal [title] over a [body], with a text-style dismiss button and a primary confirm button at the
 * end (§7, `.rp-dialog`: surface fill, radius 16, padding 24 on a phone). Back, a tap on the underlay around the card
 * and the dismiss button call [onDismiss]; the confirm button calls [onConfirm] and does not close the dialog, the
 * caller stops showing it. [DoonaConfirmKind.Destructive] fills the confirm button with the destructive colour, for
 * removing a subscription. The underlay is doona's: black at 32 % in light and 55 % in dark. The card keeps a gutter
 * from the window's edges, which belongs to the underlay, not to the dialog. The buttons sit side by side at the end
 * when both fit; when a long label or a large font leaves no room, they stack at the full width, the confirm button
 * last, and their labels wrap whole, so no label is cut off. In a window too short for the title, the body and the
 * buttons, the card scrolls.
 */
@Composable
fun DoonaConfirmDialog(
    title: String,
    body: String,
    confirmLabel: String,
    dismissLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    kind: DoonaConfirmKind = DoonaConfirmKind.Default,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, scrimAlpha = DoonaTheme.colors.scrim.alpha)) {
        DoonaConfirmPanel(title, body, confirmLabel, dismissLabel, onConfirm, onDismiss, kind, modifier.leaveToUnderlay(DoonaTheme.spacing.gutter), rememberScrollState())
    }
}

/**
 * Keeps [margin] of the window free on every side without making it part of the dialog: the content gets the room less
 * the margin and measures to its own size, so the margin is the underlay's, and a tap there is a tap outside the dialog.
 * Padding would put the margin inside the content, where a tap lands on the dialog and does not dismiss it.
 */
private fun Modifier.leaveToUnderlay(margin: Dp): Modifier = layout { measurable, constraints ->
    val both = margin.roundToPx() * 2
    val placeable = measurable.measure(constraints.offset(horizontal = -both, vertical = -both))
    layout(placeable.width, placeable.height) { placeable.place(0, 0) }
}

/**
 * The dialog's panel without its window, which is what the gallery shows and the tests read. A [scroll] state makes the
 * card scroll inside the height its parent gives it, with the padding scrolling too; without one it is as tall as its content.
 */
@Composable
internal fun DoonaConfirmPanel(
    title: String,
    body: String,
    confirmLabel: String,
    dismissLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    kind: DoonaConfirmKind,
    modifier: Modifier = Modifier,
    scroll: ScrollState? = null,
) {
    val c = DoonaTheme.colors
    Column(
        modifier
            .widthIn(max = DoonaTheme.size.dialogMax)
            .fillMaxWidth()
            .clip(DoonaTheme.shape.card)
            .background(c.surface)
            .semantics { paneTitle = title }
            .then(if (scroll != null) Modifier.verticalScroll(scroll) else Modifier)
            .padding(DoonaTheme.spacing.dialogInset),
    ) {
        Text(title, style = DoonaTheme.type.cardTitle, color = c.text.primary, modifier = Modifier.semantics { heading() })
        DoonaGap(DoonaTheme.spacing.block)
        Text(body, style = DoonaTheme.type.body, color = c.text.primary)
        DoonaGap(DoonaTheme.spacing.section)
        DialogButtons {
            DoonaButton(dismissLabel, onDismiss, style = DoonaButtonStyle.Text, maxLines = Int.MAX_VALUE)
            DoonaButton(confirmLabel, onConfirm, style = if (kind == DoonaConfirmKind.Destructive) DoonaButtonStyle.Destructive else DoonaButtonStyle.Primary, maxLines = Int.MAX_VALUE)
        }
    }
}

/**
 * The dialog's two buttons: side by side at the end, an item apart, when both fit at their natural width; otherwise
 * stacked at the full width, a block apart, in the order given.
 */
@Composable
private fun DialogButtons(content: @Composable () -> Unit) {
    val side = DoonaTheme.spacing.item
    val stack = DoonaTheme.spacing.block
    Layout(content, Modifier.fillMaxWidth()) { buttons, constraints ->
        val sideGap = side.roundToPx()
        val natural = buttons.map { it.maxIntrinsicWidth(Constraints.Infinity) }
        val together = natural.sum() + sideGap * (buttons.size - 1) <= constraints.maxWidth
        if (together) {
            val placeables = buttons.mapIndexed { i, button -> button.measure(Constraints(minWidth = natural[i], maxWidth = natural[i])) }
            val height = placeables.maxOf { it.height }
            layout(constraints.maxWidth, height) {
                var x = constraints.maxWidth - placeables.sumOf { it.width } - sideGap * (placeables.size - 1)
                placeables.forEach {
                    it.placeRelative(x, (height - it.height) / 2)
                    x += it.width + sideGap
                }
            }
        } else {
            val placeables = buttons.map { it.measure(Constraints(minWidth = constraints.maxWidth, maxWidth = constraints.maxWidth)) }
            val stackGap = stack.roundToPx()
            layout(constraints.maxWidth, placeables.sumOf { it.height } + stackGap * (placeables.size - 1)) {
                var y = 0
                placeables.forEach {
                    it.placeRelative(0, y)
                    y += it.height + stackGap
                }
            }
        }
    }
}
