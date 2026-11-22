package dev.doona.android.feature.policies

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.doona.android.engine.Engine
import dev.doona.android.engine.ErrorCode
import dev.doona.android.feature.errorCode
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class GroupViewModel(private val engine: Engine, private val groupId: String) : ViewModel() {
    private val error = MutableStateFlow<ErrorCode?>(null)

    /**
     * Engine actions in tap order. The core keeps the last request it receives, and a call to it can take a while, so
     * each action runs to its answer before the next starts: two quick taps otherwise reach it in either order.
     */
    private val actions = Channel<suspend () -> Result<Unit>>(Channel.UNLIMITED)

    val uiState: StateFlow<GroupUiState> = combine(engine.ready, engine.groups, error) { ready, groups, error ->
        groupUiState(ready, groups, groupId, error)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), current())

    init {
        viewModelScope.launch {
            for (action in actions) error.value = action().exceptionOrNull()?.errorCode()
        }
    }

    fun onAction(action: GroupAction) {
        when (action) {
            is GroupAction.Select -> perform { engine.select(groupId, action.memberId) }
            GroupAction.ReturnToAutomatic -> perform { engine.select(groupId, null) }
            GroupAction.Test -> perform { engine.testLatency(groupId) }
            GroupAction.DismissError -> error.value = null
        }
    }

    /** Queues an engine action, whose failure is shown when it ends, or whose success clears the last one. */
    private fun perform(action: suspend () -> Result<Unit>) {
        actions.trySend(action)
    }

    /** The engine's flows are StateFlows, so the first frame already shows real values. */
    private fun current() = groupUiState(engine.ready.value, engine.groups.value, groupId, error.value)

    companion object {
        fun factory(engine: Engine, groupId: String): ViewModelProvider.Factory = viewModelFactory {
            initializer { GroupViewModel(engine, groupId) }
        }
    }
}
