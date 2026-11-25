package dev.doona.android.feature.permissions

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.doona.android.R
import dev.doona.android.designsystem.rv.RvConfirmSheet
import dev.doona.android.feature.localnetwork.RvPromptText

/**
 * The two permissions doona asks for on the way to its first connection: [Vpn], the system's VPN consent, and
 * [Notify], the notification permission. The notification shows only while connected (the status, 中斷連線, and a drop
 * when the connection ends: `VpnNotifications`), so it is asked once a connection is up.
 */
enum class Permission { Vpn, Notify }

/** The explanation's words: what the permission is for, 繼續, and 取消 (VPN: no connection) or 暫時不要. */
@Composable
internal fun Permission.text(): RvPromptText = when (this) {
    Permission.Vpn -> RvPromptText(
        stringResource(R.string.rv_perm_vpn_title),
        stringResource(R.string.rv_perm_vpn_body),
        stringResource(R.string.rv_lan_ask_continue),
        stringResource(R.string.rv_cancel),
    )

    Permission.Notify -> RvPromptText(
        stringResource(R.string.rv_perm_notify_title),
        stringResource(R.string.rv_perm_notify_body),
        stringResource(R.string.rv_lan_ask_continue),
        stringResource(R.string.rv_lan_ask_not_now),
    )
}

/** The explanation in RV's confirm sheet while [showing] is set: 繼續 runs [onConfirm]; the other action, back or a tap above it [onDismiss]. */
@Composable
internal fun PermissionSheet(showing: Permission?, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    // The sheet keeps its last words while it falls; a plain holder, since only a new explanation changes them.
    val last = remember { arrayOf(Permission.Vpn) }
    if (showing != null) last[0] = showing
    val text = last[0].text()
    RvConfirmSheet(showing != null, text.title, text.body, text.confirm, text.dismiss, onConfirm, onDismiss)
}

/** [PermissionSheet] over the app for [asks]; 繼續 hands [PermissionAsks.confirm]'s step to [onStep]. */
@Composable
fun PermissionPrompts(asks: PermissionAsks, onStep: (PermissionStep) -> Unit) {
    val showing by asks.showing.collectAsStateWithLifecycle()
    PermissionSheet(showing, onConfirm = { onStep(asks.confirm()) }, onDismiss = asks::dismiss)
}
