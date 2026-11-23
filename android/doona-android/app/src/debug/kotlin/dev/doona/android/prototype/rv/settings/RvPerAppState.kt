package dev.doona.android.prototype.rv.settings

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import dev.doona.android.R

/** Which apps the tunnel takes (the engine's `AppPolicy`): all of them, only the checked ones, or all but those. */
internal enum class RvAppPolicy(@StringRes val label: Int) {
    All(R.string.perapp_policy_all),
    Only(R.string.perapp_policy_only),
    Except(R.string.perapp_policy_except),
}

/** An app the phone can launch: its name and its package. */
@Immutable
internal data class RvApp(val label: String, val packageName: String)

/** A per-app setting: the [policy] and the checked [packages], which it keeps even for an app no longer installed. */
@Immutable
internal data class RvPerApp(val policy: RvAppPolicy, val packages: Set<String>)

/** Where a save stands: none running, writing the setting and rebuilding the tunnel, or the write failed. */
internal enum class RvSave { Idle, Saving, Failed }

/**
 * The 依 App page as `PerAppUiState` has it: the [saved] setting, the edits over it ([draft], null before the first),
 * the phone's [installed] apps (null while they are read), the search [query], the save, and whether the last save was
 * stored but the tunnel could not be rebuilt with it ([rebuildFailed]).
 */
@Immutable
internal data class RvPerAppState(
    val saved: RvPerApp,
    val draft: RvPerApp? = null,
    val installed: List<RvApp>? = RvPerAppData.apps,
    val query: String = "",
    val save: RvSave = RvSave.Idle,
    val rebuildFailed: Boolean = false,
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

/**
 * The sample's apps: generic names and packages, not a real phone's list, so the rows show the app disc's fallback
 * glyph. [saved] is 設定's sample: only the twelve checked apps take the tunnel.
 */
internal object RvPerAppData {
    val apps = listOf(
        "Browser", "Calendar", "Camera", "Chat", "Clock", "Drive", "Files", "Mail", "Maps", "Music",
        "News", "Notes", "Photos", "Podcasts", "Reader", "Shop", "Translate", "Video", "Wallet", "Weather",
    ).map { RvApp(it, PACKAGE + it.lowercase()) }

    private val checked = listOf("Browser", "Chat", "Drive", "Mail", "Maps", "News", "Photos", "Podcasts", "Reader", "Translate", "Video", "Weather")

    val saved = RvPerApp(RvAppPolicy.Only, checked.mapTo(mutableSetOf()) { PACKAGE + it.lowercase() })

    /** The two apps the except sample keeps out of the tunnel. */
    val except = RvPerApp(RvAppPolicy.Except, setOf(PACKAGE + "camera", PACKAGE + "video"))

    /** The layouts' samples (`perapp-v1`, `-v2`): the first twelve apps, three of them checked under 只有勾選的. */
    val twelve = apps.take(12)
    val three = RvPerApp(RvAppPolicy.Only, listOf("browser", "chat", "mail").mapTo(mutableSetOf()) { PACKAGE + it })

    /** A query two apps match (Mail and Maps), and one none does. */
    const val QUERY = "ma"
    const val QUERY_NONE = "Bank"

    private const val PACKAGE = "com.example."
}

/**
 * The page in each state its goldens record, by name; the sample app opens `perapp:<name>` in it. Unknown names give
 * the page as 設定 has it.
 */
internal fun rvPerAppSample(name: String, saved: RvPerApp = RvPerAppData.saved): RvPerAppState {
    val all = RvPerApp(RvAppPolicy.All, saved.packages)
    return when (name) {
        "all" -> RvPerAppState(all)
        "except" -> RvPerAppState(saved, draft = RvPerAppData.except)
        "search" -> RvPerAppState(saved, query = RvPerAppData.QUERY)
        "no-result" -> RvPerAppState(saved, query = RvPerAppData.QUERY_NONE)
        "loading" -> RvPerAppState(saved, installed = null)
        "none-selected" -> RvPerAppState(saved, draft = RvPerApp(RvAppPolicy.Only, emptySet()))
        "saving" -> RvPerAppState(saved, draft = RvPerAppData.except, save = RvSave.Saving)
        "save-failed" -> RvPerAppState(saved, draft = RvPerAppData.except, save = RvSave.Failed)
        "rebuild-failed" -> RvPerAppState(RvPerAppData.except, rebuildFailed = true)
        else -> RvPerAppState(saved)
    }
}

/** [rvPerAppSample]'s state [name] over the layouts' sample: the first twelve apps, three checked. */
internal fun rvPerAppLookSample(name: String): RvPerAppState {
    val state = rvPerAppSample(name, RvPerAppData.three)
    return if (state.installed == null) state else state.copy(installed = RvPerAppData.twelve)
}
