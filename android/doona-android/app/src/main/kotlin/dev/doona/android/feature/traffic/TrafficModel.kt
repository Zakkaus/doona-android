package dev.doona.android.feature.traffic

import dev.doona.android.vpn.honk.RuntimeTraffic
import dev.doona.android.vpn.honk.TrafficHistory

/** One load of honk's traffic now and of the samples it keeps; each null when its read failed. */
internal class TrafficRead(val traffic: RuntimeTraffic?, val history: TrafficHistory?)

/**
 * [range]'s chart of [RV_TRAFFIC_POINTS] points from honk's samples: the samples of the range's last seconds, each chart
 * point averaging as many of them as fit (10 over 10 分), which keeps the window's mean. honk keeps 600 s, so a longer
 * range shows those. Null with no sample. A sample honk could not rate counts as 0, as the chart draws no gaps.
 */
internal fun trafficChart(range: Int, history: TrafficHistory): RvTrafficChart? {
    val end = history.endEpochMs ?: return null
    val count = (RV_TRAFFIC_RANGE_SECONDS[range] * MS_PER_SECOND / history.stepMs).toInt().coerceAtLeast(1)
    val down = history.down.takeLast(count)
    val up = history.up.takeLast(count)
    val per = (down.size / RV_TRAFFIC_POINTS).coerceAtLeast(1)
    return RvTrafficChart(range, down.averaged(per), up.averaged(per), end, history.stepMs * per)
}

/** The last whole groups of [per] samples, each averaged. */
private fun List<Long?>.averaged(per: Int): List<Float> = takeLast(size / per * per).chunked(per) { group -> group.map { it ?: 0L }.average().toFloat() }

/**
 * The session's figures at [nowEpochMs]: each way's fastest sample honk keeps, the bytes since its counters started and
 * their average over the seconds since then (at least one, so a session that has just started has no infinite rate).
 * Null before honk counts.
 */
internal fun trafficSession(traffic: RuntimeTraffic, history: TrafficHistory?, nowEpochMs: Long): RvTrafficSession? {
    val since = traffic.sinceEpochMs ?: return null
    val seconds = ((nowEpochMs - since) / MS_PER_SECOND).coerceAtLeast(0)
    val over = seconds.coerceAtLeast(1).toDouble()
    return RvTrafficSession(
        peakDown = history?.down?.maxOfOrNull { it ?: 0L }?.toDouble() ?: 0.0,
        peakUp = history?.up?.maxOfOrNull { it ?: 0L }?.toDouble() ?: 0.0,
        averageDown = traffic.downTotal / over,
        averageUp = traffic.upTotal / over,
        downTotal = traffic.downTotal,
        upTotal = traffic.upTotal,
        seconds = seconds,
    )
}

/**
 * 流量詳情 at [nowEpochMs]: the rates, whether they are a reading (honk answered and has sampled), [range]'s chart from
 * the [read], or the [previous] chart when its samples could not be read, and the session's figures. Nothing is drawn
 * that honk did not send.
 */
internal fun trafficState(range: Int, read: TrafficRead, previous: RvTrafficChart?, nowEpochMs: Long): RvTrafficState {
    val traffic = read.traffic
    val chart = read.history?.let { trafficChart(range, it) } ?: previous
    return RvTrafficState(traffic?.downRate ?: 0, traffic?.upRate ?: 0, traffic?.sinceEpochMs != null, chart, traffic?.let { trafficSession(it, read.history, nowEpochMs) })
}

private const val MS_PER_SECOND = 1_000L
