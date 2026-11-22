package dev.doona.android.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import dev.doona.android.designsystem.theme.DoonaTheme

/** What a [DoonaConfirmDialog] asks for: [Default] a choice, [Destructive] the removal of something, in the destructive fill. */
enum class DoonaConfirmKind { Default, Destructive }

/**
 * A confirmation: a modal [title] over a [body], with a text-style dismiss button and a primary confirm button at the
 * end (§7, `.rp-dialog`: surface fill, radius 16, padding 24 on a phone). Back, a tap outside and the dismiss button
 * call [onDismiss]; the confirm button calls [onConfirm] and does not close the dialog, the caller stops showing it.
 * [DoonaConfirmKind.Destructive] fills the confirm button with the destructive colour, for removing a subscription.
 * The underlay is doona's: black at 32 % in light and 55 % in dark. The buttons sit side by side at the end when both
 * fit; when a long label or a large font leaves no room, they stack at the full width, the confirm button last, and
 * their labels wrap, so no label is cut off.
 */
@Composable
fun DoonaConfirmDialog(
    title: String,
    body: String,
    confirmLabel: String,
    dismissLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    kind: DoonaConfirmKind = DoonaConfirmKind.Default,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val underlay = DoonaTheme.colors.scrim.alpha
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect { window?.setDimAmount(underlay) }
        DoonaConfirmPanel(title, body, confirmLabel, dismissLabel, onConfirm, onDismiss, kind, Modifier.padding(horizontal = DoonaTheme.spacing.gutter))
    }
}

/** The dialog's panel without its window, which is what the gallery shows and the tests read. */
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
) {
    val c = DoonaTheme.colors
    Column(
        modifier
            .widthIn(max = DoonaTheme.size.dialogMax)
            .fillMaxWidth()
            .clip(DoonaTheme.shape.card)
            .background(c.surface)
            .padding(DoonaTheme.spacing.dialogInset)
            .semantics { paneTitle = title },
    ) {
        Text(title, style = DoonaTheme.type.cardTitle, color = c.text.primary, modifier = Modifier.semantics { heading() })
        DoonaGap(DoonaTheme.spacing.block)
        Text(body, style = DoonaTheme.type.body, color = c.text.primary)
        DoonaGap(DoonaTheme.spacing.section)
        DialogButtons {
            DoonaButton(dismissLabel, onDismiss, style = DoonaButtonStyle.Text)
            DoonaButton(confirmLabel, onConfirm, style = if (kind == DoonaConfirmKind.Destructive) DoonaButtonStyle.Destructive else DoonaButtonStyle.Primary)
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
