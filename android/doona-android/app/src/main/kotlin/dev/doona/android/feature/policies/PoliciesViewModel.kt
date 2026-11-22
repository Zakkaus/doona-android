package dev.doona.android.feature.policies

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.doona.android.engine.Engine
import dev.doona.android.engine.ErrorCode
import dev.doona.android.feature.errorCode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class PoliciesViewModel(private val engine: Engine) : ViewModel() {
    private val filter = MutableStateFlow(GroupFilter.All)
    private val error = MutableStateFlow<ErrorCode?>(null)

    val uiState: StateFlow<PoliciesUiState> = combine(engine.ready, engine.groups, filter, error, ::policiesUiState)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), current())

    fun onAction(action: PoliciesAction) {
        when (action) {
            PoliciesAction.TestAll -> testAll()
            is PoliciesAction.SetFilter -> filter.value = action.filter
            PoliciesAction.DismissError -> error.value = null
        }
    }

    private fun testAll() {
        viewModelScope.launch {
            error.value = engine.testLatency(null).exceptionOrNull()?.errorCode()
        }
    }

    /** The engine's flows are StateFlows, so the first frame already shows real values. */
    private fun current() = policiesUiState(engine.ready.value, engine.groups.value, filter.value, error.value)

    companion object {
        fun factory(engine: Engine): ViewModelProvider.Factory = viewModelFactory {
            initializer { PoliciesViewModel(engine) }
        }
    }
}
