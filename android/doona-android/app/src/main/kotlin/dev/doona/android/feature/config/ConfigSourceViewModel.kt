package dev.doona.android.feature.config

import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.doona.android.engine.ConfigDraft
import dev.doona.android.engine.ConfigEditOp
import dev.doona.android.engine.ConfigEngine
import dev.doona.android.engine.DiagnosticCode
import dev.doona.android.engine.DraftFrom
import dev.doona.android.engine.ErrorCode
import dev.doona.android.engine.SourceAt
import dev.doona.android.feature.errorCode
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The source editor and its diff: [draft] is the text `configCheck` last checked (with its diagnostic), [value] the
 * editor's buffer, [diff] the check's diff once it passed (the diff page shows while it is set), [applied] once
 * 套用 made a new head, [failure] why the last command failed.
 */
@Immutable
internal data class ConfigSourceUiState(
    val draft: RvSourceDraft? = null,
    val value: TextFieldValue = TextFieldValue(),
    val diff: RvConfigDiff? = null,
    val applied: Boolean = false,
    val failure: ErrorCode? = null,
)

/**
 * 原始碼 of the file at [path] and 檢視變更 (config-plan.md: `configDraft` → `configRead` → `configEdit` → `configCheck` →
 * diff → `configApply`). The draft is the core's; Back drops it and never commits. 檢視變更 sends the buffer as the
 * file's text and checks it: a diagnostic in this file marks its span; a clean check reads both sides of every changed
 * file for the diff and today's head, which makes the diff stale when another apply landed first. A failed 套用
 * keeps the draft; `conflict` re-reads the head so the page says which revisions, and 重新載入 starts over from it.
 */
internal class ConfigSourceViewModel(
    private val engine: ConfigEngine,
    private val path: String,
    private val reads: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {
    private val state = MutableStateFlow(ConfigSourceUiState())
    val uiState: StateFlow<ConfigSourceUiState> = state.asStateFlow()
    private var draft: ConfigDraft? = null
    private var fileId = ""

    init {
        reload()
    }

    fun onValueChange(value: TextFieldValue) {
        state.update { it.copy(value = value) }
    }

    /** A new draft from today's head, the file's text in the editor. */
    fun reload() = launchRead {
        val tree = engine.configRead().getOrElse { return@launchRead fail(it) }
        fileId = tree.files.firstOrNull { it.path == path }?.id ?: return@launchRead fail(null)
        val started = engine.configDraft(DraftFrom.Head).getOrElse { return@launchRead fail(it) }
        val text = engine.configSource(SourceAt.Draft(started), fileId).getOrElse { return@launchRead fail(it) }
        draft = started
        state.value = ConfigSourceUiState(RvSourceDraft(path, text, started.baseRev), TextFieldValue(text))
    }

    /** 檢視變更: the buffer becomes the file's text, then the check, then the diff. */
    fun review() = launchRead {
        val current = draft ?: return@launchRead
        val text = state.value.value.text
        val edited = engine.configEdit(current, fileId, ConfigEditOp.ReplaceText(text)).getOrElse { return@launchRead fail(it) }
        draft = edited
        val check = engine.configCheck(edited).getOrElse { return@launchRead fail(it) }
        val mark = check.diagnostics.firstOrNull { it.code == DiagnosticCode.UnknownGroup && it.at?.fileId == fileId }?.at
        if (mark != null) {
            val diagnostic = RvConfigDiagnostic(mark.start, mark.end, rvSpanText(text, mark))
            state.update { it.copy(draft = RvSourceDraft(path, text, edited.baseRev, diagnostic), failure = null) }
            return@launchRead
        }
        val files = engine.rvCheckDiff(check).getOrElse { return@launchRead fail(it) }
        val head = engine.configRead().getOrElse { return@launchRead fail(it) }.rev
        state.update { it.copy(draft = RvSourceDraft(path, text, edited.baseRev), diff = RvConfigDiff(files, edited.baseRev, head), failure = null) }
    }

    /** 套用; on `conflict` the diff turns stale against the head read again. */
    fun apply() = launchRead {
        val current = draft ?: return@launchRead
        engine.configApply(current).onSuccess {
            draft = null
            state.update { it.copy(applied = true, failure = null) }
        }.onFailure { error ->
            val head = if (error.errorCode() == ErrorCode.Conflict) engine.configRead().getOrNull()?.rev else null
            state.update { it.copy(diff = head?.let { rev -> it.diff?.copy(headRev = rev) } ?: it.diff, failure = error.errorCode().takeIf { head == null }) }
        }
    }

    /** Back from the diff to the editor, the draft kept. */
    fun closeDiff() {
        state.update { it.copy(diff = null) }
    }

    private fun launchRead(block: suspend () -> Unit) {
        viewModelScope.launch(reads) { block() }
    }

    private fun fail(error: Throwable?) {
        state.update { it.copy(failure = error?.errorCode() ?: ErrorCode.NotFound) }
    }
}
