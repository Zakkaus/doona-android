package dev.doona.android.feature.perapp

import androidx.annotation.StringRes
import dev.doona.android.R
import dev.doona.android.engine.AppPolicy
import dev.doona.android.engine.ErrorCode
import dev.doona.android.engine.FailureReason
import dev.doona.android.engine.InstalledApp
import dev.doona.android.engine.PerApp
import dev.doona.android.feature.messageRes
import dev.doona.android.feature.model.RvAppPolicy
import dev.doona.android.feature.model.RvPerApp

/**
 * The 依 App page from the engine's values: the [saved] policy, the edits over it ([draft], null before the first), the
 * phone's [installed] apps (null while they are read), the search [query], a save under way ([busy]), whether the last
 * write failed ([saveFailed]), the reason the tunnel did not come back after a stored save ([rebuildFailure]), and the
 * code of a first read of the apps that failed ([readError]). Until the stored policy is read ([policyLoaded] false)
 * the page shows the apps as loading, since the policy it holds is the default and not what the user saved. Pure, so
 * the ViewModel, the tests and the goldens build the same page.
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
    readError: ErrorCode?,
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
    rebuildReason = rebuildReason(rebuildFailure),
    rebuildNeedsList = rebuildFailure == FailureReason.NoAllowedApps,
    readFailed = readError != null,
    readReason = readError?.messageRes() ?: R.string.error_internal,
)

/**
 * The second line of the rebuild notice: the approved sentence for a tunnel that did not start, 活動's sentence for one
 * that found none of the listed apps installed (one cause, one sentence), else the reason's own text.
 */
@StringRes
private fun rebuildReason(reason: FailureReason?): Int = when (reason) {
    null, FailureReason.StartFailed -> R.string.vpn_failure_start_failed
    FailureReason.NoAllowedApps -> R.string.vpn_failure_no_allowed_apps
    else -> reason.messageRes()
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
