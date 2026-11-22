package dev.doona.android.feature.policies

import androidx.compose.runtime.Immutable
import dev.doona.android.engine.ErrorCode
import dev.doona.android.engine.Group
import dev.doona.android.engine.Member

/** A member of a group with the node it leads to ([node]: itself for a node, where its selection ends for a group). */
@Immutable
data class MemberItem(val member: Member, val node: Member?)

/** One group's page: the group, what it uses now, and its members in display order. */
@Immutable
data class GroupDetail(val group: Group, val current: Member?, val currentNode: Member?, val members: List<MemberItem>) {
    val subGroups: List<Member> get() = members.filter { it.member.isGroup }.map { it.member }

    /** An automatic group the user has overridden: the one case that offers a way back to the group's own choice. */
    val canReturnToAutomatic: Boolean get() = !group.kind.manual && group.pinned
}

/** What a group's page shows, in engine terms; the screen formats and localizes it. */
@Immutable
data class GroupUiState(
    /** False until the engine has loaded its groups, so a group not found yet is not taken for a removed one. */
    val ready: Boolean = false,
    /** Null when the engine has no group with this id. */
    val detail: GroupDetail? = null,
    /** The last action's failure, shown until dismissed or until the next attempt succeeds. */
    val error: ErrorCode? = null,
)

/** What [GroupViewModel] handles; opening a nested group is navigation, a callback of the screen. */
sealed interface GroupAction {
    /** Pins the member: the group carries traffic through it until the user picks another or hands the group back. */
    data class Select(val memberId: String) : GroupAction

    data object ReturnToAutomatic : GroupAction

    /** Probes this group's members. */
    data object Test : GroupAction

    data object DismissError : GroupAction
}

fun groupUiState(ready: Boolean, groups: List<Group>, groupId: String, error: ErrorCode?): GroupUiState {
    val byId = groups.associateBy { it.id }
    val group = byId[groupId] ?: return GroupUiState(ready, null, error)
    val current = group.members.firstOrNull { it.id == group.selectedId }
    val items = group.members.map { MemberItem(it, leafOf(it, byId)) }
    val (subGroups, nodes) = items.partition { it.member.isGroup }
    // Groups keep the template's order, which puts Auto and the regions first; nodes go fastest first, untested and failed last.
    val ordered = subGroups + nodes.sortedBy { it.node?.takeUnless { n -> n.failed }?.latencyMs ?: Int.MAX_VALUE }
    return GroupUiState(ready, GroupDetail(group, current, current?.let { leafOf(it, byId) }, ordered), error)
}
