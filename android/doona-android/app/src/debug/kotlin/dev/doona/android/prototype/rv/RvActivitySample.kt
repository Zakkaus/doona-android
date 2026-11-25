package dev.doona.android.prototype.rv

import dev.doona.android.engine.Member
import dev.doona.android.engine.Mode
import dev.doona.android.engine.Status
import dev.doona.android.feature.activity.ActivityUiState
import dev.doona.android.feature.model.RvNode
import dev.doona.android.prototype.rv.data.RvData

/**
 * 活動 connected, in main's [ActivityUiState] built from [RvData]: running since the session's start, Global through the
 * auto group to the [nodeId] node of [nodes], and the session's last minute, whose last second is the current rate; the
 * live sample moves that minute on by [second]s.
 */
internal fun rvSampleActivity(nodeId: String = RvData.CURRENT_ID, nodes: List<RvNode> = RvData.nodes, second: Int = 0): ActivityUiState {
    val session = RvData.session
    val node = nodes.first { it.id == nodeId }
    val down = session.minuteDown.movedOn(second)
    val up = session.minuteUp.movedOn(second)
    return ActivityUiState(
        status = Status.Running(sinceEpochMs = session.startEpochMs),
        mode = Mode.Global,
        outboundGroupId = AUTO_GROUP_ID,
        outbound = RvData.AUTO_GROUP,
        node = Member(node.id, node.name, node.latencyMs, failed = node.latencyMs == null, subscriptionId = RvData.subscriptions.first { it.name == node.subscription }.id),
        downRate = down.last().toLong(),
        upRate = up.last().toLong(),
        downHistory = down,
        upHistory = up,
    )
}

/** The minute [seconds] later, as the sample replays it: the window moved on, its first samples coming round again. */
private fun List<Float>.movedOn(seconds: Int): List<Float> {
    val by = seconds.mod(size)
    return if (by == 0) this else drop(by) + take(by)
}

/** The same screen disconnected: no rates and no history. */
internal fun rvIdleActivity(): ActivityUiState =
    rvSampleActivity().copy(status = Status.Stopped, downRate = 0, upRate = 0, downHistory = emptyList(), upHistory = emptyList())

/** A group's id is `g-` plus its name. */
private const val AUTO_GROUP_ID = "g-" + RvData.AUTO_GROUP
