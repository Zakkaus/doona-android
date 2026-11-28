package dev.doona.android.feature.config

import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.doona.android.engine.EngineException
import dev.doona.android.engine.ErrorCode
import dev.doona.android.feature.errorCode
import dev.doona.android.vpn.NativeApi
import dev.doona.android.vpn.honk.HonkSource
import dev.doona.android.vpn.honk.NativeConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The source editor and its diff: [draft] is the text honk last checked (with its diagnostic), [value] the
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
 * 原始碼 of the file at [path] and 檢視變更, over honk's native API: the file's accepted text in the editor; 檢視變更
 * has honk check the buffer laid over the file (`POST /config/validate`, the entry first): a group honk does not know
 * in this file marks its span; otherwise the diff of the file and today's revision, which makes the diff stale when
 * the file changed since it was read. 套用 replaces the file (`PUT /config/sources/{id}` with the hash read) and waits
 * for honk's reload; a file changed meanwhile turns the diff stale, and 重新載入 starts over from honk's text.
 */
internal class ConfigSourceViewModel(
    private val api: NativeApi,
    private val path: String,
    private val reads: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val state = MutableStateFlow(ConfigSourceUiState())
    val uiState: StateFlow<ConfigSourceUiState> = state.asStateFlow()
    private var base: HonkSource? = null

    init {
        reload()
    }

    fun onValueChange(value: TextFieldValue) {
        state.update { it.copy(value = value) }
    }

    /** The file's text as honk accepted it, in the editor. */
    fun reload() = launchRead {
        val config = NativeConfig(api).config()
        val source = config.files.firstOrNull { it.path == path } ?: throw EngineException(ErrorCode.NotFound)
        base = source
        state.value = ConfigSourceUiState(RvSourceDraft(path, source.content, config.revision), TextFieldValue(source.content))
    }

    /** 檢視變更: honk's check of the buffer, then the diff. */
    fun review() = launchRead {
        val source = base ?: return@launchRead
        val baseRev = state.value.draft?.baseRev ?: return@launchRead
        val text = state.value.value.text
        val native = NativeConfig(api)
        val entry = native.config().main
        val candidates = if (entry.path == path) listOf(path to text) else listOf(entry.path to entry.content, path to text)
        val candidate = "candidate-${candidates.lastIndex}"
        val mark = native.validate(candidates).diagnostics.firstOrNull { it.code == UNKNOWN_TARGET && it.sourceId == candidate }?.span
        if (mark != null) {
            val (start, end) = mark.bytes(text)
            state.update { it.copy(draft = RvSourceDraft(path, text, baseRev, RvConfigDiagnostic(start, end, rvSpanText(text, start, end))), failure = null) }
            return@launchRead
        }
        val files = listOfNotNull(rvLineDiff(path, source.content, text))
        val head = native.config()
        val headRev = if (head.files.firstOrNull { it.path == path }?.sha256 == source.sha256) baseRev else head.revision
        state.update { it.copy(draft = RvSourceDraft(path, text, baseRev), diff = RvConfigDiff(files, baseRev, headRev), failure = null) }
    }

    /** 套用; a file changed since it was read turns the diff stale against today's revision. */
    fun apply() = launchRead {
        val source = base ?: return@launchRead
        val text = state.value.draft?.text ?: return@launchRead
        try {
            NativeConfig(api).replace(source, text)
            base = null
            state.update { it.copy(applied = true, failure = null) }
        } catch (error: EngineException) {
            if (error.code != ErrorCode.Busy) throw error
            val head = NativeConfig(api).config().revision
            state.update { it.copy(diff = it.diff?.copy(headRev = head), failure = null) }
        }
    }

    /** Back from the diff to the editor, the text kept. */
    fun closeDiff() {
        state.update { it.copy(diff = null) }
    }

    private fun launchRead(block: suspend () -> Unit) {
        viewModelScope.launch(reads) {
            try {
                block()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                state.update { it.copy(failure = error.errorCode()) }
            }
        }
    }

    companion object {
        fun factory(api: NativeApi, path: String): ViewModelProvider.Factory = viewModelFactory { initializer { ConfigSourceViewModel(api, path) } }
    }
}

/** honk's code for a rule or setting naming a group or outbound it does not know. */
private const val UNKNOWN_TARGET = "unknown-routing-target"
