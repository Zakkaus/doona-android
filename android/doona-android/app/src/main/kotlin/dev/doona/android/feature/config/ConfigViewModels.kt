package dev.doona.android.feature.config

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.doona.android.designsystem.rv.RvDiffFile
import dev.doona.android.engine.EngineException
import dev.doona.android.engine.ErrorCode
import dev.doona.android.engine.ExportKind
import dev.doona.android.feature.errorCode
import dev.doona.android.feature.policies.HonkPolicyRead
import dev.doona.android.vpn.NativeApi
import dev.doona.android.vpn.honk.NativeConfig
import dev.doona.android.vpn.honk.NativeRevisions
import dev.doona.android.vpn.honk.NativeRouting
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A page of the configuration that reads once and again on 重試: its [value] once read, why the last read failed. */
@Immutable
internal data class ConfigRead<T>(val value: T? = null, val failure: ErrorCode? = null)

/** Reads [load] on [reads] into [state] now and on every [retry]; a failed read keeps what was read before. */
internal abstract class ConfigReadViewModel<T>(private val reads: CoroutineDispatcher) : ViewModel() {
    protected val state = MutableStateFlow(ConfigRead<T>())
    val uiState: StateFlow<ConfigRead<T>> = state.asStateFlow()

    /** Blocking reads of honk. */
    protected abstract suspend fun load(): T

    fun retry() = attempt { state.value = ConfigRead(load()) }

    /** Runs [block] on [reads]; a failure lands in the state's [ConfigRead.failure]. */
    protected fun attempt(block: suspend () -> Unit) {
        viewModelScope.launch(reads) {
            try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                state.update { it.copy(failure = error.errorCode()) }
            }
        }
    }
}

/** 設定檔 and 已略過: honk's accepted files (`GET /config`) and what it runs from them. */
internal class ConfigOverviewViewModel(
    private val api: NativeApi,
    private val now: () -> Long = System::currentTimeMillis,
    reads: CoroutineDispatcher = Dispatchers.IO,
) : ConfigReadViewModel<RvConfigState>(reads) {
    init {
        retry()
    }

    override suspend fun load(): RvConfigState =
        rvConfigState(NativeConfig(api).config(), HonkPolicyRead.read(api), NativeRouting(api).dnsRules(), now())

    companion object {
        fun factory(api: NativeApi): ViewModelProvider.Factory = viewModelFactory { initializer { ConfigOverviewViewModel(api) } }
    }
}

/** 版本紀錄's content: the revisions newest first and the active one. */
@Immutable
internal data class RvHistory(val revisions: List<RvRevision>, val headRev: Int, val nowEpochMs: Long)

/**
 * 版本紀錄: honk's configuration db (`GET /x-honk/config/revisions`). 匯出設定檔 hands honk's export of the accepted
 * files ([exported], one inlined document whichever kind the sheet chose) on for the picker to write. honk keeps no
 * revision names, so a rename is not kept (owner question).
 */
internal class ConfigHistoryViewModel(
    private val api: NativeApi,
    private val now: () -> Long = System::currentTimeMillis,
    reads: CoroutineDispatcher = Dispatchers.IO,
) : ConfigReadViewModel<RvHistory>(reads) {
    private val _rename = MutableStateFlow<RvRevisionRename?>(null)
    val rename: StateFlow<RvRevisionRename?> = _rename.asStateFlow()
    private val _exported = MutableStateFlow<String?>(null)
    val exported: StateFlow<String?> = _exported.asStateFlow()
    private val _exportNotice = MutableStateFlow<RvExportNotice?>(null)
    val exportNotice: StateFlow<RvExportNotice?> = _exportNotice.asStateFlow()

    init {
        retry()
    }

    override suspend fun load(): RvHistory {
        val history = NativeRevisions(api).revisions()
        return RvHistory(history.revisions.map(::rvRevision), history.active?.toInt() ?: 0, now())
    }

    fun onRename(rename: RvRevisionRename?) {
        _rename.value = rename
    }

    @Suppress("UNUSED_PARAMETER")
    fun saveName(rev: Int, name: String?) {
        _rename.value = null
    }

    @Suppress("UNUSED_PARAMETER")
    fun export(kind: ExportKind) {
        attempt { _exported.value = NativeConfig(api).export() }
    }

    /** The export's banner was read. */
    fun exportNoticeSeen() {
        _exportNotice.value = null
    }

    /** The picker took the text. */
    fun exportHandled() {
        _exported.value = null
    }

    companion object {
        fun factory(api: NativeApi): ViewModelProvider.Factory = viewModelFactory { initializer { ConfigHistoryViewModel(api) } }
    }
}

/** One revision's page: the revision and the files restoring it changes against the active one, line by line. */
@Immutable
internal data class RvRevisionView(val revision: RvRevision, val files: List<RvDiffFile>, val nowEpochMs: Long)

/**
 * A revision's page: the revision from honk's db. honk lists a revision's files by hash, without their text, so the
 * page shows no diff and no 還原 until honk serves a revision's text (owner question); 還原 activates the revision,
 * which honk records as a new revision ([restored] true).
 */
internal class ConfigRevisionViewModel(
    private val api: NativeApi,
    private val rev: Int,
    private val now: () -> Long = System::currentTimeMillis,
    reads: CoroutineDispatcher = Dispatchers.IO,
) : ConfigReadViewModel<RvRevisionView>(reads) {
    private val _rename = MutableStateFlow<RvRevisionRename?>(null)
    val rename: StateFlow<RvRevisionRename?> = _rename.asStateFlow()
    private val _restored = MutableStateFlow(false)
    val restored: StateFlow<Boolean> = _restored.asStateFlow()

    init {
        retry()
    }

    override suspend fun load(): RvRevisionView {
        val history = NativeRevisions(api).revisions()
        val revision = history.revisions.firstOrNull { it.revision == rev.toLong() } ?: throw EngineException(ErrorCode.NotFound)
        return RvRevisionView(rvRevision(revision), emptyList(), now())
    }

    fun onRename(rename: RvRevisionRename?) {
        _rename.value = rename
    }

    @Suppress("UNUSED_PARAMETER")
    fun saveName(rev: Int, name: String?) {
        _rename.value = null
    }

    fun restore() {
        attempt {
            NativeRevisions(api).activate(rev.toLong())
            _restored.value = true
        }
    }

    companion object {
        fun factory(api: NativeApi, rev: Int): ViewModelProvider.Factory = viewModelFactory { initializer { ConfigRevisionViewModel(api, rev) } }
    }
}

/**
 * 匯入設定檔 for the picked file [name] with [text], as the entry file: refused past honk's check budget
 * ([RvConfigLimits.BYTES]), the configuration unchanged. What an import writes is an owner question.
 */
internal class ConfigImportViewModel(
    private val api: NativeApi,
    private val text: String,
    private val name: String,
    reads: CoroutineDispatcher = Dispatchers.IO,
) : ConfigReadViewModel<RvImportState>(reads) {
    init {
        retry()
    }

    override suspend fun load(): RvImportState {
        val config = NativeConfig(api).config()
        if (text.encodeToByteArray().size > RvConfigLimits.BYTES) return RvImportState(name, config.revision, refusedAtLimit = true)
        return RvImportState(name, config.revision, files = listOf(RvConfigFile(name, null, text.lines().size)))
    }
}
