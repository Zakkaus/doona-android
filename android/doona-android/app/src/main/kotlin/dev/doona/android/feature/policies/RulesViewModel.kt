package dev.doona.android.feature.policies

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.doona.android.engine.EngineException
import dev.doona.android.engine.ErrorCode
import dev.doona.android.feature.errorCode
import dev.doona.android.feature.model.CoreNodePlaces
import dev.doona.android.feature.model.NodePlaces
import dev.doona.android.feature.model.RvPolicyRule
import dev.doona.android.honk.HonkSource
import dev.doona.android.honk.NativeApi
import dev.doona.android.honk.NativeConfig
import dev.doona.android.honk.NativeRouting
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Why the rules cannot be listed: honk's files did not load, or reading them failed with [code]. */
internal sealed interface RulesFailure {
    data object Parse : RulesFailure

    data class Read(val code: ErrorCode) : RulesFailure
}

/**
 * What 全部規則 shows: every rule in order ([rules], honk's ids in [ids], null for a paused rule, which honk does not
 * list), or why it cannot ([failure]); [loading]
 * until the first read ends. A failed read keeps the rules last read under its banner, and none before the first read.
 * [writeFailure] says why a removal or a pause did not land.
 */
@Immutable
internal data class RulesUiState(
    val loading: Boolean = true,
    val rules: List<RvPolicyRule> = emptyList(),
    val ids: List<String?> = emptyList(),
    val failure: RulesFailure? = null,
    val writeFailure: ErrorCode? = null,
)

/**
 * 全部規則: every rule honk runs, in evaluation order with its fallback last, drawn through honk's groups as 策略's card
 * draws its first rows. When honk's last reload left its files unloaded, the rules it runs are not the ones written, so
 * none are listed. [load] reads on [io], at start, after its own writes and on 重試. A removal deletes the rule's line
 * from the file honk names, a pause keeps it as a comment; paused rules are read back from those comments
 * ([pausedRules]) and listed 已停用 where they are written, and turning one on writes its line back.
 */
internal class RulesViewModel(
    private val api: NativeApi,
    private val places: NodePlaces = CoreNodePlaces,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val state = MutableStateFlow(RulesUiState())
    val uiState: StateFlow<RulesUiState> = state.asStateFlow()
    private var reading: Job? = null
    private var entries: Pair<String, List<HonkRuleEntry>>? = null
    private var failedWrite: (() -> Unit)? = null

    init {
        load()
    }

    /** The banner's 重試: reads again. */
    fun retry() {
        failedWrite = null
        state.update { it.copy(writeFailure = null) }
        load()
    }

    fun retryWrite() {
        failedWrite?.invoke()
    }

    fun load() {
        reading?.cancel()
        reading = viewModelScope.launch {
            val next = read()
            state.update { next.copy(writeFailure = it.writeFailure) }
        }
    }

    /** Deletes the rule at [index]. */
    fun remove(index: Int) = write(index) { it.removed() }

    /** Pauses the rule at [index], or turns a paused one on again (its long-press menu's 停用 and 啟用). */
    fun toggle(index: Int) = write(index) { it.toggled() }

    private fun write(index: Int, edit: (Statement) -> Pair<HonkSource, String>) {
        val (generation, rows) = entries ?: return
        val entry = rows.getOrNull(index)?.takeUnless { it is HonkRuleEntry.Loaded && it.rule.isFallback } ?: return
        write(generation) { config ->
            edit(
                when (entry) {
                    is HonkRuleEntry.Loaded -> Statement(statementAt(config, entry.rule.source, entry.rule.expression), null)
                    is HonkRuleEntry.Paused -> Statement(pausedAt(config, entry.rule), entry.rule.expression)
                },
            )
        }
    }

    private fun write(generation: String, prepared: Pair<HonkSource, String>? = null, candidate: (dev.doona.android.honk.HonkConfig) -> Pair<HonkSource, String>) {
        failedWrite = null
        state.update { it.copy(writeFailure = null) }
        viewModelScope.launch {
            var edit = prepared
            try {
                val current = withContext(io) {
                    val native = NativeConfig(api)
                    val config = native.config()
                    if (prepared != null && config.sources.firstOrNull { it.id == prepared.first.id }?.content == prepared.second) return@withContext true
                    if (config.generationId != generation) return@withContext false
                    val (source, text) = edit ?: candidate(config).also { edit = it }
                    native.replace(source, text)
                    true
                }
                if (!current) {
                    failedWrite = ::retry
                    state.update { it.copy(writeFailure = ErrorCode.Busy) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                failedWrite = { write(generation, edit, candidate) }
                state.update { it.copy(writeFailure = error.errorCode()) }
            }
            load()
        }
    }

    private suspend fun read(): RulesUiState = try {
        withContext(io) {
            if (NativeRouting(api).sourcesRejected()) {
                RulesUiState(loading = false, failure = RulesFailure.Parse)
            } else {
                val read = HonkPolicyRead.read(api)
                val config = NativeConfig(api).config()
                if (config.generationId != read.rules.generationId) throw EngineException(ErrorCode.Busy)
                val entries = withPaused(read.rules.rules, pausedRules(config)).also { this@RulesViewModel.entries = config.generationId to it }
                val groups = read.policyGroups(read.nodes(places))
                val rules = read.policyRules(groups, entries)
                RulesUiState(loading = false, rules = rules, ids = entries.map { (it as? HonkRuleEntry.Loaded)?.rule?.id })
            }
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        RulesUiState(loading = false, rules = state.value.rules, ids = state.value.ids, failure = RulesFailure.Read(error.errorCode()))
    }

    /** A rule's line, with the rule text when it is paused ([paused]). */
    private class Statement(val at: HonkStatementAt, val paused: String?) {
        fun removed() = at.removed()

        fun toggled() = paused?.let(at::replaced) ?: at.paused()
    }

    companion object {
        fun factory(api: NativeApi): ViewModelProvider.Factory = viewModelFactory {
            initializer { RulesViewModel(api) }
        }
    }
}
