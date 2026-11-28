package dev.doona.android.feature.nodes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.doona.android.feature.errorCode
import dev.doona.android.feature.model.CoreNodePlaces
import dev.doona.android.feature.model.NodePlaces
import dev.doona.android.honk.NativeApi
import dev.doona.android.honk.NativeCatalog
import dev.doona.android.honk.NativeProbes
import dev.doona.android.honk.NativeRouting
import dev.doona.android.honk.SelectionNetwork
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The D30 picker's, 節點's, a node's page's and search's state, one instance scoped to 活動's entry and shared by the four
 * (plan 2.2). honk pushes nothing, so the nodes, providers and groups are read on [io] at start, after each change made
 * here and on 重試, and mapped once into immutable values. A pick selects the node in the main group ([pick], see
 * [mainGroup]); a test probes every group's members ([test]) or one node ([testNode]); the rows' menus refresh or delete a
 * provider ([update], [remove]). A failure stays until the same action next succeeds, and [retry] runs it again. [clock]
 * is now, for the subscriptions' end dates. honk has no share links and no Score evidence, so the pages get neither.
 */
class NodesViewModel internal constructor(
    private val api: NativeApi,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val places: NodePlaces = CoreNodePlaces,
    private val clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {
    private val catalog = NativeCatalog(api)
    private val probes = NativeProbes(api)
    private val read = MutableStateFlow<Read?>(null)
    private val failure = MutableStateFlow<NodesFailure?>(null)
    private val running = MutableStateFlow(false)
    private val updating = MutableStateFlow(emptySet<String>())
    private var reading: Job? = null

    /** The node of the last one-node test (null: every group's), the last subscription acted on. */
    private var lastTest: String? = null
    private var lastSub: String? = null

    /** The last pick that has not succeeded yet, with what to do once it does; [retry] makes it again after it failed. */
    private var lastPick: Pick? = null

    private class Pick(val nodeId: String, val network: SelectionNetwork, val onPicked: () -> Unit)

    /** One read: the pages' state and every group's id, for [test]. */
    private class Read(val state: NodesState, val groupIds: List<String>)

    internal val ui: StateFlow<NodesUi> = combine(read, running, failure, updating) { read, testing, failure, updating ->
        NodesUi(
            read?.state ?: NodesState.Empty,
            loaded = read != null,
            testing = testing,
            failure = failure,
            nowEpochMs = clock(),
            updating = updating,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_MS), NodesUi())

    init {
        load()
    }

    /**
     * Reads everything again. A failed read keeps what was shown under its banner; a first read that fails shows no node,
     * so the pages leave their skeletons.
     */
    private fun load() {
        reading?.cancel()
        reading = viewModelScope.launch {
            var fresh: Read? = null
            attempt(NodesAction.Read) { fresh = withContext(io) { readAll() } }
            read.value = fresh ?: read.value ?: Read(NodesState.Empty, emptyList())
        }
    }

    private fun readAll(): Read {
        val groups = catalog.groups()
        val state = nodesState(catalog.nodes(), catalog.providers(), mainGroup(groups, NativeRouting(api).rules()), places)
        return Read(state, groups.map { it.id })
    }

    /**
     * Makes [nodeId] the main group's node on [network] (both, unless the caller's 套用到 row chose one). [onPicked] runs
     * once honk has switched, never after a failure (Busy included), so a page that closes on a pick stays open to show
     * the failure and its 重試. Does nothing before the first read, or with no main group (the pages say so).
     */
    internal fun pick(nodeId: String, network: SelectionNetwork = SelectionNetwork.Both, onPicked: () -> Unit = {}) {
        val main = read.value?.state?.mainGroupId ?: return
        val pick = Pick(nodeId, network, onPicked).also { lastPick = it }
        viewModelScope.launch {
            val done = attempt(NodesAction.Select) { withContext(io) { catalog.select(main, nodeId, network) } }
            // A pick a later one superseded does not close the page while the later one is still switching.
            if (!done || lastPick !== pick) return@launch
            // Done: the page's callback is not kept past its pick.
            lastPick = null
            load()
            onPicked()
        }
    }

    /** Probes every group's members; the rows show the test running until honk has recorded the results. */
    fun test() = probe(null)

    /** Probes [nodeId] alone (a row's menu, the node's page). */
    fun testNode(nodeId: String) = probe(nodeId)

    private fun probe(nodeId: String?) {
        if (running.value) return
        lastTest = nodeId
        running.value = true
        val groupIds = read.value?.groupIds.orEmpty()
        viewModelScope.launch {
            try {
                val done = attempt(NodesAction.Test) {
                    withContext(io) {
                        if (nodeId != null) probes.node(nodeId) else probes.groups(groupIds)
                    }
                }
                if (done) load()
            } finally {
                running.value = false
            }
        }
    }

    /** Refreshes provider [id]; its row shows it updating until honk has fetched it ([NodesUi.updating]). */
    fun update(id: String) {
        if (id in updating.value) return
        lastSub = id
        updating.update { it + id }
        viewModelScope.launch {
            try {
                if (attempt(NodesAction.Update, subscriptionName(id)) { withContext(io) { catalog.refreshProvider(id) } }) load()
            } finally {
                updating.update { it - id }
            }
        }
    }

    /** Deletes provider [id], once its row's sheet has asked. */
    fun remove(id: String) {
        lastSub = id
        viewModelScope.launch {
            if (attempt(NodesAction.Remove) { withContext(io) { catalog.removeProvider(id) } }) load()
        }
    }

    /** Runs the failed action again: the last pick, test, read, update or deletion. */
    fun retry() {
        when (failure.value?.action) {
            NodesAction.Select -> lastPick?.let { pick(it.nodeId, it.network, it.onPicked) }
            NodesAction.Test -> probe(lastTest)
            NodesAction.Read -> load()
            NodesAction.Update -> lastSub?.let(::update)
            NodesAction.Remove -> lastSub?.let(::remove)
            null -> Unit
        }
    }

    private fun subscriptionName(id: String): String? = read.value?.state?.subscriptions?.firstOrNull { it.id == id }?.name

    /** Runs [call]: a failure stands for [action] until it next succeeds; a success clears only its own action's failure. */
    private suspend fun attempt(action: NodesAction, subject: String? = null, call: suspend () -> Unit): Boolean {
        val error = try {
            call()
            null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            error
        }
        failure.update { last ->
            when {
                error != null -> NodesFailure(action, error.errorCode(), subject)
                last?.action == action -> null
                else -> last
            }
        }
        return error == null
    }

    companion object {
        private const val STOP_MS = 5_000L

        internal fun factory(api: NativeApi): ViewModelProvider.Factory = viewModelFactory {
            initializer { NodesViewModel(api) }
        }
    }
}
