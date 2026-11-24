package dev.doona.android.vpn

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
)

@Serializable
internal data class RecentFlowsDto(val flows: List<RecentFlowDto>) {
    companion object {
        fun parse(reply: String): RecentFlowsDto = CoreJson.result(reply, serializer())
    }
}
