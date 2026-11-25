package dev.doona.android.feature.policies

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.doona.android.engine.Catalog
import dev.doona.android.engine.Engine
import dev.doona.android.engine.Group
import dev.doona.android.engine.GroupKind
import dev.doona.android.engine.Network
import dev.doona.android.feature.errorCode
import dev.doona.android.feature.model.RvPolicyGroup
import dev.doona.android.feature.model.RvSubscription
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * A group's page: the group [groupId] with its nodes, the node each network uses, and the taps that choose them, as the
 * approved 套用到 sample has them ([RvApplyChoice]). Each new catalog is read on [reads] with the members' details; a
 * read that fails keeps what is shown. [clock] is now, for the subscriptions' end
 * dates; [places] places the nodes by name until 節點's mapper is wired. The 分開選擇 switch and 套用到 live here:
 * the engine only knows each network's node.
 */
internal class GroupViewModel(
    private val engine: Engine,
    private val groupId: String,
    private val clock: () -> Long = System::currentTimeMillis,
    private val places: RvNodePlaces = RV_UNKNOWN_PLACES,
    private val reads: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {
    private val local = MutableStateFlow(Local(apart = false, apply = RvApplyTo.Tcp))
    private val failure = MutableStateFlow<GroupFailure?>(null)
    private val read = MutableStateFlow<Read?>(null)

    /** The call that failed last, for the banner's 重試; null once an attempt succeeds. */
    private var retry: (() -> Unit)? = null

    /** Bumped by 重試 after a failed first read, to read the same catalog again. */
    private val again = MutableStateFlow(0)

    /** Whether the networks differed in the last catalog read; null before the first. Only the reader touches it. */
    private var differed: Boolean? = null

    val uiState: StateFlow<GroupUiState> = channelFlow {
        launch(reads) {
            combine(engine.catalog.filterNotNull().distinctUntilChanged(), again) { catalog, _ -> catalog }.collectLatest { catalog ->
                read(catalog).onSuccess(::shown).onFailure(::unread)
            }
        }
        combine(engine.ready, engine.catalog, read, local, failure, ::groupUiState).collect { send(it) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), current())

    /**
     * A tap on [memberId], a node or a member group, chooses it for the network 套用到 names while the switch is on, else
     * for both. A Score group picks each network's node itself, so a tap there chooses nothing.
     */
    fun onTap(memberId: String) {
        val catalog = engine.catalog.value ?: return
        val groups = catalog.groups.associateBy { it.id }
        val group = groups[groupId]?.takeIf { it.kind != GroupKind.Score } ?: return
        val shown = local.value
        val network = when {
            !shown.apart -> null
            shown.apply == RvApplyTo.Tcp -> Network.Tcp
            shown.apply == RvApplyTo.Udp -> Network.Udp
            else -> null
        }
        val member = memberFor(group, memberId, groups)
        perform(GroupAction.Select) { engine.select(groupId, member, network) }
    }

    /**
     * The 分開選擇 switch set to [on]. Turned off, UDP goes back to TCP's node (nboff): the networks then use one node, as
     * the switch says. A Score group picks each network's node itself, so while its two networks use different nodes the
     * switch stays on: one row would say one node serves both networks (q-nboff).
     */
    fun onApart(on: Boolean) {
        val group = engine.catalog.value?.groups?.firstOrNull { it.id == groupId }
        if (!on && group != null && group.kind == GroupKind.Score && group.networksDiffer) return
        local.update { it.copy(apart = on) }
        if (on || group == null || group.kind == GroupKind.Score || !group.networksDiffer) return
        perform(GroupAction.Apart) { engine.select(groupId, group.tcp.selectedId, Network.Udp) }
    }

    fun onApply(to: RvApplyTo) = local.update { it.copy(apply = to) }

    /** Probes this group's members. */
    fun onTest() = perform(GroupAction.Test) { engine.testLatency(groupId) }

    /**
     * Hands an engine call over at once, in tap order, and shows its failure when it ends, or clears the banner when it
     * succeeds. The engine keeps the order of what it is handed and does not drop it when this screen is left.
     */
    private fun perform(action: GroupAction, call: suspend () -> Result<Unit>) {
        viewModelScope.launch {
            val error = call().exceptionOrNull()
            retry = error?.let { { perform(action, call) } }
            failure.value = error?.let { GroupFailure(action, it.errorCode()) }
        }
    }

    /** A read: it shows, and a failed read's banner goes. */
    private fun shown(next: Read) {
        read.value = next
        failure.update { it?.takeIf { failed -> failed.action != GroupAction.Read } }
    }

    /** A read failed: with the group shown, nothing changes; with nothing shown yet, the failure shows with 重試. */
    private fun unread(error: Throwable) {
        if (read.value != null) return
        retry = { again.update { it + 1 } }
        failure.value = GroupFailure(GroupAction.Read, error.errorCode())
    }

    /** The banner's 重試: hands the failed call to the engine again, as it was first made. */
    fun retry() {
        retry?.invoke()
    }

    /**
     * The page's data from [catalog]. The switch starts as the networks are and is the user's from then on; it turns on
     * again only when the networks start to differ, as when another device chose apart.
     */
    private suspend fun read(catalog: Catalog): Result<Read> {
        val groups = catalog.groups.associateBy { it.id }
        val group = groups[groupId] ?: return Result.success(Read.NONE)
        seedApart(group)
        val own = group.members.filterNot { it.isGroup }
        val shown = (own + leafNodes(group, groups)).distinctBy { it.id }
        val infos = engine.nodes(shown.map { it.id }).getOrElse { return Result.failure(it) }.associateBy { it.id }
        val names = catalog.subscriptions.associate { it.id to it.name }
        val nodes = shown.associate { it.id to rvNode(it, infos[it.id], it.subscriptionId?.let(names::get).orEmpty(), places) }
        val mapped = rvPolicyGroup(group, groups, nodes)
        val members = mapped.members.map { it.id } + mapped.groups.map { it.id }
        val held = own.mapNotNullTo(mutableSetOf()) { it.subscriptionId }
        val tcp = mapped.selectedId
        val udp = group.udp.selectedId?.takeIf { it in members } ?: tcp
        return Result.success(
            Read(
                group = mapped,
                subscriptions = catalog.subscriptions.filter { it.id in held }.map(::rvSubscription),
                tcpId = tcp,
                udpId = udp,
                isScore = group.kind == GroupKind.Score,
                nowEpochMs = clock(),
            ),
        )
    }

    private fun seedApart(group: Group) {
        val differs = group.networksDiffer
        if (differed == null || (differs && differed == false)) local.update { it.copy(apart = differs) }
        differed = differs
    }

    /** The engine's flows are StateFlows, so the first frame already shows real values. */
    private fun current() = groupUiState(engine.ready.value, engine.catalog.value, read.value, local.value, failure.value)

    /** The 分開選擇 switch and the network 套用到 names: the page's own. */
    private data class Local(val apart: Boolean, val apply: RvApplyTo)

    /**
     * One catalog read: [group] is null when the catalog has no such group. [tcpId] and [udpId] name the member each
     * network uses, a node or a group.
     */
    private class Read(
        val group: RvPolicyGroup?,
        val subscriptions: List<RvSubscription>,
        val tcpId: String,
        val udpId: String,
        val isScore: Boolean,
        val nowEpochMs: Long,
    ) {
        companion object {
            val NONE = Read(null, emptyList(), "", "", isScore = false, nowEpochMs = 0L)
        }
    }

    private fun groupUiState(ready: Boolean, catalog: Catalog?, read: Read?, local: Local, failure: GroupFailure?) = GroupUiState(
        loading = !ready || catalog == null || (read == null && failure?.action != GroupAction.Read),
        group = read?.group,
        subscriptions = read?.subscriptions.orEmpty(),
        choice = read?.takeIf { it.group != null }?.let { RvApplyChoice(it.tcpId, it.udpId, local.apart) },
        apply = local.apply,
        isScore = read?.isScore ?: false,
        nowEpochMs = read?.nowEpochMs ?: 0L,
        testing = catalog?.groups?.firstOrNull { it.id == groupId }?.testing ?: false,
        failure = failure,
    )

    companion object {
        fun factory(engine: Engine, groupId: String): ViewModelProvider.Factory = viewModelFactory {
            initializer { GroupViewModel(engine, groupId) }
        }
    }
}
