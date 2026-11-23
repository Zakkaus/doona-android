package dev.doona.android.feature.activity

import androidx.compose.runtime.Immutable
import dev.doona.android.engine.ErrorCode
import dev.doona.android.engine.Group
import dev.doona.android.engine.Member
import dev.doona.android.engine.Mode
import dev.doona.android.engine.Settings
import dev.doona.android.engine.Status
import dev.doona.android.engine.Traffic
import dev.doona.android.feature.leafOf

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
     * traffic uses no node. In Rule it is the node of the flows no rule matched.
     */
    val node: Member? = null,
    val downRate: Long = 0,
    val upRate: Long = 0,
    val downHistory: List<Float> = emptyList(),
    val upHistory: List<Float> = emptyList(),
    /** The last action's failure, shown until dismissed or until the next attempt succeeds. */
    val error: ErrorCode? = null,
    /**
     * Whether a subscription exists to start from. Without one the core has no node, so the screen offers no way to start:
     * it shows the status, and the way to stop while the tunnel is up.
     */
    val hasSubscriptions: Boolean = true,
)

/** What [ActivityViewModel] handles; connecting and navigation are separate callbacks of the screen. */
sealed interface ActivityAction {
    data object Disconnect : ActivityAction

    data class SetMode(val mode: Mode) : ActivityAction

    data object DismissError : ActivityAction
}

fun activityUiState(
    status: Status,
    traffic: Traffic,
    settings: Settings,
    groups: List<Group>,
    error: ErrorCode?,
    hasSubscriptions: Boolean = true,
): ActivityUiState {
    val outbound = groups.firstOrNull()
    return ActivityUiState(
        status = status,
        mode = settings.mode,
        outboundGroupId = outbound?.id,
        outbound = outbound?.members?.firstOrNull { it.id == outbound.selectedId }?.name,
        node = outbound?.takeIf { settings.mode != Mode.Direct }?.let { currentNode(it, groups) },
        downRate = traffic.downRate,
        upRate = traffic.upRate,
        downHistory = traffic.downHistory.map(Long::toFloat),
        upHistory = traffic.upHistory.map(Long::toFloat),
        error = error,
        hasSubscriptions = hasSubscriptions,
    )
}

private fun currentNode(start: Group, groups: List<Group>): Member? {
    val selected = start.members.firstOrNull { it.id == start.selectedId } ?: return null
    return leafOf(selected, groups.associateBy { it.id })
}
