package dev.doona.android.vpn.honk

import dev.doona.android.vpn.NativeApi
import dev.doona.android.vpn.read
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.OffsetDateTime

/**
 * honk's runtime counters through its native API: the traffic it sees now, the samples it keeps of it, and the bytes per
 * outbound. honk keeps at most [HISTORY_SECONDS] of samples and starts every counter again when it restarts. Every call
 * blocks in [NativeApi]: run them on `Dispatchers.IO`.
 */
internal class NativeRuntime(private val api: NativeApi) {
    fun traffic(): RuntimeTraffic = api.read("GET", "/api/v1/runtime", RuntimeDto.serializer()).traffic.toTraffic()

    /** honk's samples of the last [seconds] (at most [HISTORY_SECONDS], all it keeps), oldest first: one a second. */
    fun history(seconds: Int = HISTORY_SECONDS): TrafficHistory =
        api.read("GET", "/api/v1/runtime/traffic/history?window_seconds=$seconds&max_points=$seconds", TrafficHistoryDto.serializer()).toHistory()

    /** The bytes each outbound carried since honk's counters started, in honk's order. */
    fun outbounds(): List<OutboundTraffic> = api.read("GET", "/api/v1/runtime/outbounds", OutboundsDto.serializer()).outbounds.map(OutboundDto::toOutbound)

    companion object {
        /** The longest window honk's traffic history serves (`resources.traffic_history.max_window_seconds`). */
        const val HISTORY_SECONDS = 600
    }
}

/**
 * The traffic honk sees now: each way's rate over its last sample and the bytes since its counters started at
 * [sinceEpochMs] (null when honk has not sampled yet), and the connections open. A figure honk does not have yet is 0.
 */
internal data class RuntimeTraffic(
    val sinceEpochMs: Long?,
    val upRate: Long,
    val downRate: Long,
    val upTotal: Long,
    val downTotal: Long,
    val connections: Int,
)

/**
 * honk's samples, oldest first, [stepMs] apart, the last taken at [endEpochMs] (null when there is none). A sample honk
 * could not rate (the first one, or one across a counter reset) is null, not a fabricated 0.
 */
internal data class TrafficHistory(val endEpochMs: Long?, val stepMs: Long, val up: List<Long?>, val down: List<Long?>)

/** An outbound's bytes since honk's counters started: [name] is a group's or node's name, or `direct` or `block`. */
internal data class OutboundTraffic(val name: String, val kind: OutboundKind, val up: Long, val down: Long)

internal enum class OutboundKind { Group, Node, Builtin }

@Serializable
internal class RuntimeDto(val traffic: TrafficSummaryDto)

/** honk sends 64-bit counts as decimal strings, each null while it has none. */
@Serializable
internal class TrafficSummaryDto(
    @SerialName("counter_since") val counterSince: String?,
    val connections: ConnectionCountsDto,
    val bytes: TrafficBytesDto,
    val rates: TrafficRatesDto?,
) {
    fun toTraffic() = RuntimeTraffic(
        sinceEpochMs = counterSince?.let(::epochMs),
        upRate = rates?.upload?.toLong() ?: 0,
        downRate = rates?.download?.toLong() ?: 0,
        upTotal = bytes.upload?.toLong() ?: 0,
        downTotal = bytes.download?.toLong() ?: 0,
        connections = connections.total ?: 0,
    )
}

@Serializable
internal class ConnectionCountsDto(val total: Int?)

@Serializable
internal class TrafficBytesDto(val upload: String?, val download: String?)

@Serializable
internal class TrafficRatesDto(
    @SerialName("upload_bytes_per_second") val upload: String?,
    @SerialName("download_bytes_per_second") val download: String?,
)

@Serializable
internal class TrafficHistoryDto(@SerialName("sampled_every_seconds") val step: Double, val samples: List<TrafficSampleDto>) {
    fun toHistory() = TrafficHistory(
        endEpochMs = samples.lastOrNull()?.sampledAt?.let(::epochMs),
        stepMs = (step * MS_PER_SECOND).toLong(),
        up = samples.map { it.upload?.toLong() },
        down = samples.map { it.download?.toLong() },
    )
}

@Serializable
internal class TrafficSampleDto(
    @SerialName("sampled_at") val sampledAt: String,
    @SerialName("upload_bytes_per_second") val upload: String?,
    @SerialName("download_bytes_per_second") val download: String?,
)

@Serializable
internal class OutboundsDto(val outbounds: List<OutboundDto>)

@Serializable
internal class OutboundDto(
    val name: String,
    val kind: String,
    @SerialName("upload_bytes") val upload: String,
    @SerialName("download_bytes") val download: String,
) {
    fun toOutbound() = OutboundTraffic(
        name = name,
        kind = checkNotNull(OUTBOUND_KINDS[kind]) { "outbound kind $kind" },
        up = upload.toLong(),
        down = download.toLong(),
    )
}

private val OUTBOUND_KINDS = mapOf("group" to OutboundKind.Group, "node" to OutboundKind.Node, "builtin" to OutboundKind.Builtin)

private const val MS_PER_SECOND = 1_000L

/** honk's RFC 3339 [timestamp] in epoch milliseconds. */
internal fun epochMs(timestamp: String): Long = OffsetDateTime.parse(timestamp).toInstant().toEpochMilli()
