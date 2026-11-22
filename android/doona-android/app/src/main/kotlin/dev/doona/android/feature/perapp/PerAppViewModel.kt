package dev.doona.android.feature.perapp

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.doona.android.engine.Engine
import dev.doona.android.engine.ErrorCode
import dev.doona.android.engine.InstalledApp
import dev.doona.android.engine.InstalledApps
import dev.doona.android.engine.PerApp
import dev.doona.android.feature.errorCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Edits stay here until [PerAppAction.Save] hands them to the engine in one `setPerApp`, so a half-made selection never
 * reaches a running tunnel, which a save rebuilds. The edits survive rotation with the ViewModel and are lost with
 * the page.
 */
class PerAppViewModel(private val engine: Engine, private val installedApps: InstalledApps) : ViewModel() {
    /** What the page holds beyond the engine's values and the device's apps. */
    private data class Local(
        /** The edits over the saved policy; null until the first edit, so the page follows the engine until then. */
        val draft: PerApp? = null,
        val query: String = "",
        val busy: Boolean = false,
        val error: ErrorCode? = null,
    )

    private val local = MutableStateFlow(Local())

    /** The device's apps, read once when the page is first shown; null while they load. */
    private val installed: StateFlow<List<InstalledApp>?> = flow { emit(loadApps()) }
        .stateIn(viewModelScope, SharingStarted.Lazily, null)

    val uiState: StateFlow<PerAppUiState> =
        combine(engine.settings, installed, local) { settings, apps, local ->
            perAppUiState(settings.perApp, local.draft, apps, local.query, local.busy, local.error)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), current())

    fun onAction(action: PerAppAction) {
        when (action) {
            is PerAppAction.SetPolicy -> edit { it.copy(policy = action.policy) }
            is PerAppAction.Toggle -> edit { it.copy(packages = if (action.checked) it.packages + action.packageName else it.packages - action.packageName) }
            is PerAppAction.SetQuery -> local.update { it.copy(query = action.query) }
            PerAppAction.Save -> save()
            PerAppAction.DismissError -> local.update { it.copy(error = null) }
        }
    }

    private fun edit(change: (PerApp) -> PerApp) =
        local.update { it.copy(draft = change(it.draft ?: engine.settings.value.perApp)) }

    private fun save() {
        val saving = local.value.draft
        if (saving == null || !current().canSave) return
        local.update { it.copy(busy = true) }
        viewModelScope.launch {
            val failure = engine.setPerApp(saving).exceptionOrNull()
            local.update {
                // An edit made while the save ran stays; only an unchanged draft is dropped, so the page follows the engine again.
                it.copy(busy = false, error = failure?.errorCode(), draft = if (failure == null && it.draft == saving) null else it.draft)
            }
        }
    }

    /** A device that cannot list its apps shows no list and the error, so the page stays usable for the All policy. */
    private suspend fun loadApps(): List<InstalledApp> = try {
        installedApps.launchable()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        local.update { it.copy(error = e.errorCode()) }
        emptyList()
    }

    /** The engine's flows are StateFlows, so the first frame already shows real values. */
    private fun current() = local.value.let { perAppUiState(engine.settings.value.perApp, it.draft, installed.value, it.query, it.busy, it.error) }

    companion object {
        fun factory(engine: Engine, installedApps: InstalledApps): ViewModelProvider.Factory = viewModelFactory {
            initializer { PerAppViewModel(engine, installedApps) }
        }
    }
}
