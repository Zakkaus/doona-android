package dev.doona.android.feature.subscriptions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.doona.android.engine.Engine
import dev.doona.android.feature.errorCode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class SubscriptionsViewModel(
    private val engine: Engine,
    imports: SubscriptionImports,
    private val clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {
    // An import that is running, or that failed while the page was left, brings the form back, as it was left.
    private val local = MutableStateFlow(SubscriptionsLocal(adding = imports.state.value.let { it.busy || it.error != null }))
    private val adder = SubscriptionAdder(imports, viewModelScope, onImported = { local.update { it.copy(adding = false) } })

    /** The add form's text, which the route hands the screen. */
    val fields: AddFormFields get() = adder.fields

    val uiState: StateFlow<SubscriptionsUiState> =
        combine(engine.ready, engine.subscriptions, adder.state, local) { ready, subscriptions, form, local ->
            subscriptionsUiState(ready, subscriptions, form, local, clock())
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), current())

    fun onAction(action: SubscriptionsAction) {
        when (action) {
            is SubscriptionsAction.Update -> update(action.id)
            is SubscriptionsAction.AskRemove -> local.update { it.copy(removingId = action.id) }
            SubscriptionsAction.ConfirmRemove -> remove()
            SubscriptionsAction.CancelRemove -> local.update { it.copy(removingId = null) }
            SubscriptionsAction.OpenForm -> local.update { it.copy(adding = true) }
            SubscriptionsAction.CloseForm -> closeForm()
            SubscriptionsAction.Add -> adder.submit()
            SubscriptionsAction.DismissFormError -> adder.dismissError()
            SubscriptionsAction.DismissError -> dismissError()
        }
    }

    private fun dismissError() {
        local.update { it.copy(error = null) }
        adder.dismissError()
    }

    private fun closeForm() {
        adder.reset()
        local.update { it.copy(adding = false) }
    }

    private fun update(id: String) {
        if (id in local.value.updating) return
        local.update { it.copy(updating = it.updating + id) }
        viewModelScope.launch {
            val failure = engine.updateSubscription(id).exceptionOrNull()
            local.update { it.copy(updating = it.updating - id, error = failure?.errorCode()) }
        }
    }

    private fun remove() {
        val id = local.value.removingId ?: return
        local.update { it.copy(removingId = null) }
        viewModelScope.launch {
            val failure = engine.removeSubscription(id).exceptionOrNull()
            local.update { it.copy(error = failure?.errorCode()) }
        }
    }

    /** The engine's flows are StateFlows, so the first frame already shows real values. */
    private fun current() = subscriptionsUiState(engine.ready.value, engine.subscriptions.value, adder.snapshot(), local.value, clock())

    companion object {
        fun factory(engine: Engine, imports: SubscriptionImports): ViewModelProvider.Factory = viewModelFactory {
            initializer { SubscriptionsViewModel(engine, imports) }
        }
    }
}
