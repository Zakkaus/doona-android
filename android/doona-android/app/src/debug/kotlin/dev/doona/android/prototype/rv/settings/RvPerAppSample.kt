package dev.doona.android.prototype.rv.settings

import android.content.res.Configuration
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.tooling.preview.Preview
import dev.doona.android.designsystem.rv.RvTheme
import dev.doona.android.designsystem.rv.rvSystemScheme
import dev.doona.android.feature.model.RvAppPolicy
import dev.doona.android.feature.model.RvPerApp
import dev.doona.android.feature.perapp.RvApp
import dev.doona.android.feature.perapp.RvPerAppActions
import dev.doona.android.feature.perapp.RvPerAppScreen
import dev.doona.android.feature.perapp.RvPerAppState
import dev.doona.android.feature.perapp.RvSave

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
        "all" -> rvPerAppStateSample(all)
        "except" -> rvPerAppStateSample(saved, draft = RvPerAppData.except)
        "search" -> rvPerAppStateSample(saved, query = RvPerAppData.QUERY)
        "no-result" -> rvPerAppStateSample(saved, query = RvPerAppData.QUERY_NONE)
        "loading" -> RvPerAppState(saved, installed = null)
        "none-selected" -> rvPerAppStateSample(saved, draft = RvPerApp(RvAppPolicy.Only, emptySet()))
        "saving" -> rvPerAppStateSample(saved, draft = RvPerAppData.except, save = RvSave.Saving)
        "save-failed" -> rvPerAppStateSample(saved, draft = RvPerAppData.except, save = RvSave.Failed)
        "rebuild-failed" -> rvPerAppStateSample(RvPerAppData.except, rebuildFailed = true)
        else -> rvPerAppStateSample(saved)
    }
}

/** [rvPerAppSample]'s state [name] over the layouts' sample: the first twelve apps, three checked. */
internal fun rvPerAppLookSample(name: String): RvPerAppState {
    val state = rvPerAppSample(name, RvPerAppData.three)
    return if (state.installed == null) state else state.copy(installed = RvPerAppData.twelve)
}

/** 依 App's state on the sample: [RvPerAppData]'s apps installed unless the caller says otherwise. */
internal fun rvPerAppStateSample(
    saved: RvPerApp,
    draft: RvPerApp? = null,
    query: String = "",
    save: RvSave = RvSave.Idle,
    rebuildFailed: Boolean = false,
) = RvPerAppState(saved, draft, RvPerAppData.apps, query, save, rebuildFailed)

@Preview(name = "Light", locale = "zh-rTW", widthDp = 412, heightDp = 915)
@Preview(name = "Dark", locale = "zh-rTW", widthDp = 412, heightDp = 915, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun RvPerAppScreenPreview() {
    val dark = isSystemInDarkTheme()
    var state by remember { mutableStateOf(rvPerAppSample("")) }
    val actions = RvPerAppActions(policy = { state = state.withPolicy(it) }, toggle = { pkg, on -> state = state.withApp(pkg, on) }, query = { state = state.copy(query = it) })
    RvTheme(rvSystemScheme(dark), dark) { RvPerAppScreen(state, actions, onBack = {}) }
}
