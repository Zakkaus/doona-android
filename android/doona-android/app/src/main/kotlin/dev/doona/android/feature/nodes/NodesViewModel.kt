package dev.doona.android.feature.nodes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.doona.android.engine.Engine
import dev.doona.android.engine.EngineException
import dev.doona.android.engine.ErrorCode
import dev.doona.android.engine.GroupKind
import dev.doona.android.engine.Network
import dev.doona.android.engine.NodeInfo
import dev.doona.android.engine.ScoreGroup
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The D30 picker's, 節點's, a node's page's and search's state, one instance scoped to 活動's entry and shared by the four
 * (plan 2.2). Each catalog revision reads the nodes' details (`nodes(ids)`) and maps every node once, on [work], into
 * immutable values, so nothing a page reads changes between revisions and 活動 under the picker is not composed again on
 * a tick. A pick switches the main group ([pick]); a test asks the core to test every group ([test]); a failure stays
 * until the same action next succeeds, and [retry] runs it again. [clock] is now, for the subscriptions' end dates.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NodesViewModel internal constructor(
    private val engine: Engine,
    work: CoroutineDispatcher,
    places: NodePlaces,
    private val clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {
    private val failure = MutableStateFlow<NodesFailure?>(null)
    private val running = MutableStateFlow(false)

    /** The last pick, which [retry] makes again after it failed. */
    private var lastPick: Pair<String, Network?>? = null

    /**
     * Each catalog mapped once, with every node's details read again (`nodes()`, the core's every node, grouped or not);
     * when the read fails the nodes keep the last details read.
     */
    private val mapped: Flow<NodesState?> = flow {
        var info = emptyMap<String, NodeInfo>()
        emitAll(
            engine.catalog.mapLatest { catalog ->
                catalog ?: return@mapLatest null
                engine.nodes(null).getOrNull()?.let { read -> info = read.associateBy { it.id } }
                nodesState(catalog, info, places)
            },
        )
    }.flowOn(work)

    /** Score's choices, read again with each catalog revision while the catalog has a Score group. */
    private val score: Flow<List<ScoreGroup>> = engine.catalog.mapLatest { catalog ->
        if (catalog?.groups?.any { it.kind == GroupKind.Score } == true) engine.scoreState().getOrNull()?.groups.orEmpty() else emptyList()
    }.distinctUntilChanged().flowOn(work)

    private val testing: Flow<Boolean> = combine(running, engine.groups.map { all -> all.any { it.testing } }) { mine, core -> mine || core }
        .distinctUntilChanged()

    internal val ui: StateFlow<NodesUi> = combine(mapped, score, testing, failure) { state, score, testing, failure ->
        NodesUi(state ?: NodesState.Empty, loaded = state != null, testing = testing, failure = failure, score = score, nowEpochMs = clock())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_MS), NodesUi())

    /**
     * Makes [nodeId] the main group's node: on both networks, or only on [network] where the caller's 套用到 row chose one.
     * Does nothing before the catalog is read, or when the catalog has no main group (the pages say so).
     */
    fun pick(nodeId: String, network: Network? = null) {
        val catalog = engine.catalog.value ?: return
        val main = catalog.mainGroupId.takeIf { id -> catalog.groups.any { it.id == id } } ?: return
        lastPick = nodeId to network
        viewModelScope.launch { engine.select(main, nodeId, network).report(NodesAction.Select) }
    }

    /** Tests every group's nodes; the rows show the test running until the core's results arrive. */
    fun test() {
        if (running.value) return
        running.value = true
        viewModelScope.launch {
            try {
                engine.testLatency(null).report(NodesAction.Test)
            } finally {
                running.value = false
            }
        }
    }

    /** Runs the failed action again: the last pick, or the test. */
    fun retry() {
        when (failure.value?.action) {
            NodesAction.Select -> lastPick?.let { (id, network) -> pick(id, network) }
            NodesAction.Test -> test()
            null -> Unit
        }
    }

    /** A failure stands for [action] until it next succeeds; a success clears only its own action's failure. */
    private fun Result<Unit>.report(action: NodesAction) {
        val error = exceptionOrNull()
        failure.update { last ->
            when {
                error != null -> NodesFailure(action, (error as? EngineException)?.code ?: ErrorCode.Internal)
                last?.action == action -> null
                else -> last
            }
        }
    }

    companion object {
        private const val STOP_MS = 5_000L

        internal fun factory(engine: Engine, places: NodePlaces = CoreNodePlaces): ViewModelProvider.Factory = viewModelFactory {
            initializer { NodesViewModel(engine, Dispatchers.Default, places) }
        }
    }
}
