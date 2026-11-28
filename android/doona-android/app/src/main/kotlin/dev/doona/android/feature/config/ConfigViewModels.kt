package dev.doona.android.feature.config

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.doona.android.designsystem.rv.RvDiffFile
import dev.doona.android.engine.ConfigDraft
import dev.doona.android.engine.ConfigEngine
import dev.doona.android.engine.DiagnosticCode
import dev.doona.android.engine.DraftFrom
import dev.doona.android.engine.EngineException
import dev.doona.android.engine.ErrorCode
import dev.doona.android.engine.ExportKind
import dev.doona.android.engine.SourceAt
import dev.doona.android.feature.errorCode
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

    protected abstract suspend fun load(): Result<T>

    fun retry() {
        viewModelScope.launch(reads) {
            load().fold({ value -> state.value = ConfigRead(value) }, { error -> state.update { it.copy(failure = error.errorCode()) } })
        }
    }
}

/** 設定檔 and 已略過: `configRead`, the head's time from `configHistory`, and the text of every file holding a record. */
internal class ConfigOverviewViewModel(
    private val engine: ConfigEngine,
    private val now: () -> Long = System::currentTimeMillis,
    reads: CoroutineDispatcher = Dispatchers.Default,
) : ConfigReadViewModel<RvConfigState>(reads) {
    init {
        retry()
    }

    override suspend fun load(): Result<RvConfigState> {
        val tree = engine.configRead().getOrElse { return Result.failure(it) }
        val head = engine.configHistory().getOrElse { return Result.failure(it) }.first { it.rev == tree.rev }
        val texts = engine.texts(SourceAt.Revision(tree.rev), tree.support.map { it.at.fileId }).getOrElse { return Result.failure(it) }
        return Result.success(rvConfigState(tree, head.appliedAtEpochMs, texts, now()))
    }
}

/** 版本紀錄's content: the revisions newest first and the head. */
@Immutable
internal data class RvHistory(val revisions: List<RvRevision>, val headRev: Int, val nowEpochMs: Long)

/**
 * 版本紀錄: `configHistory`; a rename is `configName`, then a read; 匯出設定檔 stages the head with `configExport` as the
 * kind the sheet chose and hands its token on ([exported]) for the picker to stream (slice 13). A tree naming files
 * outside its root leaves an [exportNotice]: a portable copy is refused (no token), an archive leaves those files out.
 */
internal class ConfigHistoryViewModel(
    private val engine: ConfigEngine,
    private val now: () -> Long = System::currentTimeMillis,
    private val reads: CoroutineDispatcher = Dispatchers.Default,
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

    override suspend fun load(): Result<RvHistory> = engine.configHistory().map { list -> RvHistory(list.map(::rvRevision), list.first().rev, now()) }

    fun onRename(rename: RvRevisionRename?) {
        _rename.value = rename
    }

    fun saveName(rev: Int, name: String?) {
        viewModelScope.launch(reads) {
            engine.configName(rev, name).onSuccess { retry() }.onFailure { error -> state.update { it.copy(failure = error.errorCode()) } }
        }
    }

    fun export(kind: ExportKind) {
        val head = state.value.value?.headRev ?: return
        _exportNotice.value = null
        viewModelScope.launch(reads) {
            engine.configExport(head, kind).onSuccess { staged ->
                _exportNotice.value = staged.external.takeIf { it.isNotEmpty() }?.let { RvExportNotice(refused = staged.token == null, paths = it) }
                staged.token?.let { _exported.value = it }
            }.onFailure { error -> state.update { it.copy(failure = error.errorCode()) } }
        }
    }

    /** The export's banner was read. */
    fun exportNoticeSeen() {
        _exportNotice.value = null
    }

    /** The picker took the token. */
    fun exportHandled() {
        _exported.value = null
    }
}

/** One revision's page: the revision and what restoring it changes against the head, file by file. */
@Immutable
internal data class RvRevisionView(val revision: RvRevision, val files: List<RvDiffFile>, val nowEpochMs: Long)

/**
 * A revision's page: `configHistory` for the revision, a draft `from: revision` checked against the head for its
 * files, then 還原 applies that draft as a new revision ([restored] true). The head has nothing to restore.
 */
internal class ConfigRevisionViewModel(
    private val engine: ConfigEngine,
    private val rev: Int,
    private val now: () -> Long = System::currentTimeMillis,
    private val reads: CoroutineDispatcher = Dispatchers.Default,
) : ConfigReadViewModel<RvRevisionView>(reads) {
    private var draft: ConfigDraft? = null
    private val _rename = MutableStateFlow<RvRevisionRename?>(null)
    val rename: StateFlow<RvRevisionRename?> = _rename.asStateFlow()
    private val _restored = MutableStateFlow(false)
    val restored: StateFlow<Boolean> = _restored.asStateFlow()

    init {
        retry()
    }

    override suspend fun load(): Result<RvRevisionView> {
        val history = engine.configHistory().getOrElse { return Result.failure(it) }
        val revision = history.firstOrNull { it.rev == rev } ?: return Result.failure(EngineException(ErrorCode.NotFound))
        if (rev == history.first().rev) return Result.success(RvRevisionView(rvRevision(revision), emptyList(), now()))
        val started = engine.configDraft(DraftFrom.Revision(rev)).getOrElse { return Result.failure(it) }
        val check = engine.configCheck(started).getOrElse { return Result.failure(it) }
        val files = engine.rvCheckDiff(check).getOrElse { return Result.failure(it) }
        draft = started
        return Result.success(RvRevisionView(rvRevision(revision), files, now()))
    }

    fun onRename(rename: RvRevisionRename?) {
        _rename.value = rename
    }

    fun saveName(rev: Int, name: String?) {
        viewModelScope.launch(reads) {
            engine.configName(rev, name).onSuccess { retry() }.onFailure { error -> state.update { it.copy(failure = error.errorCode()) } }
        }
    }

    fun restore() {
        val pending = draft ?: return
        viewModelScope.launch(reads) {
            engine.configApply(pending).onSuccess { _restored.value = true }.onFailure { error -> state.update { it.copy(failure = error.errorCode()) } }
        }
    }
}

/**
 * 匯入設定檔 after the picker staged [token] for the file [name]: a draft `from: import`, refused at the budget, or
 * checked, with the first include outside the staged root on its line.
 */
internal class ConfigImportViewModel(
    private val engine: ConfigEngine,
    private val token: String,
    private val name: String,
    reads: CoroutineDispatcher = Dispatchers.Default,
) : ConfigReadViewModel<RvImportState>(reads) {
    init {
        retry()
    }

    override suspend fun load(): Result<RvImportState> {
        val head = engine.configRead().getOrElse { return Result.failure(it) }.rev
        val draft = engine.configDraft(DraftFrom.Import(token)).getOrElse { error ->
            return if (error.errorCode() == ErrorCode.SourceLimit) Result.success(RvImportState(name, head, refusedAtLimit = true)) else Result.failure(error)
        }
        val check = engine.configCheck(draft).getOrElse { return Result.failure(it) }
        val escape = check.diagnostics.firstOrNull { it.code == DiagnosticCode.IncludeEscape }?.at
        val text = escape?.let { engine.configSource(SourceAt.Draft(draft), it.fileId).getOrElse { error -> return Result.failure(error) } }
        return Result.success(
            RvImportState(name, head, files = rvConfigFiles(check.files), escapeLine = escape?.line ?: 0, escapePath = if (escape != null && text != null) rvSpanText(text, escape) else ""),
        )
    }
}
