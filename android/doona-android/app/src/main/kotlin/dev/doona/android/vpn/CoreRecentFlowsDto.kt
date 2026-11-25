package dev.doona.android.vpn

import dev.doona.android.engine.RecentFlow
import kotlinx.serialization.Serializable

@Serializable
internal data class RecentFlowDto(
    val protocol: String,
    val local: String,
    val remote: String,
    val name: String?,
    val outbound: String,
    val startedMs: Long,
    val open: Boolean,
) {
    fun toRecentFlow() = RecentFlow(protocol, local, remote, name, outbound, startedMs, open)
}

@Serializable
internal data class RecentFlowsDto(val flows: List<RecentFlowDto>) {
    fun toRecentFlows() = flows.map(RecentFlowDto::toRecentFlow)
}
