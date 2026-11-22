package dev.doona.android.prototype.rv

import dev.doona.android.engine.Member
import dev.doona.android.engine.Mode
import dev.doona.android.engine.Status
import dev.doona.android.feature.activity.ActivityUiState
import dev.doona.android.prototype.rv.data.RvData

/**
 * 活動 connected, in main's [ActivityUiState] built from [RvData]: running since the session's start, Global through the
 * auto group to the current node, and the session's last minute, whose last second is the current rate.
 */
internal fun rvSampleActivity(): ActivityUiState {
    val session = RvData.session
    val node = RvData.current
    return ActivityUiState(
        status = Status.Running(sinceEpochMs = session.startEpochMs),
        mode = Mode.Global,
        outboundGroupId = AUTO_GROUP_ID,
        outbound = RvData.AUTO_GROUP,
        node = Member(node.id, node.name, node.latencyMs, failed = node.latencyMs == null, subscriptionId = RvData.subscriptions.first { it.name == node.subscription }.id),
        downRate = session.minuteDown.last().toLong(),
        upRate = session.minuteUp.last().toLong(),
        downHistory = session.minuteDown,
        upHistory = session.minuteUp,
    )
}

/** The same screen disconnected: no rates and no history. */
internal fun rvIdleActivity(): ActivityUiState =
    rvSampleActivity().copy(status = Status.Stopped, downRate = 0, upRate = 0, downHistory = emptyList(), upHistory = emptyList())

/** A group's id is `g-` plus its name. */
private const val AUTO_GROUP_ID = "g-" + RvData.AUTO_GROUP
