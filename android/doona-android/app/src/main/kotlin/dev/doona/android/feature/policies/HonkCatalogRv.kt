package dev.doona.android.feature.policies

import dev.doona.android.feature.model.NodePlaces
import dev.doona.android.feature.model.RV_INLINE
import dev.doona.android.feature.model.RvGroupKind
import dev.doona.android.feature.model.RvLabel
import dev.doona.android.feature.model.RvMemberGroup
import dev.doona.android.feature.model.RvNode
import dev.doona.android.feature.model.RvPolicyGroup
import dev.doona.android.feature.model.protocolName
import dev.doona.android.vpn.honk.Group
import dev.doona.android.vpn.honk.HealthState
import dev.doona.android.vpn.honk.MemberKind
import dev.doona.android.vpn.honk.Node
import dev.doona.android.vpn.honk.PolicyKind
import dev.doona.android.vpn.honk.Provider
import dev.doona.android.vpn.honk.Transport

/**
 * A honk node as the pages draw it. honk keeps one health observation per network, so the history is the latest TCP one
 * alone: its latency when healthy, a timeout when unavailable, nothing before a probe. honk's node carries no server,
 * port, transport or TLS, so the node reads as not described and offers no share link. [providers] is every provider by id.
 */
internal fun rvNode(node: Node, providers: Map<String, Provider>, places: NodePlaces): RvNode {
    val placed = places.of(node.name)
    val tcp = node.health.firstOrNull { it.transport == Transport.Tcp }
    val history = when (tcp?.state) {
        HealthState.Healthy -> listOf(tcp.latencyMs?.toInt())
        HealthState.Unavailable -> listOf(null)
        HealthState.Unknown, null -> emptyList()
    }
    return RvNode(
        id = node.id,
        name = node.name,
        short = placed.short,
        place = placed.place,
        city = placed.city,
        protocol = node.protocol?.let(::protocolName).orEmpty(),
        host = "",
        port = 0,
        transport = "",
        tls = false,
        subscription = node.providerId?.let { providers[it]?.name }.orEmpty(),
        subscriptionId = node.providerId ?: RV_INLINE,
        history = history,
        shareable = false,
    )
}

internal fun rvGroupKind(kind: PolicyKind): RvGroupKind = when (kind) {
    PolicyKind.Selector, PolicyKind.Fixed -> RvGroupKind.Select
    PolicyKind.UrlTest, PolicyKind.LoadBalance, PolicyKind.Random -> RvGroupKind.Auto
    PolicyKind.Fallback -> RvGroupKind.Fallback
    PolicyKind.Score -> RvGroupKind.Score
}

/**
 * A honk group as the pages draw it: its node members, then its member groups, each with the node honk resolves its TCP
 * selection to. It uses the member its TCP selection names, else its first member. [groups] is every group by id and
 * [nodes] every node by id.
 */
internal fun rvPolicyGroup(group: Group, groups: Map<String, Group>, nodes: Map<String, RvNode>): RvPolicyGroup {
    val members = group.members.filter { it.kind == MemberKind.Node }.mapNotNull { nodes[it.id] }.distinctBy { it.id }
    val nested = group.members.filter { it.kind == MemberKind.Group }.distinctBy { it.id }.mapNotNull { member ->
        groups[member.id]?.let { RvMemberGroup(it.id, RvLabel.Text(it.name), rvGroupKind(it.policy.kind), it.leafNodeId()?.let(nodes::get)) }
    }
    val ids = members.map { it.id } + nested.map { it.id }
    val selected = group.runtime.selection.tcp?.memberId?.takeIf { it in ids } ?: ids.firstOrNull().orEmpty()
    return RvPolicyGroup(group.id, RvLabel.Text(group.name), rvGroupKind(group.policy.kind), members, selected, nested)
}

/** The node this group's TCP selection ends on, through any nested groups, as honk resolves it; null before it chose one. */
internal fun Group.leafNodeId(): String? = runtime.selection.tcp?.resolvedLeafNodeId
