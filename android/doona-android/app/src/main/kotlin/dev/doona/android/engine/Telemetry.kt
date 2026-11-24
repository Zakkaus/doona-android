package dev.doona.android.engine

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Bytes since local midnight, and yesterday's combined bytes by the same time (null when not kept). */
data class TodayTraffic(val upTotal: Long = 0, val downTotal: Long = 0, val yesterdayByNow: Long? = null)

/** Average bytes per second in each point, oldest first; peaks are the fastest individual seconds in the window. */
data class TrafficSeries(
    val stepSeconds: Long,
    val endEpochMs: Long,
    val up: List<Long>,
    val down: List<Long>,
    val upPeak: Long,
    val downPeak: Long,
)

/** Endpoint details and the last eight latency tests, oldest first. Keep server addresses out of logs. */
data class NodeInfo(
    val id: String,
    val protocol: String,
    val server: String,
    val port: Int,
    val transport: String,
    val tls: Boolean,
    val tests: List<LatencyTest>,
)

/** Probe round start and measured latency in milliseconds, or null for a failed probe. */
data class LatencyTest(val atEpochMs: Long, val ms: Int?)

/** An editor input. Only the core interprets links, credentials and protocol options. Keep drafts out of logs and exports. */
sealed interface StaticNodeDraft {
    class Link(val link: String, val name: String? = null) : StaticNodeDraft

    class Manual private constructor(
        val protocol: String,
        val server: String,
        val port: Int,
        val name: String?,
        val username: String?,
        val password: String?,
        val uuid: String?,
        val cipher: String?,
        val options: Map<String, String>,
    ) : StaticNodeDraft {
        companion object {
            /** Rejects invalid ports before the draft can be passed to the engine. */
            fun create(
                protocol: String,
                server: String,
                port: Int,
                name: String? = null,
                username: String? = null,
                password: String? = null,
                uuid: String? = null,
                cipher: String? = null,
                options: Map<String, String> = emptyMap(),
            ): Result<Manual> = if (port in 1..65535) {
                Result.success(Manual(protocol, server, port, name, username, password, uuid, cipher, options))
            } else {
                Result.failure(StaticNodeDraftException(StaticNodeReason.Port))
            }
        }
    }
}

/** A field error detected before submission to the engine. */
class StaticNodeDraftException(val reason: StaticNodeReason) : Exception(reason.name)

/** The core's private editor record. Keep the original unchanged for the next update or removal. */
data class StaticNode(val id: String, val name: String, val link: String) {
    override fun toString() = "StaticNode(id=$id)"
}

/** One row in a batch or the result of an edit. Whole-call failures are [EngineException]s instead. */
sealed interface StaticNodeResult {
    data class Ready(val id: String, val name: String, val protocol: String) : StaticNodeResult
    data class Added(val id: String, val name: String, val protocol: String) : StaticNodeResult
    data class Updated(val id: String, val name: String, val protocol: String) : StaticNodeResult
    data class Duplicate(val id: String) : StaticNodeResult
    data class Invalid(val reason: StaticNodeReason) : StaticNodeResult
    data class Unsupported(val reason: StaticNodeReason) : StaticNodeResult
}

@Serializable
enum class StaticNodeReason {
    @SerialName("catalog_full")
    CatalogFull,

    @SerialName("chain")
    Chain,

    @SerialName("cipher")
    Cipher,

    @SerialName("credentials")
    Credentials,

    @SerialName("duplicate_option")
    DuplicateOption,

    @SerialName("fields")
    Fields,

    @SerialName("link")
    Link,

    @SerialName("link_length")
    LinkLength,

    @SerialName("name")
    Name,

    @SerialName("option")
    Option,

    @SerialName("password")
    Password,

    @SerialName("path")
    Path,

    @SerialName("port")
    Port,

    @SerialName("protocol")
    Protocol,

    @SerialName("server")
    Server,

    @SerialName("username")
    Username,

    @SerialName("uuid")
    Uuid,
}
