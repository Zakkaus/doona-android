package dev.doona.android.prototype.rv.states

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import dev.doona.android.R
import dev.doona.android.designsystem.rv.RvConfirmSheet
import dev.doona.android.engine.Status
import dev.doona.android.prototype.rv.subs.RvPromptText

/**
 * The two permissions doona asks for on the way to its first connection, each said first in the confirm sheet: [Vpn]
 * before the system's VPN consent, [Notify] before the notification permission. The notification shows only while
 * connected (the status, 中斷連線, and a drop when the connection ends: `VpnNotifications`), so it is asked once the
 * first connection is up.
 */
internal enum class RvPermission { Vpn, Notify }

/** The explanation's words: what the permission is for, 繼續, and 取消 (VPN: no connection) or 暫時不要. */
@Composable
internal fun RvPermission.text(): RvPromptText = when (this) {
    RvPermission.Vpn -> RvPromptText(
        stringResource(R.string.rv_perm_vpn_title),
        stringResource(R.string.rv_perm_vpn_body),
        stringResource(R.string.rv_lan_ask_continue),
        stringResource(R.string.rv_cancel),
    )

    RvPermission.Notify -> RvPromptText(
        stringResource(R.string.rv_perm_notify_title),
        stringResource(R.string.rv_perm_notify_body),
        stringResource(R.string.rv_lan_ask_continue),
        stringResource(R.string.rv_lan_ask_not_now),
    )
}

/**
 * Which explanation shows ([showing]), and which were given. [connect] stands for 連線: until the VPN was [explained]
 * it shows [RvPermission.Vpn]; after that it goes straight to [request], the system's consent, which connects once
 * given (as main's `requestVpnConsent`). Each [status] of the tunnel is passed to [onStatus]: the first time a connection
 * this started comes up, [RvPermission.Notify] shows, once. 繼續 hands the permission to [request]; the other action
 * closes the sheet and asks nothing. A cancelled VPN explanation leaves 活動 as it was and shows again on the next
 * 連線, since the consent was never asked; a declined notification one does not come back, as the first connection
 * has passed. A refusal in the system's dialog is then told by 活動's notices as before.
 */
@Stable
internal class RvPermissionAsks(explained: Boolean, private val request: (RvPermission) -> Unit) {
    var showing by mutableStateOf<RvPermission?>(null)
        private set

    /** The explanation shown last, whose words the sheet keeps while it falls. */
    var last = RvPermission.Vpn
        private set
    private var vpnExplained = explained
    private var notifyExplained = explained
    private var connecting = false

    fun connect() {
        if (vpnExplained) consent() else show(RvPermission.Vpn)
    }

    fun onStatus(status: Status) {
        if (status is Status.Running && connecting && !notifyExplained) {
            notifyExplained = true
            show(RvPermission.Notify)
        }
        if (status !is Status.Starting) connecting = false
    }

    fun confirm() {
        val shown = showing ?: return
        showing = null
        when (shown) {
            RvPermission.Vpn -> {
                vpnExplained = true
                consent()
            }

            RvPermission.Notify -> request(RvPermission.Notify)
        }
    }

    fun dismiss() {
        showing = null
    }

    private fun show(permission: RvPermission) {
        last = permission
        showing = permission
    }

    private fun consent() {
        connecting = true
        request(RvPermission.Vpn)
    }
}

/** The explanation in the confirm sheet while [RvPermissionAsks.showing]: 繼續 or the other action. */
@Composable
internal fun RvPermissionSheet(asks: RvPermissionAsks) {
    val text = asks.last.text()
    RvConfirmSheet(asks.showing != null, text.title, text.body, text.confirm, text.dismiss, asks::confirm, asks::dismiss)
}
