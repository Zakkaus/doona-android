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
 * The node [group] uses on [network]: its choice when that is a node, a nested group's own choice on that network when it
 * is a group, and so on down. Null with no choice, or for nesting deeper than [MAX_DEPTH] (a cycle the core should never
 * send). [groups] is every group of the catalog by id.
 */
internal fun rvSelectedLeaf(group: Group, network: Network, groups: Map<String, Group>): String? {
    var id = group.choice(network)
    // The choice itself, then one step per nested group followed.
    repeat(MAX_DEPTH + 1) {
        val nested = groups[id ?: return null] ?: return id
        id = nested.choice(network)
    }
    return null
}

private fun Group.choice(network: Network): String? = if (network == Network.Udp) udp.selectedId else tcp.selectedId

/** How many nested groups a selection may pass through before it is taken for a cycle and names no node. */
private const val MAX_DEPTH = 8
