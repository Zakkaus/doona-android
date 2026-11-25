package dev.doona.android.feature.perapp

import dev.doona.android.R
import dev.doona.android.engine.AppPolicy
import dev.doona.android.engine.FailureReason
import dev.doona.android.engine.InstalledApp
import dev.doona.android.engine.PerApp
import dev.doona.android.feature.model.RvAppPolicy
import dev.doona.android.feature.model.RvPerApp

/**
 * The 依 App page from the engine's values: the [saved] policy, the edits over it ([draft], null before the first), the
 * phone's [installed] apps (null while they are read), the search [query], a save under way ([busy]), whether the last
 * write failed ([saveFailed]), the reason the tunnel did not come back after a stored save ([rebuildFailure]), and
 * whether the phone would not list its apps ([readFailed]). Until the stored policy is read ([policyLoaded] false) the
 * page shows the apps as loading, since the policy it holds is the default and not what the user saved. Pure, so the
 * ViewModel, the tests and the goldens build the same page.
 */
internal fun rvPerAppState(
    saved: PerApp,
    draft: PerApp?,
    installed: List<InstalledApp>?,
    query: String,
    busy: Boolean,
    saveFailed: Boolean,
    policyLoaded: Boolean,
    rebuildFailure: FailureReason?,
    readFailed: Boolean,
) = RvPerAppState(
    saved = saved.toRv(),
    draft = draft?.toRv(),
    installed = if (policyLoaded) installed?.map { RvApp(it.label, it.packageName) } else null,
    query = query,
    save = when {
        busy -> RvSave.Saving
        saveFailed -> RvSave.Failed
        else -> RvSave.Idle
    },
    rebuildFailed = rebuildFailure != null,
    rebuildReason = rebuildNotice(rebuildFailure).first,
    rebuildFix = rebuildNotice(rebuildFailure).second,
    readFailed = readFailed,
)

/**
 * The rebuild notice's second line and action by what kept the tunnel down, as 活動 says a failed start (owner,
 * q-startfail, 2026-11-24): 活動's sentence for each cause, and the action that can mend it. Only a failure that a
 * second try may get past offers 重試; none of the listed apps installed, a core this doona does not match and the
 * system's lockdown would fail the same way again. 活動 keeps its own copy of this mapping on its branch; one shared
 * mapping replaces both once they meet.
 */
private fun rebuildNotice(reason: FailureReason?): Pair<Int, RvRebuildFix> = when (reason) {
    null, FailureReason.StartFailed, FailureReason.VpnNotEstablished -> R.string.vpn_failure_start_failed to RvRebuildFix.Retry
    FailureReason.CoreStopped -> R.string.rv_core_stopped_reason to RvRebuildFix.Retry
    FailureReason.Revoked -> R.string.rv_vpn_taken_reason to RvRebuildFix.Retry
    FailureReason.NoAllowedApps -> R.string.vpn_failure_no_allowed_apps to RvRebuildFix.EditList
    FailureReason.IncompatibleCore -> R.string.vpn_failure_incompatible_core to RvRebuildFix.CheckUpdate
    FailureReason.LockdownEnabled -> R.string.vpn_failure_lockdown_enabled to RvRebuildFix.VpnSettings
}

internal fun PerApp.toRv() = RvPerApp(policy.toRv(), packages)

internal fun RvPerApp.toEngine() = PerApp(policy.toEngine(), packages)

internal fun AppPolicy.toRv() = when (this) {
    AppPolicy.All -> RvAppPolicy.All
    AppPolicy.OnlyListed -> RvAppPolicy.Only
    AppPolicy.ExceptListed -> RvAppPolicy.Except
}

internal fun RvAppPolicy.toEngine() = when (this) {
    RvAppPolicy.All -> AppPolicy.All
    RvAppPolicy.Only -> AppPolicy.OnlyListed
    RvAppPolicy.Except -> AppPolicy.ExceptListed
}
