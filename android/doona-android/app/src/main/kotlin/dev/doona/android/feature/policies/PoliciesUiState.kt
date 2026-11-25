package dev.doona.android.feature.policies

import androidx.compose.runtime.Immutable
import dev.doona.android.engine.ErrorCode
import dev.doona.android.engine.Group
import dev.doona.android.engine.Member
import dev.doona.android.feature.model.leafOf

/** Which groups the list shows: all, those the user picks a member of, or those that pick their own. */
enum class GroupFilter {
    All,
    Manual,
    Auto,
    ;

    fun admits(group: Group): Boolean = when (this) {
        All -> true
        Manual -> group.kind.manual
        Auto -> !group.kind.manual
    }
}

/**
 * A group with the member it uses now ([current]) and the node under that member ([node]): the same member when it is a
 * node, and where its selection ends when it is a group. The latency a card shows is the node's.
 */
@Immutable
data class GroupItem(val group: Group, val current: Member?, val node: Member?)

/** Everything the 策略 list shows, in engine terms; the screen formats and localizes it. */
@Immutable
data class PoliciesUiState(
    /** False until the engine has loaded its groups, so an empty list is not shown as "no groups" too early. */
    val ready: Boolean = false,
    val items: List<GroupItem> = emptyList(),
    val filter: GroupFilter = GroupFilter.All,
    /** The last action's failure, shown until dismissed or until the next attempt succeeds. */
    val error: ErrorCode? = null,
) {
    val manual: Int get() = items.count { it.group.kind.manual }
    val visible: List<GroupItem> get() = items.filter { filter.admits(it.group) }
}

/** What [PoliciesViewModel] handles; opening a group or the subscriptions page is navigation, a callback of the screen. */
sealed interface PoliciesAction {
    /** Probes the members of every group. */
    data object TestAll : PoliciesAction

    data class SetFilter(val filter: GroupFilter) : PoliciesAction

    data object DismissError : PoliciesAction
}

fun policiesUiState(ready: Boolean, groups: List<Group>, filter: GroupFilter, error: ErrorCode?): PoliciesUiState {
    val byId = groups.associateBy { it.id }
    val items = groups.map { group ->
        val current = group.members.firstOrNull { it.id == group.selectedId }
        GroupItem(group, current, current?.let { leafOf(it, byId) })
    }
    return PoliciesUiState(ready, items, filter, error)
}
