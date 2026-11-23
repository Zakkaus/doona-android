package dev.doona.android.prototype.rv.states

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.doona.android.R
import dev.doona.android.engine.FailureReason
import dev.doona.android.engine.Status
import dev.doona.android.feature.activity.ActivityUiState
import dev.doona.android.prototype.rv.RvBanner
import dev.doona.android.prototype.rv.RvEmptyState
import dev.doona.android.prototype.rv.rvIdleActivity

/**
 * What 活動 says when it cannot carry traffic: no subscription yet (the empty state, in place of the cards); every node
 * failed its latency test; another app took the VPN; no network; the core stopped after the tunnel came up. A failed
 * subscription update keeps its nodes, so it is told on 訂閱, not here.
 */
internal enum class RvActivityNotice(val route: String) {
    NoSubscription("empty"),
    NoNode("no-node"),
    VpnTaken("vpn-taken"),
    Offline("offline"),
    CoreStopped("core-stopped"),
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
        RvActivityNotice.VpnTaken, RvActivityNotice.Offline -> idle
    }
}

/**
 * The notice's card: the empty state with 新增訂閱, or a banner with its reason and one action ([onAction]): 重新測試,
 * 重新連線 or 網路設定. Each banner keeps the error glyph but VPN (its power glyph) and the network (Wi-Fi off).
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
    }
}
