package dev.doona.android.feature.subscriptions

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.doona.android.engine.Engine
import dev.doona.android.engine.Subscription
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 訂閱 as the page draws it: the page's state, the subscriptions updating now, and whether the engine is still loading. */
@Immutable
internal data class SubsUiState(val page: RvSubsState, val updating: Set<String> = emptySet(), val loading: Boolean = false)

/** What the view model keeps besides the engine's values: updates running, the last one that failed, the delete asked. */
internal data class SubsLocal(val updating: Set<String> = emptySet(), val failedId: String? = null, val deletingId: String? = null)

/**
 * 訂閱: the engine's subscriptions mapped to the page, with the updates the user started, the banner of the last one
 * that failed (or of a subscription whose last refresh failed, by the core's report), and the delete question.
 */
internal fun subsUiState(ready: Boolean, subscriptions: List<Subscription>, local: SubsLocal, nowEpochMs: Long): SubsUiState {
    val ids = subscriptions.map { it.id }.toSet()
    val failedId = local.failedId?.takeIf { it in ids } ?: subscriptions.firstOrNull { it.error != null && it.id !in local.updating }?.id
    return SubsUiState(
        page = RvSubsState(subscriptions.map(::rvSubscription), nowEpochMs, failedId, local.deletingId?.takeIf { it in ids }),
        updating = local.updating intersect ids,
        loading = !ready,
    )
}

/** The subscriptions page's state holder (plan §2.2): reads the engine, and updates and removes subscriptions. */
internal class SubscriptionsViewModel(private val engine: Engine, private val clock: () -> Long = System::currentTimeMillis) : ViewModel() {
    private val local = MutableStateFlow(SubsLocal())

    val uiState: StateFlow<SubsUiState> =
        combine(engine.ready, engine.subscriptions, local) { ready, subscriptions, local -> subsUiState(ready, subscriptions, local, clock()) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_MS), subsUiState(engine.ready.value, engine.subscriptions.value, local.value, clock()))

    /** Updates [id]; a second tap while it runs does nothing. A failure shows the banner until the next update starts. */
    fun update(id: String) {
        var started = false
        local.update {
            started = id !in it.updating
            if (started) it.copy(updating = it.updating + id, failedId = it.failedId?.takeUnless { failed -> failed == id }) else it
        }
        if (!started) return
        viewModelScope.launch {
            val failed = engine.updateSubscription(id).isFailure
            local.update { it.copy(updating = it.updating - id, failedId = if (failed) id else it.failedId) }
        }
    }

    /** Updates every subscription, one after another, as the core takes one fetch at a time. */
    fun updateAll() {
        val ids = engine.subscriptions.value.map { it.id }.filterNot { it in local.value.updating }
        if (ids.isEmpty()) return
        local.update { it.copy(updating = it.updating + ids, failedId = null) }
        viewModelScope.launch {
            ids.forEach { id ->
                val failed = engine.updateSubscription(id).isFailure
                local.update { it.copy(updating = it.updating - id, failedId = if (failed) id else it.failedId) }
            }
        }
    }

    /** Asks to delete [id], or closes the question when null. */
    fun delete(id: String?) = local.update { it.copy(deletingId = id) }

    /** Deletes [id] after the question. A failure leaves the subscription in the list, where it still shows. */
    fun confirmDelete(id: String) {
        local.update { it.copy(deletingId = null) }
        viewModelScope.launch { engine.removeSubscription(id) }
    }
}

/** How long a page's state keeps flowing after its last collector stops: a rotation does not restart it. */
internal const val STOP_MS = 5_000L
