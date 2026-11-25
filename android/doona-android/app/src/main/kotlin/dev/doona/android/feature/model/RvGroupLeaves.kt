package dev.doona.android.feature.model

import dev.doona.android.engine.Catalog
import dev.doona.android.engine.Group
import dev.doona.android.engine.Member
import dev.doona.android.engine.Network

/**
 * Every node the catalog's groups list, once each, in the catalog's order: a nested group is a member that is a group, so
 * it is left out here and its own nodes are listed under it. The one helper for a group's nodes (the 節點 pages, the
 * policy pages, 統計).
 */
internal fun rvLeafMembers(catalog: Catalog): List<Member> =
    catalog.groups.asSequence().flatMap { it.members }.filterNot { it.isGroup }.distinctBy { it.id }.toList()

/**
 * The node a member leads to on [network]: the member itself when it is a node, and, for a group, the node its choice on
 * that network ends on, through nested groups. Null when the chain ends in nothing (no choice, a choice that is not a
 * member, a group the catalog lacks). The walk is bounded by the number of groups, so a cycle in a broken template cannot
 * hang a screen. [groups] is every group by id. The one walker for a group's node (活動, the tile, a group's page, 節點).
 */
internal fun leafOf(member: Member, groups: Map<String, Group>, network: Network = Network.Tcp): Member? {
    var current = member
    repeat(groups.size + 1) {
        if (!current.isGroup) return current
        val group = groups[current.id] ?: return null
        current = group.members.firstOrNull { it.id == group.choice(network) } ?: return null
    }
    return null
}

/** The id of the node [group] uses on [network] ([leafOf] its chosen member); null when it names none. */
internal fun rvSelectedLeaf(group: Group, network: Network, groups: Map<String, Group>): String? =
    group.members.firstOrNull { it.id == group.choice(network) }?.let { leafOf(it, groups, network) }?.id

private fun Group.choice(network: Network): String? = if (network == Network.Udp) udp.selectedId else tcp.selectedId
