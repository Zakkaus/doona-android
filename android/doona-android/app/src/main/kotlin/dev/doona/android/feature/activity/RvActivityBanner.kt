package dev.doona.android.feature.activity

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.doona.android.R
import dev.doona.android.designsystem.rv.RvNotice
import dev.doona.android.designsystem.rv.RvNoticeKind
import dev.doona.android.feature.messageRes

/**
 * The notice's banner, with one action ([onAction]): 重新測試, 重新連線, 網路設定 or 匯出報告; none with no subscription,
 * where 活動 shows the empty state instead. A failed start is titled 連線失敗, the reason the notification's
 * (`vpn_failure_*`), and its action what can mend it: 重試, 調整清單, 檢查更新 or 新增訂閱. A refusal's action is the way
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
        RvActivityNotice.StartNoApps -> started(R.string.vpn_failure_no_allowed_apps, R.string.rv_edit_app_list)
        RvActivityNotice.StartIncompatible -> started(R.string.vpn_failure_incompatible_core, R.string.rv_settings_check_update)
        RvActivityNotice.StartNoNodes -> started(R.string.first_no_nodes, R.string.rv_add_subscription)
        RvActivityNotice.Lockdown -> started(R.string.vpn_failure_lockdown_enabled, R.string.rv_open_vpn_settings)
        RvActivityNotice.ConsentDenied -> Words(RvNoticeKind.Warning, R.string.rv_vpn_denied, R.string.rv_vpn_denied_reason, R.string.rv_reconnect)
        RvActivityNotice.NotifyDenied -> Words(RvNoticeKind.Info, R.string.rv_notify_denied, R.string.rv_notify_denied_reason, R.string.rv_lan_open_settings)
    }
    return RvNotice(kind, stringResource(title), stringResource(reason), stringResource(action), onAction)
}

/** A failed start: 連線失敗 over the [reason] and the one [action] that can mend it. */
private fun started(@StringRes reason: Int, @StringRes action: Int) = Words(RvNoticeKind.Error, R.string.vpn_status_failed, reason, action)

private data class Words(val kind: RvNoticeKind, @StringRes val title: Int, @StringRes val reason: Int, @StringRes val action: Int)

/**
 * The banner a failed action leaves on 活動 (plan 8): what failed, titled as the shared action-failed banner's words are
 * (pv-d M8), over the engine's own reason, and 重試 ([onRetry]).
 */
@Composable
internal fun rvActivityFailureBanner(failure: RvActivityFailure, onRetry: () -> Unit): RvNotice {
    val title = when (failure.attempt) {
        RvActivityAttempt.SwitchMode -> R.string.rv_failed_switch_mode
        RvActivityAttempt.TestLatency -> R.string.rv_failed_test_latency
    }
    return RvNotice(RvNoticeKind.Error, stringResource(title), stringResource(failure.code.messageRes()), stringResource(R.string.rv_retry), onRetry)
}
