package dev.doona.android.prototype.rv.states

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.doona.android.R
import dev.doona.android.engine.FailureReason
import dev.doona.android.engine.Status
import dev.doona.android.feature.activity.ActivityUiState
import dev.doona.android.prototype.rv.RvNotice
import dev.doona.android.prototype.rv.RvNoticeKind
import dev.doona.android.prototype.rv.rvIdleActivity

/**
 * What 活動 says when it cannot carry traffic: no subscription yet (the empty state, in place of the cards); every node
 * failed its latency test; another app took the VPN; no network; the core stopped after the tunnel came up. And, once
 * after doona crashed, that a crash log waits to be exported. A failed subscription update keeps its nodes, so it is
 * told on 訂閱, not here.
 */
internal enum class RvActivityNotice(val route: String) {
    NoSubscription("empty"),
    NoNode("no-node"),
    VpnTaken("vpn-taken"),
    Offline("offline"),
    CoreStopped("core-stopped"),
    Crash("crash"),
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
        RvActivityNotice.VpnTaken, RvActivityNotice.Offline, RvActivityNotice.Crash -> idle
    }
}

/**
 * The notice's banner, with one action ([onAction]): 重新測試, 重新連線, 網路設定 or 匯出報告; none with no subscription,
 * where 活動 shows the empty state instead. Its kind picks its glyph: the core stopping and every node failing are errors;
 * another app's VPN and no network, outside doona, warnings; the crash's log, past and nothing broken now, info.
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
    }
    return RvNotice(kind, stringResource(title), stringResource(reason), stringResource(action), onAction)
}

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
