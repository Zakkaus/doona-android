package dev.doona.android.feature.perapp

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import dev.doona.android.feature.FailureFix
import dev.doona.android.feature.model.RvAppPolicy
import dev.doona.android.feature.model.RvPerApp

/** An app the phone can launch: its name and its package. */
@Immutable
internal data class RvApp(val label: String, val packageName: String)

/** Where a save stands: none running, writing the setting and rebuilding the tunnel, or the write failed. */
internal enum class RvSave { Idle, Saving, Failed }

/**
 * The 依 App page: the [saved] setting, the edits over it ([draft], null before the first), the phone's [installed]
 * apps (null while they are read), the search [query], the save, whether the last save was stored but the tunnel
 * could not be rebuilt with it ([rebuildFailed]: [rebuildReason] is its notice's second line, [rebuildFix] what can
 * mend the cause), and whether the phone could not list its apps ([readFailed], an empty [installed] then being no
 * claim that it has none).
 */
@Immutable
internal data class RvPerAppState(
    val saved: RvPerApp,
    val draft: RvPerApp?,
    val installed: List<RvApp>?,
    val query: String,
    val save: RvSave,
    val rebuildFailed: Boolean,
    @StringRes val rebuildReason: Int,
    val rebuildFix: FailureFix,
    val readFailed: Boolean,
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

    /**
     * [needsApp] said under the policies: only when the phone lists apps to check. With none listed (none to launch, or
     * a read that failed) the line would ask for the impossible; the list's caption or the notice says why instead, and
     * 儲存 stays off all the same.
     */
    val asksForApp: Boolean get() = needsApp && !installed.isNullOrEmpty()

    /** The phone would not list its apps and none are known: nothing to search or check, which the notice says. */
    val unread: Boolean get() = readFailed && installed.isNullOrEmpty()

    /** The edits differ from what is saved, they can be saved, and no save is running. */
    val canSave: Boolean get() = installed != null && edited != saved && !needsApp && save != RvSave.Saving
}
