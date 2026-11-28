package dev.doona.android.feature.policies

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
import dev.doona.android.feature.model.RvPolicyGroup
import dev.doona.android.feature.model.RvSubscription
import dev.doona.android.feature.model.rvNode
import dev.doona.android.feature.nodes.ui.RvApplyTo
import dev.doona.android.feature.subscriptions.rvSubscription
import dev.doona.android.honk.Group
import dev.doona.android.honk.MemberKind
import dev.doona.android.honk.NativeApi
import dev.doona.android.honk.NativeCatalog
import dev.doona.android.honk.NativeProbes
import dev.doona.android.honk.PolicyKind
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * A group's page: the group [groupId] with its nodes, the node each network uses, and the taps that choose them, as the
 * approved 套用到 sample has them ([RvApplyChoice]). honk pushes nothing, so the group is read on [io] at start, after
 * each change made here and on 重試 ([readGroup]); a read that fails keeps what is shown and says so with 重試. A tap
 * selects through honk (a pin on an automatic group) and the 分開選擇 switch clears or sets pins; the switch and 套用到
 * live here, as honk only knows each network's member. [clock] is now, for the subscriptions' end dates; [places] places
 * the nodes by name. honk reports no Score evidence, so the page gets none.
 */
internal class GroupViewModel(
    private val api: NativeApi,
    private val groupId: String,
    private val clock: () -> Long = System::currentTimeMillis,
    private val places: NodePlaces = CoreNodePlaces,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val catalog = NativeCatalog(api)
    private val probes = NativeProbes(api)
    private val local = MutableStateFlow(Local(apart = false, apply = RvApplyTo.Tcp))
    private val failure = MutableStateFlow<GroupFailure?>(null)
    private val read = MutableStateFlow<Read?>(null)
    private val testing = MutableStateFlow(false)
    private var reading: Job? = null

    /** Keeps the changes in tap order: each waits for the one before it. */
    private val changes = Mutex()

    /** The call that failed last, for the banner's 重試; null once an attempt succeeds. */
    private var retry: (() -> Unit)? = null

    /** Whether the networks differed in the last read; null before the first. */
    private var differed: Boolean? = null

    val uiState: StateFlow<GroupUiState> = combine(read, local, failure, testing, ::groupUiState)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), groupUiState(null, local.value, null, false))

    fun onResume() {
        reading?.cancel()
        reading = viewModelScope.launch { changes.withLock { load() } }
    }

    /**
     * A tap on [memberId], a node or a member group, chooses it for the network 套用到 names while the switch is on, else
     * for both. A Score group picks each network's node itself, so a tap there chooses nothing.
     */
    fun onTap(memberId: String) {
        if (reading?.isActive == true || failure.value?.action == GroupAction.Read) return
        val shown = read.value ?: return
        val group = shown.source?.takeIf { it.policy.kind != PolicyKind.Score } ?: return
        val network = when {
            !local.value.apart -> SelectionNetwork.Both
            local.value.apply == RvApplyTo.Tcp -> SelectionNetwork.Tcp
            local.value.apply == RvApplyTo.Udp -> SelectionNetwork.Udp
            else -> SelectionNetwork.Both
        }
        val member = memberFor(group, memberId, shown.groups)
        perform(GroupAction.Select) { withContext(io) { catalog.select(groupId, member, network) } }
    }

    /**
     * The 分開選擇 switch set to [on]. Turned off while the networks use two members, UDP goes to TCP's (q-nboff), so one
     * node serves both, as the switch says. A Score group pins TCP's member on both networks instead: Score would
     * otherwise move TCP on its own and split the networks again. Turned on, a Score group with a pin goes back to
     * choosing each network's node itself; a tap there chooses nothing, so the switch is the only way back.
     */
    fun onApart(on: Boolean) {
        perform(GroupAction.Apart) {
            val group = withContext(io) { catalog.group(groupId) }
            val selection = group.runtime.selection
            val score = group.policy.kind == PolicyKind.Score
            val tcp = selection.tcp?.memberId
            val clear = on && score && (selection.tcp?.source == OVERRIDE || selection.udp?.source == OVERRIDE)
            val next = withContext(io) {
                if (clear) {
                    catalog.clearSelection(groupId, SelectionNetwork.Both)
                } else if (!on && group.networksDiffer) {
                    catalog.select(groupId, tcp ?: throw EngineException(ErrorCode.Busy), if (score) SelectionNetwork.Both else SelectionNetwork.Udp)
                }
                readGroup()
            }
            if (!on && next.source?.networksDiffer == true) throw EngineException(ErrorCode.Busy)
            shown(next)
            local.update { it.copy(apart = on) }
        }
    }

    fun onApply(to: RvApplyTo) = local.update { it.copy(apply = to) }

    /** Probes this group's members; the page shows the test running until honk has recorded the results. */
    fun onTest() {
        testing.value = true
        perform(GroupAction.Test) {
            try {
                withContext(io) { probes.groups(listOf(groupId)) }
            } finally {
                testing.value = false
            }
        }
    }

    /** The banner's 重試: runs the failed call again, as it was first made. */
    fun retry() {
        retry?.invoke()
    }

    /** Runs a change after those before it, then shows its failure or reads again if it did not already read back. */
    private fun perform(action: GroupAction, call: suspend () -> Unit) {
        viewModelScope.launch {
            changes.withLock {
                val error = attempt { call() }
                retry = error?.let { { perform(action, call) } }
                failure.value = error?.let { GroupFailure(action, it.errorCode()) }
                if (error == null && action != GroupAction.Apart) load()
            }
        }
    }

    private suspend fun load() {
        val error = attempt { shown(withContext(io) { readGroup() }) }
        if (error != null) {
            retry = ::onResume
            failure.value = GroupFailure(GroupAction.Read, error.errorCode())
        }
    }

    /**
     * The page's one read of honk: the group, the groups among its members (for the nodes they end on), every node and
     * every provider. A group honk no longer has reads as none, and the page leaves.
     */
    private fun readGroup(): Read {
        val group = groupOrNull(groupId) ?: return Read.None
        val groups = (group.members.filter { it.kind == MemberKind.Group }.mapNotNull { groupOrNull(it.id) } + group).associateBy { it.id }
        val nodes = catalog.nodes()
        val providers = catalog.providers()
        val byProvider = providers.associateBy { it.id }
        val mapped = rvPolicyGroup(group, groups, nodes.associate { it.id to rvNode(it, byProvider, places) })
        val members = mapped.members.map { it.id } + mapped.groups.map { it.id }
        val own = group.members.filter { it.kind == MemberKind.Node }.mapTo(HashSet()) { it.id }
        val held = nodes.filter { it.id in own }.mapNotNullTo(HashSet()) { it.providerId }
        return Read(
            source = group,
            groups = groups,
            group = mapped,
            subscriptions = providers.filter { it.id in held }.map { rvSubscription(it, null) },
            tcpId = mapped.selectedId,
            udpId = group.runtime.selection.udp?.memberId?.takeIf { it in members }.orEmpty(),
            isScore = group.policy.kind == PolicyKind.Score,
            nowEpochMs = clock(),
        )
    }

    private fun groupOrNull(id: String): Group? = try {
        catalog.group(id)
    } catch (missing: EngineException) {
        if (missing.code == ErrorCode.NotFound) null else throw missing
    }

    /**
     * A read: it shows, and a failed read's banner goes. The switch starts as the networks are and is the user's from
     * then on; it turns on again only when the networks start to differ, as when another device chose apart.
     */
    private fun shown(next: Read) {
        next.source?.networksDiffer?.let { differs ->
            if (differed == null || (differs && differed == false)) local.update { it.copy(apart = differs) }
            differed = differs
        }
        read.value = next
        failure.update { it?.takeIf { failed -> failed.action != GroupAction.Read } }
    }

    /** The 分開選擇 switch and the network 套用到 names: the page's own. */
    private data class Local(val apart: Boolean, val apply: RvApplyTo)

    /**
     * One read: [source] is honk's group, null when honk has no such group, and [groups] it and its member groups by id.
     * [tcpId] and [udpId] name the member each network uses, a node or a group.
     */
    private data class Read(
        val source: Group?,
        val groups: Map<String, Group>,
        val group: RvPolicyGroup?,
        val subscriptions: List<RvSubscription>,
        val tcpId: String,
        val udpId: String,
        val isScore: Boolean,
        val nowEpochMs: Long,
    ) {
        companion object {
            val None = Read(null, emptyMap(), null, emptyList(), "", "", isScore = false, nowEpochMs = 0L)
        }
    }

    /** Still loading until the first read ends; a first read that fails shows its banner instead. */
    private fun groupUiState(read: Read?, local: Local, failure: GroupFailure?, testing: Boolean) = GroupUiState(
        loading = read == null && failure?.action != GroupAction.Read,
        group = read?.group,
        subscriptions = read?.subscriptions.orEmpty(),
        choice = read?.takeIf { it.group != null }?.let { RvApplyChoice(it.tcpId, it.udpId, local.apart) },
        apply = local.apply,
        isScore = read?.isScore ?: false,
        nowEpochMs = read?.nowEpochMs ?: 0L,
        testing = testing,
        failure = failure,
    )

    companion object {
        /** A selection's source when a pin chose it. */
        private const val OVERRIDE = "override"

        fun factory(api: NativeApi, groupId: String): ViewModelProvider.Factory = viewModelFactory {
            initializer { GroupViewModel(api, groupId) }
        }
    }
}

/** Runs [call]; its failure, or null. */
private suspend fun attempt(call: suspend () -> Unit): Exception? = try {
    call()
    null
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (error: Exception) {
    error
}

/** Whether TCP and UDP use different members. */
private val Group.networksDiffer: Boolean get() = runtime.selection.tcp?.memberId != runtime.selection.udp?.memberId
