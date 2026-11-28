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
import dev.doona.android.feature.errorCode
import dev.doona.android.feature.policies.HonkPolicyRead
import dev.doona.android.honk.NativeApi
import dev.doona.android.honk.NativeConfig
import dev.doona.android.honk.NativeRevisions
import dev.doona.android.honk.NativeRouting
import dev.doona.android.honk.daeCode
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
internal abstract class ConfigReadViewModel<T>(protected val reads: CoroutineDispatcher) : ViewModel() {
    protected val state = MutableStateFlow(ConfigRead<T>())
    val uiState: StateFlow<ConfigRead<T>> = state.asStateFlow()

    /** Blocking reads of honk. */
    protected abstract suspend fun load(): T

    fun retry() = attempt { state.value = ConfigRead(load()) }

    /** Runs [block] on [reads]; read failures keep the previously loaded value. */
    protected fun attempt(onFailure: (ErrorCode) -> Unit = { code -> state.update { it.copy(failure = code) } }, block: suspend () -> Unit) {
        viewModelScope.launch(reads) {
            try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                onFailure(error.errorCode())
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
    private val _imported = MutableStateFlow<RvConfigImport?>(null)

    /** How the last 匯入 ended, until its banner is read. */
    val imported: StateFlow<RvConfigImport?> = _imported.asStateFlow()

    override suspend fun load(): RvConfigState =
        rvConfigState(NativeConfig(api).config(), HonkPolicyRead.read(api), NativeRouting(api).dnsRules(), now())

    /**
     * 匯入: honk checks [text] as the entry file ([NativeConfig.validate]); a file it accepts replaces the entry file and
     * the page reads again, one it rejects changes nothing and says honk's first error (owner, q-import 2026-11-26).
     * A null [text] means the chosen file could not be read.
     */
    fun import(text: String?, readError: ErrorCode? = null) {
        if (text == null) {
            _imported.value = RvConfigImport.Failed(readError)
            return
        }
        _imported.value = null
        viewModelScope.launch(reads) {
            _imported.value = try {
                val native = NativeConfig(api)
                val main = native.config().main
                val check = native.validate(listOf(main.path to text))
                if (check.valid) {
                    native.replace(main, text)
                    retry()
                    RvConfigImport.Done
                } else {
                    RvConfigImport.Rejected(check.diagnostics.firstOrNull { it.level == "error" }?.message ?: check.diagnostics.firstOrNull()?.message)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                RvConfigImport.Failed(error.errorCode())
            }
        }
    }

    /** The import's banner was read. */
    fun importSeen() {
        _imported.value = null
    }

    private val _writeFailure = MutableStateFlow<ErrorCode?>(null)

    /** Why the last removal did not land. */
    val writeFailure: StateFlow<ErrorCode?> = _writeFailure.asStateFlow()

    /**
     * The files card's remove control (owner, q-edit-wire 2026-11-26): deletes the line of the `include { }` block that
     * names [path] in the file that includes it, so the file and what it includes leave the configuration; honk checks
     * the file before it takes it. The entry file cannot go.
     */
    fun removeFile(path: String) {
        val parent = state.value.value?.files?.firstOrNull { it.path == path }?.parent ?: return
        _writeFailure.value = null
        viewModelScope.launch(reads) {
            try {
                val native = NativeConfig(api)
                val source = native.config().files.firstOrNull { it.path == parent } ?: throw EngineException(ErrorCode.NotFound)
                val lines = source.content.lines()
                val at = includeLine(lines, path) ?: throw EngineException(ErrorCode.NotFound)
                native.replace(source, lines.filterIndexed { i, _ -> i != at }.joinToString("\n"))
                retry()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _writeFailure.value = error.errorCode()
            }
        }
    }

    companion object {
        fun factory(api: NativeApi): ViewModelProvider.Factory = viewModelFactory { initializer { ConfigOverviewViewModel(api) } }
    }
}

/** The 0-based line of an `include { }` block in [lines] that names [path] (quotes aside), or null. */
internal fun includeLine(lines: List<String>, path: String): Int? {
    var inside = false
    lines.forEachIndexed { i, line ->
        val code = daeCode(line)
        when {
            !inside && INCLUDE_OPEN.matches(code) -> inside = true
            inside && code == "}" -> inside = false
            inside && code.trim('\'', '"', ',', ' ') == path -> return i
        }
    }
    return null
}

private val INCLUDE_OPEN = Regex("""include\s*\{""")

/** How 匯入 ended: the file replaced the entry file, honk rejected it, or the read or write failed. */
internal sealed interface RvConfigImport {
    data object Done : RvConfigImport

    data class Rejected(val message: String?) : RvConfigImport

    data class Failed(val code: ErrorCode?) : RvConfigImport
}

/** 版本紀錄's content: the revisions newest first and the active one. */
@Immutable
internal data class RvHistory(val revisions: List<RvRevision>, val headRev: Int, val nowEpochMs: Long)

/**
 * 版本紀錄: honk's configuration db (`GET /x-honk/config/revisions`). 匯出設定檔 hands honk's export of the accepted
 * files ([exported], one inlined document whichever kind the sheet chose) on for the picker to write.
 */
internal class ConfigHistoryViewModel(
    private val api: NativeApi,
    private val now: () -> Long = System::currentTimeMillis,
    reads: CoroutineDispatcher = Dispatchers.IO,
) : ConfigReadViewModel<RvHistory>(reads) {
    private val _exported = MutableStateFlow<String?>(null)
    val exported: StateFlow<String?> = _exported.asStateFlow()
    private val _exportNotice = MutableStateFlow(ConfigRead<RvExportNotice>())
    val exportNotice = _exportNotice.asStateFlow()

    override suspend fun load(): RvHistory {
        val history = NativeRevisions(api).revisions()
        return RvHistory(history.revisions.map(::rvRevision), history.active?.toInt() ?: 0, now())
    }

    fun export() {
        exportNoticeSeen()
        attempt(onFailure = { _exportNotice.value = ConfigRead(failure = it) }) { _exported.value = NativeConfig(api).export() }
    }

    /** The export's banner was read. */
    fun exportNoticeSeen() {
        _exportNotice.value = ConfigRead()
    }

    fun exportHandled(open: (() -> java.io.OutputStream?)?) {
        val text = _exported.value
        _exported.value = null
        if (open != null) {
            attempt(onFailure = { _exportNotice.value = ConfigRead(failure = ErrorCode.WriteFailed) }) {
                checkNotNull(text)
                checkNotNull(open()).bufferedWriter().use { it.write(text) }
                _exportNotice.value = ConfigRead(RvExportNotice(false, emptyList()))
            }
        }
    }

    companion object {
        fun factory(api: NativeApi): ViewModelProvider.Factory = viewModelFactory { initializer { ConfigHistoryViewModel(api) } }
    }
}

/** One revision's page: the revision and the files restoring it changes against the active one, line by line. */
@Immutable
internal data class RvRevisionView(val revision: RvRevision, val files: List<RvDiffFile>?, val nowEpochMs: Long, val headRev: Int?)

/** honk lists revision files by hash, without their text. Restoring activates the revision as a new head. */
internal class ConfigRevisionViewModel(
    private val api: NativeApi,
    private val rev: Int,
    private val now: () -> Long = System::currentTimeMillis,
    reads: CoroutineDispatcher = Dispatchers.IO,
) : ConfigReadViewModel<RvRevisionView>(reads) {
    private val _restored = MutableStateFlow(false)
    val restored: StateFlow<Boolean> = _restored.asStateFlow()

    init {
        retry()
    }

    override suspend fun load(): RvRevisionView {
        val history = NativeRevisions(api).revisions()
        val revision = history.revisions.firstOrNull { it.revision == rev.toLong() } ?: throw EngineException(ErrorCode.NotFound)
        // honk-gap:rev-content
        val head = history.active?.toInt()
        return RvRevisionView(rvRevision(revision), if (rev == head) emptyList() else null, now(), head)
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
