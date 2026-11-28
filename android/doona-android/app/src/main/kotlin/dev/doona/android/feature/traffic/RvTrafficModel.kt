package dev.doona.android.feature.traffic

import androidx.compose.runtime.Immutable

/** The ranges under 流量詳情's chart, 1 分, 5 分, 15 分 and 1 小時, by the seconds each spans back from now. */
// honk-gap:traffic-1h
internal val RV_TRAFFIC_RANGE_SECONDS = listOf(60, 300, 900, 3_600)

/** Every range's chart has this many points, a second each over 1 分 and a minute each over 1 小時, so its line keeps one density. */
internal const val RV_TRAFFIC_POINTS = 60

/** The range index of 1 分, the live minute. */
internal const val RV_TRAFFIC_LIVE = 0

/**
 * One range's chart, as the page draws it: the [range] (an index of [RV_TRAFFIC_RANGE_SECONDS]) it was read for, each
 * way's average bytes a second per point, oldest first, the wall time at the end of the last point and how long each
 * point spans.
 */
@Immutable
internal data class RvTrafficChart(val range: Int, val down: List<Float>, val up: List<Float>, val endEpochMs: Long, val stepMs: Long) {
    fun values(upload: Boolean): List<Float> = if (upload) up else down
}

/**
 * The connection's figures for the Key stats card: each way's fastest second and average over the session, the bytes
 * each way and how many [seconds] it has run.
 */
@Immutable
internal data class RvTrafficSession(
    val peakDown: Double?,
    val peakUp: Double?,
    val averageDown: Double,
    val averageUp: Double,
    val downTotal: Long,
    val upTotal: Long,
    val seconds: Long,
)

/**
 * What 流量詳情 draws: the second's rates each way, whether they are a reading ([sampled]: the tunnel runs and this
 * session's first second is in), the last [chart] read (null before the first), and the running [session] (null while
 * no tunnel runs, so the card shows no figures).
 */
@Immutable
internal data class RvTrafficState(val downRate: Long, val upRate: Long, val sampled: Boolean, val chart: RvTrafficChart?, val session: RvTrafficSession?)
