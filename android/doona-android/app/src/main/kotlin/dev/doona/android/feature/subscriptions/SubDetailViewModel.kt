package dev.doona.android.feature.subscriptions

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.doona.android.engine.EngineException
import dev.doona.android.engine.ErrorCode
import dev.doona.android.feature.errorCode
import dev.doona.android.feature.model.RvSubscription
import dev.doona.android.honk.DaeSubscription
import dev.doona.android.honk.NativeApi
import dev.doona.android.honk.NativeCatalog
import dev.doona.android.honk.NativeConfig
import dev.doona.android.honk.daeSubscription
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A subscription's page as it is drawn: the subscription, the sheet open on it, and the auto-update interval. Once a
 * read finds no such provider (removed here or elsewhere, or before a back stack restored after process death) [gone]
 * is set and the page leaves; [sub] then stays the last one read, so the page is still drawn while it leaves. Before
 * the first read the page draws nothing and stays.
 */
@Immutable
internal data class SubDetailUiState(
    val sub: RvSubscription?,
    val sheet: RvSubDetailState = RvSubDetailState(),
    val autoUpdate: RvAutoUpdate,
    val gone: Boolean = false,
    val readError: ErrorCode? = null,
)

/** honk's provider as last read, with the User-Agent its configuration entry sets; [gone] once a read finds none. */
private data class SubRead(val sub: RvSubscription? = null, val gone: Boolean = false, val error: ErrorCode? = null, val retryName: String? = null)

/**
 * Subscription [id]'s page state holder (plan §2.2): honk's provider and the User-Agent of its entry in honk's
 * configuration, read on [io] at start and after each change; the auto-update interval in hours as the app's settings
 * give it ([updateHours], null while auto-update is off; none shown until its first value); and the calls behind
 * 立即更新, 重新命名, the User-Agent sheet and 刪除訂閱. honk's API has no call that renames a provider or sets its
 * User-Agent, so both rewrite the provider's entry in the `subscription {}` block of the configuration file that holds
 * it, as doona web edits the main file, and honk reloads; honk keeps a provider's id across a reload only while its URL
 * and User-Agent stay, so the page follows the provider by its name after such a change. A call that fails leaves the
 * subscription as honk has it and says why: a rename under its field, the others in the page's banner.
 */
internal class SubDetailViewModel(
    api: NativeApi,
    updateHours: Flow<Int?>,
    private var id: String,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val catalog = NativeCatalog(api)
    private val config = NativeConfig(api)
    private val read = MutableStateFlow(SubRead())
    private val sheet = MutableStateFlow(RvSubDetailState())
    private var reading: Job? = null

    val uiState: StateFlow<SubDetailUiState> =
        combine(read, sheet, updateHours.map(::rvAutoUpdate).onStart { emit(RvAutoUpdate.Unread) }) { read, sheet, autoUpdate ->
            SubDetailUiState(read.sub, sheet, autoUpdate, read.gone, read.error)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_MS), SubDetailUiState(null, autoUpdate = RvAutoUpdate.Unread))

    init {
        load()
    }

    fun retryRead() = load(read.value.retryName)

    /** Reads the provider again, by [name] after a change to its entry, else by its id; a failed read keeps the last. */
    private fun load(name: String? = null) {
        reading?.cancel()
        reading = viewModelScope.launch {
            val provider = attempt { providers().firstOrNull { if (name != null) it.name == name else it.id == id } }.getOrElse { e ->
                read.update { it.copy(error = e.errorCode(), retryName = name) }
                return@launch
            }
            if (provider == null) {
                read.update { it.copy(gone = true) }
                return@launch
            }
            val agent = attempt { entry(provider.name)?.second?.userAgent }.getOrElse { error ->
                read.update { it.copy(error = error.errorCode(), retryName = name) }
                return@launch
            }
            id = provider.id
            read.value = SubRead(rvSubscription(provider, agent))
        }
    }

    fun change(state: RvSubDetailState) = sheet.update { state }

    /** Updates through honk (重試 on the banner, again); a refusal shows the banner, saying why. */
    fun update() = call(RvSubDetailFailure::Update, then = { load() }) { catalog.refreshProvider(id) }

    /**
     * Renames the provider's entry; a refusal (a name already in use, which honk's reload rejects, among them) opens
     * the sheet again on [name] with why under it.
     */
    fun rename(name: String) {
        viewModelScope.launch {
            attempt { edit { it.renamed(name) } }
                .onSuccess { load(name) }
                .onFailure { e -> sheet.update { it.copy(sheet = RvSubSheet.Rename, name = name, renameFailed = e.errorCode()) } }
        }
    }

    /** Saves [userAgent] in the provider's entry, null for honk's default (重試 on the banner, again); a refusal shows the banner, saying why. */
    fun setUserAgent(userAgent: String?) = call({ RvSubDetailFailure.UserAgent(it, userAgent) }, then = ::load) { edit { it.withUserAgent(userAgent) } }

    /**
     * Deletes through honk (重試 on the banner, again); a refusal shows the banner over the page, saying why.
     * `not_found` means it is gone already: the page leaves with no banner.
     */
    fun delete() = call(RvSubDetailFailure::Delete, then = { read.update { it.copy(gone = true) } }) {
        try {
            catalog.removeProvider(id)
        } catch (error: EngineException) {
            if (error.code != ErrorCode.NotFound) throw error
        }
    }

    /** The provider [name]'s entry and the configuration file that holds it; null when no file has one. */
    private fun entry(name: String) = config.config().sources.firstNotNullOfOrNull { source -> daeSubscription(source.content, name)?.let { source to it } }

    /** Replaces the file holding the provider's entry with [change] of it, and waits for honk's reload; the provider's name. */
    private suspend fun edit(change: (DaeSubscription) -> String): String {
        val name = checkNotNull(read.value.sub) { "a change before the first read" }.name
        val (source, entry) = entry(name) ?: throw EngineException(ErrorCode.NotFound)
        config.replace(source, change(entry))
        return name
    }

    /** Runs [action] on [io] in place of the banner, [then] on its result; its refusal becomes the banner [failure] makes of its code. */
    private fun <T> call(failure: (ErrorCode) -> RvSubDetailFailure, then: (T) -> Unit, action: suspend () -> T) {
        sheet.update { it.copy(failed = null) }
        viewModelScope.launch {
            attempt { action() }.onSuccess(then).onFailure { e -> sheet.update { it.copy(failed = failure(e.errorCode())) } }
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
