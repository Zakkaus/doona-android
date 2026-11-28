package dev.doona.android.feature.subscriptions

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.doona.android.engine.ErrorCode
import dev.doona.android.feature.errorCode
import dev.doona.android.honk.NativeApi
import dev.doona.android.honk.NativeCatalog
import dev.doona.android.honk.Provider
import dev.doona.android.honk.ProviderKind
import dev.doona.android.honk.errorCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 訂閱 as the page draws it: the page's state, the subscriptions updating now, and whether the first read is running. */
@Immutable
internal data class SubsUiState(val page: RvSubsState, val updating: Set<String> = emptySet(), val loading: Boolean = false, val readError: ErrorCode? = null)

/**
 * What the view model keeps besides honk's providers: updates running, the last one that failed, the delete asked and
 * the last delete honk refused, each failure with its code.
 */
internal data class SubsLocal(
    val updating: Set<String> = emptySet(),
    val failed: RvSubFailure? = null,
    val deletingId: String? = null,
    val deleteFailed: RvSubFailure? = null,
    val readError: ErrorCode? = null,
)

/**
 * 訂閱: honk's subscription providers mapped to the page ([listed] null until the first read ends), with the updates
 * the user started, the banner of the last one that failed (or of a provider whose last fetch failed, by honk's
 * `last_error`), the delete question, and the banner of a delete honk refused.
 */
internal fun subsUiState(listed: List<Provider>?, local: SubsLocal, nowEpochMs: Long): SubsUiState {
    val providers = listed.orEmpty().filter { it.kind == ProviderKind.Subscription }
    val ids = providers.map { it.id }.toSet()
    val reported = providers.firstNotNullOfOrNull { p -> p.lastError?.takeIf { p.id !in local.updating }?.let { RvSubFailure(p.id, it.errorCode()) } }
    val failed = local.failed?.takeIf { it.id in ids } ?: reported
    return SubsUiState(
        page = RvSubsState(
            providers.map { rvSubscription(it, userAgent = null) },
            nowEpochMs,
            failed,
            local.deletingId?.takeIf { it in ids },
            local.deleteFailed?.takeIf { it.id in ids },
        ),
        updating = local.updating intersect ids,
        loading = listed == null && local.readError == null,
        readError = local.readError,
    )
}

/**
 * The subscriptions page's state holder (plan §2.2): reads honk's providers on [io] at start and after each update or
 * delete, and updates and removes them. honk pushes nothing, so the list is as last read.
 */
internal class SubscriptionsViewModel(
    api: NativeApi,
    private val clock: () -> Long = System::currentTimeMillis,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val catalog = NativeCatalog(api)
    private val providers = MutableStateFlow<List<Provider>?>(null)
    private val local = MutableStateFlow(SubsLocal())
    private var reading: Job? = null

    val uiState: StateFlow<SubsUiState> =
        combine(providers, local) { providers, local -> subsUiState(providers, local, clock()) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_MS), subsUiState(providers.value, local.value, clock()))

    /** Reads the providers again; a failed read keeps the list last read (none before the first). */
    fun load() {
        reading?.cancel()
        reading = viewModelScope.launch {
            val read = attempt { providers() }
            read.onSuccess { providers.value = it }
            local.update { it.copy(readError = read.exceptionOrNull()?.errorCode()) }
        }
    }

    /** Updates [id]; a second tap while it runs does nothing. A failure shows the banner until the next update starts. */
    fun update(id: String) {
        var started = false
        local.update {
            started = id !in it.updating
            if (started) it.copy(updating = it.updating + id, failed = it.failed?.takeUnless { failed -> failed.id == id }) else it
        }
        if (!started) return
        viewModelScope.launch {
            val failure = attempt { refreshProvider(id) }.exceptionOrNull()
            local.update { it.failed(id, failure) }
            load()
        }
    }

    /** Updates every subscription, one after another, as honk runs one refresh of each at a time. */
    fun updateAll() {
        val ids = providers.value.orEmpty().filter { it.kind == ProviderKind.Subscription }.map { it.id }.filterNot { it in local.value.updating }
        if (ids.isEmpty()) return
        local.update { it.copy(updating = it.updating + ids, failed = null) }
        viewModelScope.launch {
            ids.forEach { id ->
                val failure = attempt { refreshProvider(id) }.exceptionOrNull()
                local.update { it.failed(id, failure) }
            }
            load()
        }
    }

    /** Asks to delete [id], or closes the question when null. */
    fun delete(id: String?) = local.update { it.copy(deletingId = id) }

    /**
     * Deletes [id] after the question (重試 on the banner, again). A refusal leaves it in the list, with the banner saying
     * why.
     */
    fun confirmDelete(id: String) {
        local.update { it.copy(deletingId = null, deleteFailed = null) }
        viewModelScope.launch {
            val code = attempt { removeProvider(id) }.exceptionOrNull()?.errorCode()
            if (code != null) local.update { it.copy(deleteFailed = RvSubFailure(id, code)) }
            load()
        }
    }

    /** [action] on [io]; its failure, a cancellation apart, is the result's. */
    private suspend fun <T> attempt(action: suspend NativeCatalog.() -> T): Result<T> = try {
        Result.success(withContext(io) { catalog.action() })
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        Result.failure(error)
    }
}

/** [id]'s update ended: a [failure] shows the banner with its code, until the next update starts. */
private fun SubsLocal.failed(id: String, failure: Throwable?) = copy(
    updating = updating - id,
    failed = failure?.let { RvSubFailure(id, it.errorCode()) } ?: failed,
)

/** How long a page's state keeps flowing after its last collector stops: a rotation does not restart it. */
internal const val STOP_MS = 5_000L
