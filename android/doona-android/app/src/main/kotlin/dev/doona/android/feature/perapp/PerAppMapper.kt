package dev.doona.android.feature.perapp

import dev.doona.android.engine.AppPolicy
import dev.doona.android.engine.InstalledApp
import dev.doona.android.engine.PerApp
import dev.doona.android.engine.Status
import dev.doona.android.feature.failureWords
import dev.doona.android.feature.model.RvAppPolicy
import dev.doona.android.feature.model.RvPerApp

/**
 * The 依 App page from the engine's values: the [saved] policy, the edits over it ([draft], null before the first), the
 * phone's [installed] apps (null while they are read), the search [query], a save under way ([busy]), whether the last
 * write failed ([saveFailed]), why the tunnel did not come back after a stored save ([rebuildFailure], in the words
 * of [failureWords]), and whether the phone would not list its apps ([readFailed]). Until the stored policy is read ([policyLoaded] false) the
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
    rebuildFailure: Status.Failed?,
    readFailed: Boolean,
): RvPerAppState {
    val words = failureWords(rebuildFailure?.reason, rebuildFailure?.code)
    return RvPerAppState(
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
        rebuildReason = words.reason,
        rebuildFix = words.fix,
        readFailed = readFailed,
    )
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
