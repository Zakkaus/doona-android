package dev.doona.android.vpn.honk

import dev.doona.android.engine.ErrorCode
import dev.doona.android.vpn.NativeApi
import dev.doona.android.vpn.expect
import dev.doona.android.vpn.read
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The connections honk's userspace datapath has open, through its native API, and closing them. honk lists the newest
 * first and reports bytes only for the connections it carries itself. Every call blocks in [NativeApi]: run them on
 * `Dispatchers.IO`.
 */
internal class NativeConnections(private val api: NativeApi) {
    /** Up to [LIMIT] open connections, TCP and UDP together, newest first. */
    fun list(): List<HonkConnection> {
        val page = api.read("GET", "/api/v1/connections?limit=$LIMIT&detail=full", ConnectionListDto.serializer())
        return (page.tcp.map { it.toConnection(udp = false) } + page.udp.map { it.toConnection(udp = true) }).sortedByDescending { it.startedAtEpochMs }
    }

    /** Closes [id]; one already gone fails with [ErrorCode.NotFound], one honk does not carry itself with [ErrorCode.InvalidArgument]. */
    fun close(id: String) {
        api.expect("DELETE", "/api/v1/connections/$id", conflict = ErrorCode.InvalidArgument)
    }

    /** Closes every connection honk carries itself and returns how many it closed. */
    fun closeAll(): Int = api.read("DELETE", "/api/v1/connections?all=true", BulkCloseDto.serializer()).closed

    companion object {
        /** honk's largest page (1 to 1000), shared by TCP and UDP. */
        const val LIMIT = 1_000
    }
}

/**
 * An open connection: [host] is the domain honk read for it, or the address when it had none ([hasDomain] false).
 * [outbound] is the group's name, `direct` or `block` routing chose, null when honk did not record it; [chain] the group ids from the root to the node id it ended on, empty
 * when honk did not record it. [process] is the process name honk captured, null when none.
 */
internal data class HonkConnection(
    val id: String,
    val udp: Boolean,
    val host: String,
    val port: Int,
    val hasDomain: Boolean,
    val process: String?,
    val outbound: String?,
    val chain: List<String>,
    val ruleId: String?,
    val ruleExpression: String?,
    val startedAtEpochMs: Long,
    val upBytes: Long,
    val downBytes: Long,
)

@Serializable
internal class ConnectionListDto(val tcp: List<ConnectionDto>, val udp: List<ConnectionDto>)

@Serializable
internal class ConnectionDto(
    val id: String,
    val pname: String? = null,
    val dst: String,
    val domain: String? = null,
    val outbound: String? = null,
    val chain: List<String>,
    @SerialName("rule_id") val ruleId: String? = null,
    @SerialName("rule_expression") val ruleExpression: String? = null,
    @SerialName("started_at") val startedAt: String,
    @SerialName("upload_bytes") val upload: String? = null,
    @SerialName("download_bytes") val download: String? = null,
) {
    fun toConnection(udp: Boolean): HonkConnection {
        // `1.2.3.4:443` or `[2001:db8::1]:443`: the port follows the last colon.
        val colon = dst.lastIndexOf(':')
        val address = dst.substring(0, colon).removePrefix("[").removeSuffix("]")
        return HonkConnection(
            id = id,
            udp = udp,
            host = domain ?: address,
            port = dst.substring(colon + 1).toInt(),
            hasDomain = domain != null,
            process = pname,
            outbound = outbound,
            chain = chain,
            ruleId = ruleId,
            ruleExpression = ruleExpression,
            startedAtEpochMs = epochMs(startedAt),
            upBytes = upload?.toLong() ?: 0,
            downBytes = download?.toLong() ?: 0,
        )
    }
}

@Serializable
internal class BulkCloseDto(val closed: Int)
