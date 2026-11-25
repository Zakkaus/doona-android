package dev.doona.android.feature.subscriptions

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.doona.android.engine.Engine
import dev.doona.android.engine.ErrorCode
import dev.doona.android.engine.Subscription
import dev.doona.android.feature.errorCode
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

/**
 * What the view model keeps besides the engine's values: updates running, the last one that failed, the delete asked,
 * the last delete the core refused, each failure with the core's code, and the subscriptions the core no longer has.
 */
internal data class SubsLocal(
    val updating: Set<String> = emptySet(),
    val failedId: String? = null,
    val deletingId: String? = null,
    val deleteFailedId: String? = null,
    val failedCode: ErrorCode = ErrorCode.FetchFailed,
    val deleteFailedCode: ErrorCode = ErrorCode.WriteFailed,
    val gone: Set<String> = emptySet(),
)

/**
 * 訂閱: the engine's subscriptions mapped to the page, with the updates the user started, the banner of the last one
 * that failed (or of a subscription whose last refresh failed, by the core's report), the delete question, and the
 * banner of a delete the core refused. A subscription the core answered `not_found` for is left out.
 */
internal fun subsUiState(ready: Boolean, listed: List<Subscription>, local: SubsLocal, nowEpochMs: Long): SubsUiState {
    val subscriptions = listed.filterNot { it.id in local.gone }
    val ids = subscriptions.map { it.id }.toSet()
    val reported = subscriptions.firstOrNull { it.error != null && it.id !in local.updating }
    val failed = local.failedId?.takeIf { it in ids }?.let { it to local.failedCode } ?: reported?.let { it.id to reportedCode(it.error) }
    return SubsUiState(
        page = RvSubsState(
            subscriptions.map(::rvSubscription),
            nowEpochMs,
            failed?.first,
            local.deletingId?.takeIf { it in ids },
            local.deleteFailedId?.takeIf { it in ids },
            failed?.second ?: ErrorCode.FetchFailed,
            local.deleteFailedCode,
        ),
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
            val failure = engine.updateSubscription(id).exceptionOrNull()
            local.update { it.failed(id, failure) }
        }
    }

    /** Updates every subscription, one after another, as the core takes one fetch at a time. */
    fun updateAll() {
        val ids = engine.subscriptions.value.map { it.id }.filterNot { it in local.value.updating }
        if (ids.isEmpty()) return
        local.update { it.copy(updating = it.updating + ids, failedId = null) }
        viewModelScope.launch {
            ids.forEach { id ->
                val failure = engine.updateSubscription(id).exceptionOrNull()
                local.update { it.failed(id, failure) }
            }
        }
    }

    /** Asks to delete [id], or closes the question when null. */
    fun delete(id: String?) = local.update { it.copy(deletingId = id) }

    /**
     * Deletes [id] after the question (重試 on the banner, again). A refusal leaves it in the list, with the banner saying
     * why; `not_found` means it is gone already, so it leaves the list with no banner.
     */
    fun confirmDelete(id: String) {
        local.update { it.copy(deletingId = null, deleteFailedId = null) }
        viewModelScope.launch {
            val code = engine.removeSubscription(id).exceptionOrNull()?.errorCode() ?: return@launch
            local.update { if (code == ErrorCode.NotFound) it.copy(gone = it.gone + id) else it.copy(deleteFailedId = id, deleteFailedCode = code) }
        }
    }
}

/** The code a subscription's last refresh failed with, an [ErrorCode] name as the engine gives it. */
private fun reportedCode(error: String?): ErrorCode = ErrorCode.entries.firstOrNull { it.name == error } ?: ErrorCode.FetchFailed

/** [id]'s update ended: a [failure] shows the banner with its code, until the next update starts. */
private fun SubsLocal.failed(id: String, failure: Throwable?) = copy(
    updating = updating - id,
    failedId = if (failure != null) id else failedId,
    failedCode = failure?.errorCode() ?: failedCode,
)

/** How long a page's state keeps flowing after its last collector stops: a rotation does not restart it. */
internal const val STOP_MS = 5_000L
