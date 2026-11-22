package dev.doona.android.feature.localnetwork

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.doona.android.R
import dev.doona.android.designsystem.components.DoonaConfirmDialog

/** What the app gives the prompts: the state holder, and the two steps only an activity can take. */
class LocalNetworkHost(val access: LocalNetworkAccess, val request: () -> Unit, val openSettings: () -> Unit)

/**
 * Shows the prompt [LocalNetworkHost.access] holds, over whatever page is open. The reason's confirm button goes on to
 * the system's question, and back or a tap outside it closes it without a refusal; the refusal's confirm button opens
 * the app's settings page, where the user can still allow access.
 */
@Composable
fun LocalNetworkPrompts(host: LocalNetworkHost) {
    val prompt by host.access.prompt.collectAsStateWithLifecycle()
    when (prompt) {
        null -> Unit

        LocalNetworkPrompt.Ask -> PromptDialog(
            LocalNetworkPrompt.Ask.text(),
            onConfirm = {
                host.access.requesting()
                host.request()
            },
            onDismiss = host.access::declined,
            onDismissRequest = host.access::closed,
        )

        LocalNetworkPrompt.Denied -> PromptDialog(
            LocalNetworkPrompt.Denied.text(),
            onConfirm = {
                host.access.dismissed()
                host.openSettings()
            },
            onDismiss = host.access::dismissed,
        )
    }
}

@Composable
private fun PromptDialog(text: PromptText, onConfirm: () -> Unit, onDismiss: () -> Unit, onDismissRequest: () -> Unit = onDismiss) =
    DoonaConfirmDialog(text.title, text.body, text.confirm, text.dismiss, onConfirm, onDismiss, onDismissRequest = onDismissRequest)

/** A prompt's words, shared by the dialog and its golden. */
@Immutable
internal data class PromptText(val title: String, val body: String, val confirm: String, val dismiss: String)

@Composable
internal fun LocalNetworkPrompt.text(): PromptText = when (this) {
    LocalNetworkPrompt.Ask -> PromptText(
        stringResource(R.string.local_network_ask_title),
        stringResource(R.string.local_network_ask_body),
        stringResource(R.string.local_network_ask_continue),
        stringResource(R.string.local_network_ask_not_now),
    )

    LocalNetworkPrompt.Denied -> PromptText(
        stringResource(R.string.local_network_denied_title),
        stringResource(R.string.local_network_denied_body),
        stringResource(R.string.local_network_open_settings),
        stringResource(R.string.ui_dismiss),
    )
}
