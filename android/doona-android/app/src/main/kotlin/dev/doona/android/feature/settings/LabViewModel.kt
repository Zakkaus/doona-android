package dev.doona.android.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn

/** 實驗室's state: 效能模式 as stored, and as this run of the app started with it. */
internal data class LabUiState(val performance: Boolean, val started: Boolean) {
    /** The switch differs from what the running core read at its start: it takes effect after a restart. */
    val restartPending: Boolean get() = performance != started
}

/** 實驗室 over the [store]: 效能模式 is stored at once and read by the core at its next start (owner, 2026-11-25 15:20). */
internal class LabViewModel(private val store: SettingsStore) : ViewModel() {
    /** Null until the store has answered both. */
    val uiState: StateFlow<LabUiState?> = combine(store.performanceMode, flow { emit(store.performanceAtStart()) }, ::LabUiState)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun setPerformance(enabled: Boolean) {
        store.setPerformanceMode(enabled)
    }

    companion object {
        fun factory(store: SettingsStore): ViewModelProvider.Factory = viewModelFactory {
            initializer { LabViewModel(store) }
        }
    }
}
