package dev.doona.android.feature.activity

import androidx.compose.runtime.Immutable
import dev.doona.android.engine.ErrorCode
import dev.doona.android.engine.Group
import dev.doona.android.engine.Member
import dev.doona.android.engine.Mode
import dev.doona.android.engine.Settings
import dev.doona.android.engine.Status
import dev.doona.android.engine.Traffic

/** Everything the 活動 screen shows, in engine terms; the screen formats and localizes it. */
@Immutable
data class ActivityUiState(
    val status: Status = Status.Stopped,
    val mode: Mode = Mode.Global,
    /** The template's first group, which carries Global traffic, and the member it uses now. */
    val outboundGroupId: String? = null,
    val outbound: String? = null,
    /**
     * The node that carries traffic, found by following each selected group down to a node; null in Direct, where
     * traffic uses no node.
     */
    val node: Member? = null,
    val downRate: Long = 0,
    val upRate: Long = 0,
    val downHistory: List<Float> = emptyList(),
    val upHistory: List<Float> = emptyList(),
    /** The last action's failure, shown until dismissed or until the next attempt succeeds. */
    val error: ErrorCode? = null,
)

sealed interface ActivityAction {
    /** Handled by the activity, which asks for VPN consent first. */
    data object Connect : ActivityAction

    data object Disconnect : ActivityAction

    data class SetMode(val mode: Mode) : ActivityAction

    /** Handled by the navigation shell. */
    data class OpenGroup(val groupId: String) : ActivityAction

    data object DismissError : ActivityAction
}

fun activityUiState(status: Status, traffic: Traffic, settings: Settings, groups: List<Group>, error: ErrorCode?): ActivityUiState {
    val outbound = groups.firstOrNull()
    return ActivityUiState(
        status = status,
        mode = settings.mode,
        outboundGroupId = outbound?.id,
        outbound = outbound?.members?.firstOrNull { it.id == outbound.selectedId }?.name,
        node = outbound?.takeIf { settings.mode == Mode.Global }?.let { currentNode(it, groups) },
        downRate = traffic.downRate,
        upRate = traffic.upRate,
        downHistory = traffic.downHistory.map(Long::toFloat),
        upHistory = traffic.upHistory.map(Long::toFloat),
        error = error,
    )
}

private fun currentNode(start: Group, groups: List<Group>): Member? {
    val byId = groups.associateBy { it.id }
    var group = start
    // Bounded by the number of groups, so a cycle in a broken template cannot hang the screen.
    repeat(groups.size) {
        val member = group.members.firstOrNull { it.id == group.selectedId } ?: return null
        group = if (member.isGroup) byId[member.id] ?: return null else return member
    }
    return null
}
