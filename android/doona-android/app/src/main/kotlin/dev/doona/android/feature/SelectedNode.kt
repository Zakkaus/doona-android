package dev.doona.android.feature

import dev.doona.android.engine.Group
import dev.doona.android.engine.Member

/**
 * The node a member leads to: the member itself when it is a node, and, for a group, the node its selection ends on.
 * Null when the chain ends in nothing. The walk is bounded by the number of groups, so a cycle in a broken template
 * cannot hang a screen. [groups] is every group by id.
 */
fun leafOf(member: Member, groups: Map<String, Group>): Member? {
    var current = member
    repeat(groups.size + 1) {
        if (!current.isGroup) return current
        val group = groups[current.id] ?: return null
        current = group.members.firstOrNull { it.id == group.selectedId } ?: return null
    }
    return null
}
