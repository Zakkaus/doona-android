package dev.doona.android.feature.perapp

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.doona.android.engine.Engine
import dev.doona.android.engine.ErrorCode
import dev.doona.android.engine.FailureReason
import dev.doona.android.engine.InstalledApp
import dev.doona.android.engine.InstalledApps
import dev.doona.android.engine.PerApp
import dev.doona.android.engine.RebuildFailedException
import dev.doona.android.feature.errorCode
import dev.doona.android.feature.model.RvAppPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What [PerAppViewModel] handles. */
internal sealed interface PerAppAction {
    data class SetPolicy(val policy: RvAppPolicy) : PerAppAction

    data class Toggle(val packageName: String, val checked: Boolean) : PerAppAction

    data class SetQuery(val query: String) : PerAppAction

    data object Save : PerAppAction

    /** 重試 on the rebuild notice: the notice goes, and the route asks to connect again. */
    data object Reconnect : PerAppAction

    /** The page is shown again: the apps of the device may have changed while it was away. */
    data object Resume : PerAppAction
}

/**
 * Edits stay here until [PerAppAction.Save] hands them to the engine in one `setPerApp`, so a half-made selection never
 * reaches a running tunnel, which a save rebuilds. While a save runs the page takes no edit. The edits survive rotation
 * with the ViewModel and are lost with the page.
 */
internal class PerAppViewModel(private val engine: Engine, private val installedApps: InstalledApps) : ViewModel() {
    /** What the page holds beyond the engine's values and the device's apps. */
    private data class Local(
        /** The edits over the saved policy; null until the first edit, so the page follows the engine until then. */
        val draft: PerApp? = null,
        val query: String = "",
        val busy: Boolean = false,
        /** The code of the last failed write, shown until the next save succeeds. */
        val saveError: ErrorCode? = null,
        val rebuildFailure: FailureReason? = null,
    )

    private val local = MutableStateFlow(Local())

    /** The device's apps; null until the first read, and kept while a later one runs. */
    private val installed = MutableStateFlow<List<InstalledApp>?>(null)
    private var reading: Job? = null
    private var readAgain = false

    val uiState: StateFlow<RvPerAppState> =
        combine(engine.settings, engine.perAppLoaded, installed, local) { settings, loaded, apps, local ->
            state(settings.perApp, loaded, apps, local)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), current())

    fun onAction(action: PerAppAction) {
        when (action) {
            is PerAppAction.SetPolicy -> edit { it.copy(policy = action.policy.toEngine()) }
            is PerAppAction.Toggle -> edit { it.copy(packages = if (action.checked) it.packages + action.packageName else it.packages - action.packageName) }
            is PerAppAction.SetQuery -> local.update { it.copy(query = action.query) }
            PerAppAction.Save -> save()
            PerAppAction.Reconnect -> local.update { it.copy(rebuildFailure = null) }
            PerAppAction.Resume -> readApps()
        }
    }

    private fun edit(change: (PerApp) -> PerApp) {
        // Until the saved policy is read the engine shows the default, and a draft that starts from it would overwrite what was saved.
        if (!engine.perAppLoaded.value || local.value.busy) return
        local.update { it.copy(draft = change(it.draft ?: engine.settings.value.perApp)) }
    }

    private fun save() {
        val saving = local.value.draft
        if (saving == null || !current().canSave) return
        local.update { it.copy(busy = true) }
        viewModelScope.launch {
            val failure = engine.setPerApp(saving).exceptionOrNull()
            val rebuild = failure as? RebuildFailedException
            local.update {
                it.copy(
                    busy = false,
                    saveError = failure?.takeIf { rebuild == null }?.errorCode(),
                    rebuildFailure = rebuild?.reason,
                    // Stored, though the tunnel may not have come up with it: the page follows the engine again.
                    draft = if (failure == null || rebuild != null) null else it.draft,
                )
            }
        }
    }

    /**
     * Reads the device's apps on the page's first resume, and again whenever it is resumed, as an app may have come or
     * gone. A resume during a read does not start a second one beside it; it is remembered, and the read that is running
     * is followed by one more, since it may have looked at the device before the change.
     */
    private fun readApps() {
        if (reading?.isActive == true) {
            readAgain = true
            return
        }
        reading = viewModelScope.launch {
            do {
                readAgain = false
                installed.value = loadApps()
            } while (readAgain)
        }
    }

    /**
     * A device that cannot list its apps keeps the list it had, so the page stays usable for every policy; with none
     * read before, the page has no app to show and says so.
     */
    private suspend fun loadApps(): List<InstalledApp> = try {
        installedApps.launchable()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        installed.value.orEmpty()
    }

    private fun state(saved: PerApp, loaded: Boolean, apps: List<InstalledApp>?, local: Local) =
        rvPerAppState(saved, local.draft, apps, local.query, local.busy, local.saveError, loaded, local.rebuildFailure)

    /** The engine's flows are StateFlows, so the first frame already shows real values. */
    private fun current() = state(engine.settings.value.perApp, engine.perAppLoaded.value, installed.value, local.value)

    companion object {
        fun factory(engine: Engine, installedApps: InstalledApps): ViewModelProvider.Factory = viewModelFactory {
            initializer { PerAppViewModel(engine, installedApps) }
        }
    }
}
