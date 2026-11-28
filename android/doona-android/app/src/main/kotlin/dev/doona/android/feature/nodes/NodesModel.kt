package dev.doona.android.feature.nodes

import androidx.compose.runtime.Immutable
import dev.doona.android.engine.ErrorCode
import dev.doona.android.engine.Member
import dev.doona.android.engine.NodeInfo
import dev.doona.android.engine.Subscription
import dev.doona.android.engine.SubscriptionFormat
import dev.doona.android.feature.model.NodePlaces
import dev.doona.android.feature.model.RV_TESTS
import dev.doona.android.feature.model.RvNode
import dev.doona.android.feature.model.RvSubscription
import dev.doona.android.feature.model.protocolName
import dev.doona.android.feature.model.rvNode
import dev.doona.android.feature.subscriptions.rvSubscription
import dev.doona.android.honk.Group
import dev.doona.android.honk.GroupSelection
import dev.doona.android.honk.HonkRules
import dev.doona.android.honk.Node
import dev.doona.android.honk.Provider
import dev.doona.android.honk.Transport
import java.time.OffsetDateTime

/**
 * What the D30 picker, 節點, a node's page and search show, mapped once per catalog revision: every node once in the
 * catalog's order ([nodes]), the subscriptions in theirs, the node the main group uses for TCP ([currentId], empty with
 * none) and for UDP ([udpId], the same node unless the choices differ), when each node's kept tests ran ([testedAt],
 * oldest first, as long as its history), and whether the main group's TCP and UDP choices differ ([split]). A static node
 * has no subscription: its [RvNode.subscriptionId] is empty, and the lists show it under 組態內.
 */
@Immutable
internal data class NodesState(
    val nodes: List<RvNode>,
    val subscriptions: List<RvSubscription>,
    val currentId: String,
    val mainGroupId: String?,
    val testedAt: Map<String, List<Long>>,
    val split: Boolean,
    val udpId: String = currentId,
) {
    /** One node by id, or null where the catalog no longer has it. */
    fun node(id: String): RvNode? = nodes.firstOrNull { it.id == id }

    /** The nodes the main group uses, one per network; one node while the networks share it, none with no main group. */
    val current: Set<String> get() = setOf(currentId, udpId) - ""

    companion object {
        val Empty = NodesState(emptyList(), emptyList(), "", null, emptyMap(), split = false)
    }
}

/**
 * What the pages read from the ViewModel: the mapped [state], a latency test running, the last failed action, Score's
 * marks (null: honk reports no Score evidence), the subscriptions being updated from a row's menu ([updating]), and now.
 */
@Immutable
internal data class NodesUi(
    val state: NodesState = NodesState.Empty,
    val loaded: Boolean = false,
    val testing: Set<String>? = null,
    val failure: NodesFailure? = null,
    val score: RvScoreMarks? = null,
    val nowEpochMs: Long = 0L,
    val updating: Set<String> = emptySet(),
)

/**
 * An action that failed, with honk's reason: a node switch, a latency test, the read of the nodes, or a row menu's update
 * or deletion of a subscription. [subject] is its display name on an update; [providerId] is the retry target.
 */
@Immutable
internal data class NodesFailure(val action: NodesAction, val code: ErrorCode, val subject: String? = null, val providerId: String? = null)

internal enum class NodesAction { Select, Test, Read, Update, Remove }

/**
 * The pages' state from honk's [nodes] (in honk's order), its [providers] and the [main] group, with each node placed by
 * [places]. honk keeps each node's last probe only, so [NodesState.testedAt] holds that probe's time when the history
 * shows it. The current nodes are what the main group's TCP and UDP selections end on, through nested groups as honk
 * resolves them; with no main group no node is the current one.
 */
internal fun nodesState(nodes: List<Node>, providers: List<Provider>, main: Group?, places: NodePlaces): NodesState {
    val byId = providers.associateBy { it.id }
    val tcp = main?.runtime?.selection?.tcp
    val udp = main?.runtime?.selection?.udp
    val tcpId = tcp?.leaf().orEmpty()
    val udpId = udp?.leaf().orEmpty()
    val rows = nodes.map { rvNode(it, byId, places) }
    return NodesState(
        nodes = rows,
        subscriptions = providers.map { rvSubscription(it, null) },
        currentId = tcpId,
        mainGroupId = main?.id,
        testedAt = nodes.zip(rows).associate { (node, row) -> node.id to if (row.history.isEmpty()) emptyList() else listOfNotNull(node.probedAt()) },
        split = tcp?.memberId != udp?.memberId || tcpId != udpId,
        udpId = udpId,
    )
}

/**
 * The group 主節點 names: the one the routing's fallback sends to, so a flow no rule matches goes where the pages say. A
 * configuration whose fallback is `direct` or `block` has none.
 */
internal fun mainGroup(groups: List<Group>, rules: HonkRules): Group? = groups.firstOrNull { it.name == rules.fallback.outbound }

/** The node a selection ends on: honk's resolved leaf, else the member itself. */
private fun GroupSelection.leaf(): String = resolvedLeafNodeId ?: memberId

/** When the probe [rvNode] shows was taken: the TCP one, else the first. */
private fun Node.probedAt(): Long? =
    (health.firstOrNull { it.transport == Transport.Tcp } ?: health.firstOrNull())?.observedAt?.let { OffsetDateTime.parse(it).toInstant().toEpochMilli() }

/** The subscription a node came from: its id, its name and its format (null for a legacy one the core has not refetched). */
internal class RvSource(val id: String, val name: String, val format: SubscriptionFormat?)

/** [member]'s subscription among [subscriptions] (by id); null for a node of no subscription (組態內). */
internal fun rvSource(member: Member, subscriptions: Map<String, Subscription>): RvSource? =
    member.subscriptionId?.let { id -> RvSource(id, subscriptions[id]?.name.orEmpty(), subscriptions[id]?.lastFetch?.format) }

/**
 * A node as the pages show it. The history is the core's last tests, oldest first; a node the core has no details for
 * shows the catalog's last probe alone (a real probe, never one made up), so a probed node never reads as untested.
 * The name stays the subscription's: only the rows draw its emoji as marks (q-emoji1). [source] is null for a node of no
 * subscription (組態內).
 */
internal fun rvNode(member: Member, info: NodeInfo?, source: RvSource?, places: NodePlaces): RvNode {
    val placed = places.of(member.name)
    val history = when {
        info != null -> info.tests.map { it.ms }.takeLast(RV_TESTS)
        member.latencyMs != null || member.failed -> listOf(member.latencyMs)
        else -> emptyList()
    }
    return RvNode(
        id = member.id,
        name = member.name,
        short = placed.short,
        place = placed.place,
        city = placed.city,
        protocol = info?.protocol?.let(::protocolName).orEmpty(),
        host = info?.server.orEmpty(),
        port = info?.port ?: 0,
        transport = info?.transport?.let(::transportName).orEmpty(),
        tls = info?.tls ?: false,
        subscription = source?.name.orEmpty(),
        subscriptionId = source?.id.orEmpty(),
        history = history,
        shareable = source?.format == SubscriptionFormat.ShareLinks,
    )
}

/** A transport as the pages name it (gRPC, WebSocket), from the core's lower-case id; an id not listed is shown as sent. */
internal fun transportName(id: String): String = TRANSPORTS[id] ?: id

private val TRANSPORTS = mapOf(
    "tcp" to "TCP",
    "ws" to "WebSocket",
    "grpc" to "gRPC",
    "h2" to "HTTP/2",
    "http" to "HTTP",
    "quic" to "QUIC",
    "httpupgrade" to "HTTPUpgrade",
    "xhttp" to "XHTTP",
)
