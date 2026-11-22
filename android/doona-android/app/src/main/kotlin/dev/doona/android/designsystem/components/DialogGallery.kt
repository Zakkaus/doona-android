package dev.doona.android.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.doona.android.R
import dev.doona.android.designsystem.theme.DoonaTheme

/** The confirmation dialog's panel on its underlay, as the dialog's window shows it: a choice, a removal, and long text. */
@Composable
internal fun DialogGallery() = GalleryPage("Dialog") {
    val dismiss = stringResource(R.string.ui_dismiss)
    Section("DoonaConfirmDialog: default, destructive, long text") {
        Underlay {
            Panel("Connect now?", "The tunnel starts with the apps you chose.", "Connect", dismiss, DoonaConfirmKind.Default)
            Panel("移除訂閱", "移除後，這份訂閱的節點會一併刪除。", "移除", dismiss, DoonaConfirmKind.Destructive)
            Panel(
                "Remove the subscription and every group that uses only its nodes?",
                "新增訂閱後節點會出現在這裡；移除後，這份訂閱的全部節點、依賴這些節點的群組與測得的延遲都會一併刪除，且無法復原。",
                "Remove the subscription",
                "Keep it",
                DoonaConfirmKind.Destructive,
            )
        }
    }
}

/** The window under a dialog: the page's colour with the scrim over it. */
@Composable
private fun Underlay(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxWidth().background(DoonaTheme.colors.window)) {
        Column(Modifier.fillMaxWidth().background(DoonaTheme.colors.scrim).padding(DoonaTheme.spacing.gutter), verticalArrangement = Arrangement.spacedBy(DoonaTheme.spacing.section)) {
            content()
        }
    }
}

@Composable
private fun Panel(title: String, body: String, confirm: String, dismiss: String, kind: DoonaConfirmKind) {
    DoonaConfirmPanel(title, body, confirm, dismiss, onConfirm = {}, onDismiss = {}, kind = kind)
}
