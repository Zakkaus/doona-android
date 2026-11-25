package dev.doona.android.prototype.rv.subs

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.doona.android.R
import dev.doona.android.designsystem.rv.RvConfirmSheet
import dev.doona.android.designsystem.rv.RvPromptText

/**
 * The local network prompts of main's flow (`feature/localnetwork`), in the sample's sheet: [Ask] says why before
 * Android's own dialog, the first time a subscription or node on the local network is reached; [Denied], after a no,
 * leads to the app's settings and never back to Android's dialog. The words are main's.
 */
internal enum class RvLanPrompt { Ask, Denied }

@Composable
internal fun RvLanPrompt.text(): RvPromptText = when (this) {
    RvLanPrompt.Ask -> RvPromptText(
        stringResource(R.string.rv_lan_ask_title),
        stringResource(R.string.rv_lan_ask_body),
        stringResource(R.string.rv_lan_ask_continue),
        stringResource(R.string.rv_lan_ask_not_now),
    )

    RvLanPrompt.Denied -> RvPromptText(
        stringResource(R.string.rv_lan_denied_title),
        stringResource(R.string.local_network_denied_body),
        stringResource(R.string.rv_lan_open_settings),
        stringResource(R.string.ui_dismiss),
    )
}

/** The prompt in the confirm sheet while [prompt] is set: 繼續 or 開啟設定 runs [onConfirm], the other [onDismiss]. */
@Composable
internal fun RvLanSheet(prompt: RvLanPrompt?, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    // The sheet keeps its last words while it falls.
    val text = (prompt ?: RvLanPrompt.Ask).text()
    RvConfirmSheet(prompt != null, text.title, text.body, text.confirm, text.dismiss, onConfirm, onDismiss)
}
