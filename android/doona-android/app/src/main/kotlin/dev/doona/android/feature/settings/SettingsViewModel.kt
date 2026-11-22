package dev.doona.android.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.doona.android.engine.Engine
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** The 設定 page only reads: each row opens the page that edits its setting. */
class SettingsViewModel(engine: Engine) : ViewModel() {
    val uiState: StateFlow<SettingsUiState> = engine.settings.map(::settingsUiState)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), settingsUiState(engine.settings.value))

    companion object {
        fun factory(engine: Engine): ViewModelProvider.Factory = viewModelFactory {
            initializer { SettingsViewModel(engine) }
        }
    }
}
