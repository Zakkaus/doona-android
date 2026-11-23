package dev.doona.android.prototype.rv.states

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.doona.android.R
import dev.doona.android.engine.ErrorCode
import dev.doona.android.engine.FailureReason
import dev.doona.android.engine.Status
import dev.doona.android.feature.activity.ActivityUiState
import dev.doona.android.prototype.rv.RvBanner
import dev.doona.android.prototype.rv.RvEmptyState
import dev.doona.android.prototype.rv.rvIdleActivity

/**
 * What 活動 says when it cannot carry traffic: no subscription yet (the empty state, in place of the cards); every node
 * failed its latency test; another app took the VPN; no network; the core stopped after the tunnel came up. And, once
 * after doona crashed, that a crash log waits to be exported. A start that failed says why: the tunnel did not start,
 * the per-app list resolved to no installed app, the bundled core speaks another schema, or the core found no node in
 * Global. A tunnel revoked by the system is [VpnTaken]. A refusal is told where the user acted: the system's VPN consent
 * ([ConsentDenied]) and the notification permission ([NotifyDenied]). A failed subscription update keeps its nodes, so it is told on
 * 訂閱, not here.
 */
internal enum class RvActivityNotice(val route: String) {
    NoSubscription("empty"),
    NoNode("no-node"),
    VpnTaken("vpn-taken"),
    Offline("offline"),
    CoreStopped("core-stopped"),
    Crash("crash"),
    StartFailed("failed-start"),
    StartNoApps("failed-no-apps"),
    StartIncompatible("failed-incompatible"),
    StartNoNodes("failed-no-nodes"),
    ConsentDenied("consent-denied"),
    NotifyDenied("notify-denied"),
}

/**
 * 活動 under a notice, disconnected in each: with no subscription there is no node or group; with every test failed the
 * node shows its failure; after the core stopped the status is [FailureReason.CoreStopped], which reads 未連線.
 */
internal fun rvNoticeActivity(notice: RvActivityNotice): ActivityUiState {
    val idle = rvIdleActivity()
    return when (notice) {
        RvActivityNotice.NoSubscription -> idle.copy(outboundGroupId = null, outbound = null, node = null)
        RvActivityNotice.NoNode -> idle.copy(node = idle.node?.copy(latencyMs = null, failed = true))
        RvActivityNotice.CoreStopped -> idle.copy(status = Status.Failed(FailureReason.CoreStopped))
        RvActivityNotice.StartFailed -> idle.copy(status = Status.Failed(FailureReason.StartFailed))
        RvActivityNotice.StartNoApps -> idle.copy(status = Status.Failed(FailureReason.NoAllowedApps))
        RvActivityNotice.StartIncompatible -> idle.copy(status = Status.Failed(FailureReason.IncompatibleCore))
        RvActivityNotice.StartNoNodes -> idle.copy(status = Status.Failed(FailureReason.StartFailed, ErrorCode.NoNodes), node = null)
        RvActivityNotice.VpnTaken, RvActivityNotice.Offline, RvActivityNotice.Crash, RvActivityNotice.ConsentDenied, RvActivityNotice.NotifyDenied -> idle
    }
}

/**
 * The notice's card: the empty state with 新增訂閱, or a banner with its reason and one action ([onAction]): 重新測試,
 * 重新連線, 網路設定 or 匯出報告. A failed start is titled 連線失敗, the reason the notification's (`vpn_failure_*`), and its
 * action what can mend it: 重試, 依 App, 檢查更新 or 新增訂閱. Each banner keeps the error glyph but VPN (its power glyph)
 * and the network (Wi-Fi off). A refusal's action is the way back: 重新連線 asks the system for the VPN consent again, 開啟設定
 * opens the app's notification settings; the notification glyph marks the second.
 */
@Composable
internal fun RvActivityNoticeCard(notice: RvActivityNotice, onAction: () -> Unit) {
    when (notice) {
        RvActivityNotice.NoSubscription -> RvEmptyState(
            stringResource(R.string.rv_empty_subscription),
            stringResource(R.string.rv_empty_subscription_line),
            stringResource(R.string.rv_add_subscription),
            onAction,
        )

        RvActivityNotice.NoNode -> RvBanner(stringResource(R.string.rv_no_node), stringResource(R.string.rv_no_node_reason), stringResource(R.string.rv_retest), onAction)

        RvActivityNotice.VpnTaken -> RvBanner(
            stringResource(R.string.rv_vpn_taken),
            stringResource(R.string.rv_vpn_taken_reason),
            stringResource(R.string.rv_reconnect),
            onAction,
            icon = R.drawable.rv_sym_power,
        )

        RvActivityNotice.Offline -> RvBanner(
            stringResource(R.string.rv_offline),
            stringResource(R.string.rv_offline_reason),
            stringResource(R.string.rv_network_settings),
            onAction,
            icon = R.drawable.rv_sym_wifi_off,
        )

        RvActivityNotice.CoreStopped -> RvBanner(
            stringResource(R.string.rv_core_stopped),
            stringResource(R.string.rv_core_stopped_reason),
            stringResource(R.string.rv_reconnect),
            onAction,
        )

        RvActivityNotice.Crash -> RvBanner(stringResource(R.string.rv_crash), stringResource(R.string.rv_crash_reason), stringResource(R.string.rv_crash_export), onAction)

        RvActivityNotice.StartFailed -> StartBanner(R.string.vpn_failure_start_failed, R.string.rv_retry, onAction)

        RvActivityNotice.StartNoApps -> StartBanner(R.string.vpn_failure_no_allowed_apps, R.string.perapp_title, onAction)

        RvActivityNotice.StartIncompatible -> StartBanner(R.string.vpn_failure_incompatible_core, R.string.rv_settings_check_update, onAction)

        RvActivityNotice.StartNoNodes -> StartBanner(R.string.first_no_nodes, R.string.rv_add_subscription, onAction)

        RvActivityNotice.ConsentDenied -> RvBanner(
            stringResource(R.string.rv_vpn_denied),
            stringResource(R.string.rv_vpn_denied_reason),
            stringResource(R.string.rv_reconnect),
            onAction,
            icon = R.drawable.rv_sym_power,
        )

        RvActivityNotice.NotifyDenied -> RvBanner(
            stringResource(R.string.rv_notify_denied),
            stringResource(R.string.rv_notify_denied_reason),
            stringResource(R.string.rv_lan_open_settings),
            onAction,
            icon = R.drawable.rv_settings_ic_notifications,
        )
    }
}

/** A failed start: 連線失敗 over the [reason] and the one [action] that can mend it. */
@Composable
private fun StartBanner(@StringRes reason: Int, @StringRes action: Int, onAction: () -> Unit) {
    RvBanner(stringResource(R.string.vpn_status_failed), stringResource(reason), stringResource(action), onAction)
}
