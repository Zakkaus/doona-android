package dev.doona.android.engine

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

/** Endpoint details and the last eight latency tests, oldest first. Do not log or export the server address. */
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
