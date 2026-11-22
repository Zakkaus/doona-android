package dev.doona.android.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
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
 * The underlay is doona's: black at 32 % in light and 55 % in dark. The buttons wrap onto two lines when a large font
 * leaves no room for both.
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
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(DoonaTheme.spacing.item, Alignment.End),
            verticalArrangement = Arrangement.spacedBy(DoonaTheme.spacing.block),
        ) {
            DoonaButton(dismissLabel, onDismiss, style = DoonaButtonStyle.Text)
            if (kind == DoonaConfirmKind.Destructive) DoonaDestructiveButton(confirmLabel, onConfirm) else DoonaButton(confirmLabel, onConfirm)
        }
    }
}
