package dev.doona.android.feature.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.doona.android.engine.Engine
import dev.doona.android.feature.subscriptions.AddFormFields
import dev.doona.android.feature.subscriptions.SubscriptionAdder
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

class FirstRunViewModel(private val engine: Engine) : ViewModel() {
    private val adder = SubscriptionAdder(engine, viewModelScope)

    /** The add form's text, which the route hands the screen. */
    val fields: AddFormFields get() = adder.fields

    val uiState: StateFlow<FirstRunUiState> =
        combine(engine.ready, engine.subscriptions, engine.status, adder.state) { ready, subscriptions, status, form ->
            firstRunUiState(ready, subscriptions.size, status, form)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), current())

    /** A success needs no follow-up here: the new subscription flips [FirstRunUiState.hasSubscriptions] and the tab shows the activity screen. */
    fun onAction(action: FirstRunAction) {
        when (action) {
            FirstRunAction.Add -> adder.submit()
            FirstRunAction.DismissError -> adder.dismissError()
        }
    }

    /** The engine's flows are StateFlows, so the first frame already shows real values. */
    private fun current() = firstRunUiState(engine.ready.value, engine.subscriptions.value.size, engine.status.value, adder.state.value)

    companion object {
        fun factory(engine: Engine): ViewModelProvider.Factory = viewModelFactory {
            initializer { FirstRunViewModel(engine) }
        }
    }
}
