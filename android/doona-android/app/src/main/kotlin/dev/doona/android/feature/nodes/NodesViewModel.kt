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
import dev.doona.android.feature.model.CoreNodePlaces
import dev.doona.android.feature.model.NodePlaces
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
 * a tick. A pick switches the main group ([pick]); a test asks the core to test every group ([test]), or one group that
 * lists one node ([testNode]); the rows' menus copy a node's share link ([copyLink]) and update or delete a subscription
 * ([update], [remove]). A failure (of those, or of reading the nodes' details) stays until the same action next
 * succeeds, and [retry] runs it again. [clock] is now, for the subscriptions' end dates.
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
    private val updating = MutableStateFlow(emptySet<String>())

    /** The node of the last one-node test (null: every group's), the last share link asked for, the last subscription acted on. */
    private var lastTest: String? = null
    private var lastShare: Share? = null
    private var lastSub: String? = null

    private class Share(val nodeId: String, val onLink: (String) -> Unit)

    /** Bumped by [retry] after a failed read of the nodes' details, to read them again. */
    private val reread = MutableStateFlow(0)

    /** The last pick that has not succeeded yet, with what to do once it does; [retry] makes it again after it failed. */
    private var lastPick: Pick? = null

    private class Pick(val nodeId: String, val network: Network?, val onPicked: () -> Unit)

    /**
     * Each catalog mapped once, with every node's details read again (`nodes()`, the core's every node, grouped or not).
     * A failed read is reported (the pages show it with 重試) and the nodes keep the last details read; a node never
     * described shows none ([dev.doona.android.feature.model.RvNode.described]).
     */
    private val mapped: Flow<NodesState?> = flow {
        var info = emptyMap<String, NodeInfo>()
        emitAll(
            combine(engine.catalog, reread) { catalog, _ -> catalog }.mapLatest { catalog ->
                catalog ?: return@mapLatest null
                engine.nodes(null).also { it.report(NodesAction.Read) }.getOrNull()?.let { read -> info = read.associateBy { it.id } }
                nodesState(catalog, info, places)
            },
        )
    }.flowOn(work)

    /** Score's choices, read again with each catalog revision while the catalog has a Score group. */
    private val score: Flow<RvScoreMarks?> = engine.catalog.mapLatest { catalog ->
        if (catalog?.groups?.any { it.kind == GroupKind.Score } == true) rvScoreMarks(engine.scoreState().getOrNull()?.groups.orEmpty()) else null
    }.distinctUntilChanged().flowOn(work)

    private val testing: Flow<Boolean> = combine(running, engine.groups.map { all -> all.any { it.testing } }) { mine, core -> mine || core }
        .distinctUntilChanged()

    internal val ui: StateFlow<NodesUi> = combine(mapped, score, testing, failure, updating) { state, score, testing, failure, updating ->
        NodesUi(state ?: NodesState.Empty, loaded = state != null, testing = testing, failure = failure, score = score, nowEpochMs = clock(), updating = updating)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_MS), NodesUi())

    /**
     * Makes [nodeId] the main group's node: on both networks, or only on [network] where the caller's 套用到 row chose one.
     * [onPicked] runs once the core has switched, never after a failure (Busy included), so a page that closes on a pick
     * stays open to show the failure and its 重試. Does nothing before the catalog is read, or when the catalog has no
     * main group (the pages say so).
     */
    fun pick(nodeId: String, network: Network? = null, onPicked: () -> Unit = {}) {
        val catalog = engine.catalog.value ?: return
        val main = catalog.mainGroupId.takeIf { id -> catalog.groups.any { it.id == id } } ?: return
        val pick = Pick(nodeId, network, onPicked).also { lastPick = it }
        viewModelScope.launch {
            val result = engine.select(main, nodeId, network)
            result.report(NodesAction.Select)
            // A pick a later one superseded does not close the page while the later one is still switching.
            if (result.isFailure || lastPick !== pick) return@launch
            // Done: the page's callback is not kept past its pick.
            lastPick = null
            onPicked()
        }
    }

    /** Tests every group's nodes; the rows show the test running until the core's results arrive. */
    fun test() {
        if (running.value) return
        lastTest = null
        running.value = true
        viewModelScope.launch {
            try {
                engine.testLatency(null).report(NodesAction.Test)
            } finally {
                running.value = false
            }
        }
    }

    /**
     * Tests [nodeId] from a row's menu with one plan: the core probes a group's members, and each plan starts a new probe
     * round for every node it reaches (a result from an older round is dropped), so this tests one group that lists the
     * node, the main group when it does, or every group when none does.
     */
    fun testNode(nodeId: String) {
        val catalog = engine.catalog.value
        val holding = catalog?.groups.orEmpty().filter { group -> group.members.any { it.id == nodeId } }
        val group = holding.firstOrNull { it.id == catalog?.mainGroupId } ?: holding.firstOrNull() ?: return test()
        lastTest = nodeId
        viewModelScope.launch { engine.testLatency(group.id).report(NodesAction.Test) }
    }

    /** Reads [nodeId]'s share link from the core and hands it to [onLink] (the page puts it on the clipboard); a node with none fails. */
    fun copyLink(nodeId: String, onLink: (String) -> Unit) {
        lastShare = Share(nodeId, onLink)
        viewModelScope.launch {
            val link = engine.nodeShareLink(nodeId)
            link.report(NodesAction.Share)
            link.getOrNull()?.let { onLink(it.uri) }
        }
    }

    /** Updates subscription [id]; its row shows it updating until the core answers ([NodesUi.updating]). */
    fun update(id: String) {
        if (id in updating.value) return
        lastSub = id
        updating.update { it + id }
        viewModelScope.launch {
            try {
                engine.updateSubscription(id).report(NodesAction.Update, subscriptionName(id))
            } finally {
                updating.update { it - id }
            }
        }
    }

    /** Deletes subscription [id], once its row's sheet has asked. */
    fun remove(id: String) {
        lastSub = id
        viewModelScope.launch { engine.removeSubscription(id).report(NodesAction.Remove) }
    }

    /** Runs the failed action again: the last pick, test, read of the nodes' details, share link, update or deletion. */
    fun retry() {
        when (failure.value?.action) {
            NodesAction.Select -> lastPick?.let { pick(it.nodeId, it.network, it.onPicked) }
            NodesAction.Test -> lastTest?.let(::testNode) ?: test()
            NodesAction.Read -> reread.update { it + 1 }
            NodesAction.Share -> lastShare?.let { copyLink(it.nodeId, it.onLink) }
            NodesAction.Update -> lastSub?.let(::update)
            NodesAction.Remove -> lastSub?.let(::remove)
            null -> Unit
        }
    }

    private fun subscriptionName(id: String): String? = engine.catalog.value?.subscriptions?.firstOrNull { it.id == id }?.name

    /** A failure stands for [action] until it next succeeds; a success clears only its own action's failure. */
    private fun Result<*>.report(action: NodesAction, subject: String? = null) {
        val error = exceptionOrNull()
        failure.update { last ->
            when {
                error != null -> NodesFailure(action, (error as? EngineException)?.code ?: ErrorCode.Internal, subject)
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
