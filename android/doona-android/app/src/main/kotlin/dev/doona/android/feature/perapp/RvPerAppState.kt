package dev.doona.android.feature.perapp

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import dev.doona.android.R
import dev.doona.android.feature.model.RvAppPolicy
import dev.doona.android.feature.model.RvPerApp

/** An app the phone can launch: its name and its package. */
@Immutable
internal data class RvApp(val label: String, val packageName: String)

/** Where a save stands: none running, writing the setting and rebuilding the tunnel, or the write failed. */
internal enum class RvSave { Idle, Saving, Failed }

/**
 * The 依 App page: the [saved] setting, the edits over it ([draft], null before the first), the phone's [installed]
 * apps (null while they are read), the search [query], the save, and whether the last save was stored but the tunnel
 * could not be rebuilt with it ([rebuildFailed]). [saveReason] and [rebuildReason] are the second lines of the two
 * failure notices.
 */
@Immutable
internal data class RvPerAppState(
    val saved: RvPerApp,
    val draft: RvPerApp? = null,
    val installed: List<RvApp>?,
    val query: String = "",
    val save: RvSave = RvSave.Idle,
    val rebuildFailed: Boolean = false,
    @StringRes val saveReason: Int = R.string.rv_report_failed_reason,
    @StringRes val rebuildReason: Int = R.string.vpn_failure_start_failed,
) {
    /** What the page shows: the edits, or the setting as saved. */
    val edited: RvPerApp get() = draft ?: saved

    /** The installed apps whose name or package holds the [query], in the phone's order. */
    val shown: List<RvApp>
        get() {
            val needle = query.trim()
            val apps = installed.orEmpty()
            return if (needle.isEmpty()) apps else apps.filter { it.label.contains(needle, ignoreCase = true) || it.packageName.contains(needle, ignoreCase = true) }
        }

    /** 只有勾選的 with no installed app checked: it would route nothing, and the tunnel would refuse to start. */
    val needsApp: Boolean
        get() = installed != null && edited.policy == RvAppPolicy.Only && installed.none { it.packageName in edited.packages }

    /** The edits differ from what is saved, they can be saved, and no save is running. */
    val canSave: Boolean get() = installed != null && edited != saved && !needsApp && save != RvSave.Saving

    /** The page with [policy] chosen. */
    fun withPolicy(policy: RvAppPolicy) = editing { it.copy(policy = policy) }

    /** The page with [packageName] checked or not. */
    fun withApp(packageName: String, checked: Boolean) = editing { it.copy(packages = if (checked) it.packages + packageName else it.packages - packageName) }

    /** While a save runs the page rests: no edit is taken. */
    private fun editing(change: (RvPerApp) -> RvPerApp): RvPerAppState = if (save == RvSave.Saving) this else copy(draft = change(edited))
}
