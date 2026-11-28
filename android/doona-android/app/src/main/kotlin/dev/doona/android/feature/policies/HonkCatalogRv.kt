package dev.doona.android.feature.policies

import dev.doona.android.feature.model.RvGroupKind
import dev.doona.android.feature.model.RvLabel
import dev.doona.android.feature.model.RvMemberGroup
import dev.doona.android.feature.model.RvNode
import dev.doona.android.feature.model.RvPolicyGroup
import dev.doona.android.honk.Group
import dev.doona.android.honk.MemberKind
import dev.doona.android.honk.PolicyKind

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

/**
 * The member of [group] to select so that it carries [nodeId]: the node itself when it is a member, else the first member
 * group whose selection ends on it, as the pages list member groups by their leaf node.
 */
internal fun memberFor(group: Group, nodeId: String, groups: Map<String, Group>): String =
    group.members.firstOrNull { it.kind == MemberKind.Node && it.id == nodeId }?.id
        ?: group.members.firstOrNull { it.kind == MemberKind.Group && groups[it.id]?.leafNodeId() == nodeId }?.id
        ?: nodeId
