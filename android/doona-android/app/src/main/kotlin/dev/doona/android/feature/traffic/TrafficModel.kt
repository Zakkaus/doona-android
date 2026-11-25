package dev.doona.android.feature.traffic

import dev.doona.android.engine.Status
import dev.doona.android.engine.Traffic
import dev.doona.android.engine.TrafficSeries

/**
 * One `trafficSeries` read for [range]: its [chart], null when the read failed, and for the live minute how many seconds
 * the engine's own history held when it was read ([historyAtRead]), so the read's older seconds can lead that history.
 */
internal class SeriesRead(val range: Int, val chart: RvTrafficChart?, val historyAtRead: Int)

/**
 * [series] as [range]'s chart of [RV_TRAFFIC_POINTS] points: the core sends up to 300 points, so each chart point
 * averages as many of them as fit (5 over 5 分), which keeps the window's mean; a series of no more points than that is
 * taken as it is.
 */
internal fun trafficChart(range: Int, series: TrafficSeries): RvTrafficChart {
    val per = (series.down.size / RV_TRAFFIC_POINTS).coerceAtLeast(1)
    return RvTrafficChart(range, series.down.averaged(per), series.up.averaged(per), series.endEpochMs, series.stepSeconds * per * MS_PER_SECOND)
}

/** The last whole groups of [per] points, each averaged. */
private fun List<Long>.averaged(per: Int): List<Float> = takeLast(size / per * per).chunked(per) { group -> group.average().toFloat() }

/**
 * The live minute at [nowEpochMs]: the engine's second-by-second history, led by the seconds of the [seed] read that came
 * before it ([historyAtRead] seconds of the seed are already in the history), the last [RV_TRAFFIC_POINTS] of them. Null
 * when there is neither, so the page keeps its last chart.
 */
internal fun liveMinute(seed: RvTrafficChart?, historyAtRead: Int, traffic: Traffic, nowEpochMs: Long): RvTrafficChart? {
    if (traffic.downHistory.isEmpty()) return seed
    val older = seed?.down?.dropLast(historyAtRead).orEmpty()
    val olderUp = seed?.up?.dropLast(historyAtRead).orEmpty()
    val down = (older + traffic.downHistory.map(Long::toFloat)).takeLast(RV_TRAFFIC_POINTS)
    val up = (olderUp + traffic.upHistory.map(Long::toFloat)).takeLast(RV_TRAFFIC_POINTS)
    return RvTrafficChart(RV_TRAFFIC_LIVE, down, up, nowEpochMs, MS_PER_SECOND)
}

/**
 * The running session's figures at [nowEpochMs], from the engine: the session's fastest second each way, its bytes and
 * their average over the seconds since it started (at least one, so a session that has just started has no infinite
 * rate). Null while no tunnel runs.
 */
internal fun trafficSession(status: Status, traffic: Traffic, nowEpochMs: Long): RvTrafficSession? {
    val since = (status as? Status.Running)?.sinceEpochMs ?: return null
    val seconds = ((nowEpochMs - since) / MS_PER_SECOND).coerceAtLeast(0)
    val over = seconds.coerceAtLeast(1).toDouble()
    return RvTrafficSession(
        peakDown = traffic.downPeak.toDouble(),
        peakUp = traffic.upPeak.toDouble(),
        averageDown = traffic.downTotal / over,
        averageUp = traffic.upTotal / over,
        downTotal = traffic.downTotal,
        upTotal = traffic.upTotal,
        seconds = seconds,
    )
}

/**
 * 流量詳情 at [nowEpochMs]: the second's rates, the chart of the last [read] (the live minute is built on each second's
 * traffic), or the [previous] chart when that read failed, and the session's figures. Nothing is drawn that the engine
 * did not send.
 */
internal fun trafficState(read: SeriesRead, status: Status, traffic: Traffic, previous: RvTrafficChart?, nowEpochMs: Long): RvTrafficState {
    val chart = if (read.range == RV_TRAFFIC_LIVE) liveMinute(read.chart, read.historyAtRead, traffic, nowEpochMs) else read.chart
    return RvTrafficState(traffic.downRate, traffic.upRate, chart ?: previous, trafficSession(status, traffic, nowEpochMs))
}

private const val MS_PER_SECOND = 1_000L
