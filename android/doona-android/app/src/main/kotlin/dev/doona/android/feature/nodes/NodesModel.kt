package dev.doona.android.feature.nodes

import androidx.compose.runtime.Immutable
import dev.doona.android.engine.Catalog
import dev.doona.android.engine.ErrorCode
import dev.doona.android.engine.Member
import dev.doona.android.engine.Network
import dev.doona.android.engine.NodeInfo
import dev.doona.android.engine.ScoreGroup
import dev.doona.android.engine.Subscription
import dev.doona.android.engine.SubscriptionFormat
import dev.doona.android.feature.model.NodePlaces
import dev.doona.android.feature.model.RV_TESTS
import dev.doona.android.feature.model.RvNode
import dev.doona.android.feature.model.RvSubFormat
import dev.doona.android.feature.model.RvSubscription
import dev.doona.android.feature.model.rvLeafMembers
import dev.doona.android.feature.model.rvSelectedLeaf
import java.net.URI

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
 * marks (null with no Score group), the subscriptions being updated from a row's menu ([updating]), and now.
 */
@Immutable
internal data class NodesUi(
    val state: NodesState = NodesState.Empty,
    val loaded: Boolean = false,
    val testing: Boolean = false,
    val failure: NodesFailure? = null,
    val score: RvScoreMarks? = null,
    val nowEpochMs: Long = 0L,
    val updating: Set<String> = emptySet(),
)

/**
 * An action that failed, with the engine's reason: a node switch, a latency test, the read of the nodes' details, or a
 * row menu's copy of a share link, update or deletion of a subscription. [subject] is the updated subscription's name.
 */
@Immutable
internal data class NodesFailure(val action: NodesAction, val code: ErrorCode, val subject: String? = null)

internal enum class NodesAction { Select, Test, Read, Share, Update, Remove }

/** The nodes the Score groups in [groups] chose, per network; null with no Score group. */
internal fun rvScoreMarks(groups: List<ScoreGroup>): RvScoreMarks? = if (groups.isEmpty()) {
    null
} else {
    RvScoreMarks(groups.mapNotNullTo(HashSet()) { it.tcp.selectedId }, groups.mapNotNullTo(HashSet()) { it.udp.selectedId })
}

/**
 * [catalog] as the pages show it, with each node's details from [infos] and its place from [places]. The nodes are every
 * group's nodes in the catalog's order, then the nodes of [infos] (the core's every node) that no group lists: a profile
 * edited by hand may leave a node out of every group. The core names a node only as a group's member, so such a node is
 * named by its server. [ungrouped] false (the read of [infos] failed, so they may be an older revision's) leaves those
 * nodes out. With no main group (a hand-edited profile may have none) no node is the current one.
 */
internal fun nodesState(catalog: Catalog, infos: Map<String, NodeInfo>, places: NodePlaces, ungrouped: Boolean = true): NodesState {
    val names = catalog.subscriptions.associate { it.id to it.name }
    val grouped = rvLeafMembers(catalog)
    val listed = grouped.mapTo(HashSet()) { it.id }
    val members = if (ungrouped) grouped + infos.values.filter { it.id !in listed }.map { Member(it.id, it.server) } else grouped
    val groups = catalog.groups.associateBy { it.id }
    val main = groups[catalog.mainGroupId]
    return NodesState(
        nodes = members.map { m -> rvNode(m, infos[m.id], m.subscriptionId?.let { id -> RvSource(id, names[id].orEmpty()) }, places) },
        subscriptions = catalog.subscriptions.map(::rvSubscription),
        currentId = main?.let { rvSelectedLeaf(it, Network.Tcp, groups) }.orEmpty(),
        mainGroupId = main?.id,
        testedAt = members.associate { m -> m.id to infos[m.id]?.tests?.takeLast(RV_TESTS)?.map { it.atEpochMs }.orEmpty() },
        split = main?.networksDiffer == true,
        udpId = main?.let { rvSelectedLeaf(it, Network.Udp, groups) }.orEmpty(),
    )
}

/** The subscription a node came from: its id and its name. */
internal class RvSource(val id: String, val name: String)

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
    )
}

internal fun rvSubscription(subscription: Subscription): RvSubscription = RvSubscription(
    id = subscription.id,
    name = subscription.name,
    nodes = subscription.nodes,
    updatedAtEpochMs = subscription.updatedAtEpochMs ?: 0L,
    usedBytes = subscription.usedBytes ?: 0L,
    totalBytes = subscription.totalBytes ?: 0L,
    expiresAtEpochMs = subscription.expiresAtEpochMs,
    host = runCatching { URI(subscription.url).host }.getOrNull().orEmpty(),
    format = when (subscription.format) {
        SubscriptionFormat.Clash -> RvSubFormat.Clash
        SubscriptionFormat.SingBox -> RvSubFormat.SingBox
        SubscriptionFormat.Sip008 -> RvSubFormat.Sip008
        SubscriptionFormat.ShareLinks, SubscriptionFormat.Base64, null -> RvSubFormat.Links
    },
    userAgent = subscription.userAgent,
)

/** A protocol as the pages name it (VLESS, Hysteria2), from the core's lower-case id; an id not listed is shown as sent. */
internal fun protocolName(id: String): String = PROTOCOLS[id] ?: id

private val PROTOCOLS = mapOf(
    "vless" to "VLESS",
    "vmess" to "VMess",
    "trojan" to "Trojan",
    "ss" to "Shadowsocks",
    "shadowsocks" to "Shadowsocks",
    "hysteria2" to "Hysteria2",
    "tuic" to "TUIC",
)

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
