package dev.doona.android.feature.activity

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.doona.android.engine.Engine
import dev.doona.android.engine.ErrorCode
import dev.doona.android.engine.Mode
import dev.doona.android.feature.errorCode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ActivityViewModel(private val engine: Engine) : ViewModel() {
    private val error = MutableStateFlow<ErrorCode?>(null)

    val uiState: StateFlow<ActivityUiState> =
        combine(engine.status, engine.traffic, engine.settings, engine.groups, error, ::activityUiState)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), current())

    fun onAction(action: ActivityAction) {
        when (action) {
            ActivityAction.Disconnect -> engine.disconnect()

            is ActivityAction.SetMode -> setMode(action.mode)

            ActivityAction.DismissError -> error.value = null

            // The route sends these to the activity and the navigation shell.
            ActivityAction.Connect, is ActivityAction.OpenGroup -> Unit
        }
    }

    private fun setMode(mode: Mode) {
        viewModelScope.launch {
            error.value = engine.setMode(mode).exceptionOrNull()?.errorCode()
        }
    }

    /** The engine's flows are StateFlows, so the first frame already shows real values. */
    private fun current() =
        activityUiState(engine.status.value, engine.traffic.value, engine.settings.value, engine.groups.value, error.value)

    companion object {
        fun factory(engine: Engine): ViewModelProvider.Factory = viewModelFactory {
            initializer { ActivityViewModel(engine) }
        }
    }
}
