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
import dev.doona.android.feature.activity.RvActivityNotice
import dev.doona.android.prototype.rv.RvActivitySampleState
import dev.doona.android.prototype.rv.rvIdleActivity

/**
 * 活動 under a notice, disconnected in each: with no subscription there is no node or group; with every test failed the
 * node shows its failure; after the core stopped the status is [FailureReason.CoreStopped], which reads 未連線.
 */
internal fun rvNoticeActivity(notice: RvActivityNotice): RvActivitySampleState {
    val idle = rvIdleActivity()
    return when (notice) {
        RvActivityNotice.NoSubscription -> idle.copy(outboundGroupId = null, outbound = null, node = null)
        RvActivityNotice.NoNode -> idle.copy(node = idle.node?.copy(latencyMs = null, failed = true))
        RvActivityNotice.CoreStopped -> idle.copy(status = Status.Failed(FailureReason.CoreStopped))
        RvActivityNotice.StartFailed -> idle.copy(status = Status.Failed(FailureReason.StartFailed))
        RvActivityNotice.StartNoApps -> idle.copy(status = Status.Failed(FailureReason.NoAllowedApps))
        RvActivityNotice.StartIncompatible -> idle.copy(status = Status.Failed(FailureReason.IncompatibleCore))
        RvActivityNotice.StartNoNodes -> idle.copy(status = Status.Failed(FailureReason.StartFailed, ErrorCode.NoNodes), node = null)
        RvActivityNotice.Lockdown -> idle.copy(status = Status.Failed(FailureReason.LockdownEnabled))
        RvActivityNotice.VpnTaken, RvActivityNotice.Offline, RvActivityNotice.Crash, RvActivityNotice.ConsentDenied, RvActivityNotice.NotifyDenied -> idle
    }
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
