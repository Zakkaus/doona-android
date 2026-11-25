package dev.doona.android.feature.localnetwork

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** What the app gives the prompts: the state holder, and the two steps only an activity can take. */
class LocalNetworkHost(val access: LocalNetworkAccess, val request: () -> Unit, val openSettings: () -> Unit) {
    /** The reason's confirm button: the system's question, only while the reason still waits for an answer. */
    fun proceed() {
        if (access.requesting()) request()
    }
}

/**
 * Shows the prompt [LocalNetworkHost.access] holds, over whatever page is open. The reason's confirm button goes on to
 * the system's question unless the reason was withdrawn meanwhile, and back or a tap outside it closes it without a
 * refusal; the refusal's confirm button opens the app's settings page, where the user can still allow access, and an
 * action that waits on the refusal waits for the user to come back. It is RV's confirm sheet ([RvLanSheet]). Not
 * [visible], the prompt waits off screen while another sheet is up.
 */
@Composable
fun LocalNetworkPrompts(host: LocalNetworkHost, visible: Boolean = true) {
    val prompt by host.access.prompt.collectAsStateWithLifecycle()
    RvLanSheet(
        prompt.takeIf { visible },
        onConfirm = {
            when (prompt) {
                LocalNetworkPrompt.Ask -> host.proceed()

                LocalNetworkPrompt.Denied -> {
                    host.access.toSettings()
                    host.openSettings()
                }

                null -> Unit
            }
        },
        onDismiss = {
            when (prompt) {
                LocalNetworkPrompt.Ask -> host.access.declined()
                LocalNetworkPrompt.Denied -> host.access.dismissed()
                null -> Unit
            }
        },
        onDismissRequest = {
            when (prompt) {
                LocalNetworkPrompt.Ask -> host.access.closed()
                LocalNetworkPrompt.Denied -> host.access.dismissed()
                null -> Unit
            }
        },
    )
}
