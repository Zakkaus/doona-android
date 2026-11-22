package dev.doona.android.feature.perapp

import androidx.compose.runtime.Immutable
import dev.doona.android.engine.AppPolicy
import dev.doona.android.engine.ErrorCode
import dev.doona.android.engine.InstalledApp
import dev.doona.android.engine.PerApp

/** Everything the 依 App screen shows, in engine terms: the policy and the checks being edited, not yet saved. */
@Immutable
data class PerAppUiState(
    val policy: AppPolicy = AppPolicy.All,
    /** The installed apps that match [query], in the order the device lists them. */
    val apps: List<InstalledApp> = emptyList(),
    /** The checked packages, including any that are no longer installed: saving keeps them. */
    val checked: Set<String> = emptySet(),
    /** False until the device's apps are read; an empty list before that is not a device without apps. */
    val loaded: Boolean = false,
    /** Whether the device has any launchable app at all; an empty [apps] with this true means the search matched none. */
    val hasApps: Boolean = false,
    val query: String = "",
    /** The edits differ from what is saved, they can be saved, and no save is running. */
    val canSave: Boolean = false,
    /** 只有勾選的 with no installed app checked: it would route nothing, and the tunnel would refuse to start. */
    val needsApp: Boolean = false,
    val busy: Boolean = false,
    /** The last failure, shown until dismissed or until the next attempt succeeds. */
    val error: ErrorCode? = null,
)

/** What [PerAppViewModel] handles. */
sealed interface PerAppAction {
    data class SetPolicy(val policy: AppPolicy) : PerAppAction

    data class Toggle(val packageName: String, val checked: Boolean) : PerAppAction

    data class SetQuery(val query: String) : PerAppAction

    data object Save : PerAppAction

    data object DismissError : PerAppAction
}

/**
 * The state from the saved policy, the edits over it ([draft], null before the first edit), the device's [installed]
 * apps (null while they load) and the search [query]. Pure, so previews and tests build the same state.
 */
fun perAppUiState(
    saved: PerApp,
    draft: PerApp?,
    installed: List<InstalledApp>?,
    query: String,
    busy: Boolean,
    error: ErrorCode?,
): PerAppUiState {
    val edited = draft ?: saved
    val needle = query.trim()
    val apps = installed.orEmpty()
    val visible = if (needle.isEmpty()) apps else apps.filter { it.label.contains(needle, ignoreCase = true) || it.packageName.contains(needle, ignoreCase = true) }
    // Only an installed app counts: the tunnel resolves the stored packages against what is installed.
    val needsApp = edited.policy == AppPolicy.OnlyListed && apps.none { it.packageName in edited.packages }
    return PerAppUiState(
        policy = edited.policy,
        apps = visible,
        checked = edited.packages,
        loaded = installed != null,
        hasApps = apps.isNotEmpty(),
        query = query,
        canSave = installed != null && edited != saved && !needsApp && !busy,
        needsApp = needsApp && installed != null,
        busy = busy,
        error = error,
    )
}
