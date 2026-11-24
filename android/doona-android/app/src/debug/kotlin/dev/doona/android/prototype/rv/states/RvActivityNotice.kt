package dev.doona.android.prototype.rv.states

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.doona.android.R
import dev.doona.android.designsystem.rv.RvNotice
import dev.doona.android.designsystem.rv.RvNoticeKind
import dev.doona.android.engine.ErrorCode
import dev.doona.android.engine.FailureReason
import dev.doona.android.engine.Status
import dev.doona.android.feature.activity.ActivityUiState
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
 * The notice's banner, with one action ([onAction]): 重新測試, 重新連線, 網路設定 or 匯出報告; none with no subscription,
 * where 活動 shows the empty state instead. A failed start is titled 連線失敗, the reason the notification's
 * (`vpn_failure_*`), and its action what can mend it: 重試, 依 App, 檢查更新 or 新增訂閱. A refusal's action is the way
 * back: 重新連線 asks the system for the VPN consent again, 開啟設定 opens the app's notification settings. Its kind picks
 * its glyph: the core stopping, every node failing and a failed start are errors; another app's VPN, no network and a
 * refused VPN consent, outside doona, warnings; the crash's log and a refused notification permission, nothing broken
 * now, info.
 */
@Composable
internal fun rvActivityBanner(notice: RvActivityNotice, onAction: () -> Unit): RvNotice? {
    val (kind, title, reason, action) = when (notice) {
        RvActivityNotice.NoSubscription -> return null
        RvActivityNotice.NoNode -> Words(RvNoticeKind.Error, R.string.rv_no_node, R.string.rv_no_node_reason, R.string.rv_retest)
        RvActivityNotice.VpnTaken -> Words(RvNoticeKind.Warning, R.string.rv_vpn_taken, R.string.rv_vpn_taken_reason, R.string.rv_reconnect)
        RvActivityNotice.Offline -> Words(RvNoticeKind.Warning, R.string.rv_offline, R.string.rv_offline_reason, R.string.rv_network_settings)
        RvActivityNotice.CoreStopped -> Words(RvNoticeKind.Error, R.string.rv_core_stopped, R.string.rv_core_stopped_reason, R.string.rv_reconnect)
        RvActivityNotice.Crash -> Words(RvNoticeKind.Info, R.string.rv_crash, R.string.rv_crash_reason, R.string.rv_crash_export)
        RvActivityNotice.StartFailed -> started(R.string.vpn_failure_start_failed, R.string.rv_retry)
        RvActivityNotice.StartNoApps -> started(R.string.vpn_failure_no_allowed_apps, R.string.perapp_title)
        RvActivityNotice.StartIncompatible -> started(R.string.vpn_failure_incompatible_core, R.string.rv_settings_check_update)
        RvActivityNotice.StartNoNodes -> started(R.string.first_no_nodes, R.string.rv_add_subscription)
        RvActivityNotice.ConsentDenied -> Words(RvNoticeKind.Warning, R.string.rv_vpn_denied, R.string.rv_vpn_denied_reason, R.string.rv_reconnect)
        RvActivityNotice.NotifyDenied -> Words(RvNoticeKind.Info, R.string.rv_notify_denied, R.string.rv_notify_denied_reason, R.string.rv_lan_open_settings)
    }
    return RvNotice(kind, stringResource(title), stringResource(reason), stringResource(action), onAction)
}

/** A failed start: 連線失敗 over the [reason] and the one [action] that can mend it. */
private fun started(@StringRes reason: Int, @StringRes action: Int) = Words(RvNoticeKind.Error, R.string.vpn_status_failed, reason, action)

/** A failed action's title (pv-d M8): 策略's mode switch, a node switch in a picker. */
internal enum class RvActionFailed(@StringRes val title: Int) {
    SwitchMode(R.string.rv_failed_switch_mode),
    SwitchNode(R.string.rv_failed_switch_node),
}

/** The banner a failed [action] leaves where it happened: what failed over the engine's own [reason], and 重試 ([onRetry]). */
@Composable
internal fun rvActionFailedBanner(action: RvActionFailed, @StringRes reason: Int, onRetry: () -> Unit) =
    RvNotice(RvNoticeKind.Error, stringResource(action.title), stringResource(reason), stringResource(R.string.rv_retry), onRetry)

private data class Words(val kind: RvNoticeKind, @StringRes val title: Int, @StringRes val reason: Int, @StringRes val action: Int)
