package dev.doona.android.feature

import androidx.annotation.StringRes
import dev.doona.android.R
import dev.doona.android.engine.ErrorCode
import dev.doona.android.engine.FailureReason

/**
 * The one action a notice about a tunnel that is down offers: what can mend its cause (owner, q-startfail, 2026-11-24
 * 22:57). Only a cause a second try may get past is tried again ([Retry]); no node leads to 新增訂閱 ([AddSubscription]),
 * none of the listed apps installed to the per-app list ([EditList]), a core this doona does not match to the update
 * check ([CheckUpdate]), the system's lockdown to its VPN settings ([VpnSettings]).
 */
internal enum class FailureFix(@StringRes val label: Int) {
    Retry(R.string.rv_retry),
    AddSubscription(R.string.rv_add_subscription),
    EditList(R.string.rv_edit_app_list),
    CheckUpdate(R.string.rv_settings_check_update),
    VpnSettings(R.string.rv_open_vpn_settings),
}

/** Why the tunnel is down in a notice's second line ([reason]), and the [fix] its action runs. */
internal data class FailureWords(@StringRes val reason: Int, val fix: FailureFix)

/**
 * The words for a tunnel that went down for [reason], with the core's [code] when it gave one: 活動's sentence for each
 * cause and the action that can mend it. 活動 keeps its own copy of this table on its branch until the two meet; this is
 * the one both read then. A start with no node to use is 活動's 「沒有節點」 with 新增訂閱, not a retry.
 */
internal fun failureWords(reason: FailureReason?, code: ErrorCode?): FailureWords = when (reason) {
    FailureReason.StartFailed if code == ErrorCode.NoNodes -> FailureWords(R.string.first_no_nodes, FailureFix.AddSubscription)
    null, FailureReason.StartFailed, FailureReason.VpnNotEstablished -> FailureWords(R.string.vpn_failure_start_failed, FailureFix.Retry)
    FailureReason.CoreStopped -> FailureWords(R.string.rv_core_stopped_reason, FailureFix.Retry)
    FailureReason.Revoked -> FailureWords(R.string.rv_vpn_taken_reason, FailureFix.Retry)
    FailureReason.NoAllowedApps -> FailureWords(R.string.vpn_failure_no_allowed_apps, FailureFix.EditList)
    FailureReason.IncompatibleCore -> FailureWords(R.string.vpn_failure_incompatible_core, FailureFix.CheckUpdate)
    FailureReason.LockdownEnabled -> FailureWords(R.string.vpn_failure_lockdown_enabled, FailureFix.VpnSettings)
}
