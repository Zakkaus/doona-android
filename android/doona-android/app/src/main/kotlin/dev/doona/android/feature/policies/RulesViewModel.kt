package dev.doona.android.feature.policies

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.doona.android.engine.Catalog
import dev.doona.android.engine.Engine
import dev.doona.android.engine.EngineException
import dev.doona.android.engine.ErrorCode
import dev.doona.android.engine.RuleRow
import dev.doona.android.feature.errorCode
import dev.doona.android.feature.model.RvPolicyRule
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Why the rules cannot be listed: the source did not compile, or reading it failed with [code]. */
internal sealed interface RulesFailure {
    data object Parse : RulesFailure

    data class Read(val code: ErrorCode) : RulesFailure
}

/**
 * What 全部規則 shows: every rule in order with today's bytes ([rules]), or why it cannot ([failure]); [loading] until the
 * first read ends. A failed read keeps the rules last read under its banner, and none before the first read.
 */
@Immutable
internal data class RulesUiState(
    val loading: Boolean = true,
    val rules: List<RvPolicyRule> = emptyList(),
    val failure: RulesFailure? = null,
)

/**
 * 全部規則: every row of the rule source, read [RULES_PAGE] at a time on [reads] when the catalog changes, joined with
 * today's bytes per rule and drawn through the catalog's groups, as 策略's card draws its first rows. A page read at
 * another revision than the catalog's is stale, so the read starts over, a few times at most; a newer catalog starts it
 * over anyway. A source that did not compile has no rows to list.
 */
internal class RulesViewModel(
    private val engine: Engine,
    private val places: RvNodePlaces = RV_UNKNOWN_PLACES,
    private val reads: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {
    private val read = MutableStateFlow<RulesUiState?>(null)
    private val again = MutableStateFlow(0)

    val uiState: StateFlow<RulesUiState> = channelFlow {
        launch(reads) {
            combine(engine.catalog.filterNotNull(), again) { catalog, _ -> catalog }.collectLatest { read.value = read(it) }
        }
        read.collect { send(it ?: RulesUiState()) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), read.value ?: RulesUiState())

    /** The banner's 重試: reads the source again. */
    fun retry() {
        again.value++
    }

    private suspend fun read(catalog: Catalog): RulesUiState {
        val source = catalog.ruleSource
        if (source.error == ErrorCode.ParseFailed) return RulesUiState(loading = false, failure = RulesFailure.Parse)
        val rows = engine.allRules(source.rulesRevision).getOrElse { return failed(it) }
        val byRule = engine.usage().getOrElse { return failed(it) }.today.byRule.associate { it.id to it.up + it.down }
        val nodes = rvNodes(catalog, emptyMap(), places)
        val byId = catalog.groups.associateBy { it.id }
        val groups = catalog.groups.map { rvPolicyGroup(it, byId, nodes) }.associateBy { it.id }
        return RulesUiState(loading = false, rules = rows.map { rvPolicyRule(it, groups, byRule[it.id] ?: 0L) })
    }

    private fun failed(error: Throwable) = RulesUiState(loading = false, rules = read.value?.rules.orEmpty(), failure = RulesFailure.Read(error.errorCode()))

    companion object {
        fun factory(engine: Engine): ViewModelProvider.Factory = viewModelFactory {
            initializer { RulesViewModel(engine) }
        }
    }
}

/**
 * Every row of the rule source at [revision], page by page; a page at another revision starts the read over,
 * [RULE_READS] times at most. 全部規則 and 生效設定 both list them.
 */
internal suspend fun Engine.allRules(revision: Long): Result<List<RuleRow>> {
    repeat(RULE_READS) {
        val rows = mutableListOf<RuleRow>()
        var stale = false
        while (!stale) {
            val page = rules(rows.size, RULES_PAGE).getOrElse { return Result.failure(it) }
            stale = page.rulesRevision != revision
            if (!stale) rows += page.rows
            if (!stale && (page.rows.isEmpty() || rows.size >= page.total)) return Result.success(rows)
        }
    }
    return Result.failure(EngineException(ErrorCode.Busy))
}

/** How many rows one read asks for; the engine allows 1 to 500. */
internal const val RULES_PAGE = 50

/** How often a stale read starts over before it counts as failed. */
private const val RULE_READS = 3
