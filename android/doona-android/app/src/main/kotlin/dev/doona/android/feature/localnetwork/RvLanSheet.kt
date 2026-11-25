package dev.doona.android.feature.localnetwork

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import dev.doona.android.R
import dev.doona.android.designsystem.rv.RvConfirmSheet

/** A prompt's words: title, body, the confirm button and the secondary pill. Shared by the sheet and its goldens. */
@Immutable
internal data class RvPromptText(val title: String, val body: String, val confirm: String, val dismiss: String)

/**
 * [LocalNetworkPrompt.Ask] says why before Android's own dialog, the first time a subscription or node on the local
 * network is reached; [LocalNetworkPrompt.Denied], after a no, leads to the app's settings and never back to Android's
 * dialog.
 */
@Composable
internal fun LocalNetworkPrompt.text(): RvPromptText = when (this) {
    LocalNetworkPrompt.Ask -> RvPromptText(
        stringResource(R.string.rv_lan_ask_title),
        stringResource(R.string.rv_lan_ask_body),
        stringResource(R.string.rv_lan_ask_continue),
        stringResource(R.string.rv_lan_ask_not_now),
    )

    LocalNetworkPrompt.Denied -> RvPromptText(
        stringResource(R.string.rv_lan_denied_title),
        stringResource(R.string.rv_lan_denied_body),
        stringResource(R.string.rv_lan_open_settings),
        stringResource(R.string.ui_dismiss),
    )
}

/**
 * The prompt in the confirm sheet while [prompt] is set: 繼續 or 開啟設定 runs [onConfirm], the secondary pill
 * [onDismiss], and back or a tap above the sheet [onDismissRequest].
 */
@Composable
internal fun RvLanSheet(prompt: LocalNetworkPrompt?, onConfirm: () -> Unit, onDismiss: () -> Unit, onDismissRequest: () -> Unit = onDismiss) {
    // The sheet keeps its last words while it falls; a plain holder, since only a new prompt changes them.
    val last = remember { arrayOf(LocalNetworkPrompt.Ask) }
    if (prompt != null) last[0] = prompt
    val text = last[0].text()
    RvConfirmSheet(prompt != null, text.title, text.body, text.confirm, text.dismiss, onConfirm, onDismiss, onDismissRequest = onDismissRequest)
}
