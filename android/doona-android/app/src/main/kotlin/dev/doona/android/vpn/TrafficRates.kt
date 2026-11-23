package dev.doona.android.vpn

import dev.doona.android.engine.Traffic

/** One reading of the core's byte totals, at [atMs] on a monotonic clock. */
internal data class TrafficSample(val traffic: TrafficDto, val atMs: Long)

private const val HISTORY = 60

/**
 * [Traffic] after [sample]. Without a [previous] sample, as after sampling resumes, [sample] only sets the baseline:
 * a rate over the pause would be an average, not the current speed.
 */
internal fun Traffic.next(previous: TrafficSample?, sample: TrafficSample): Traffic {
    val (upTotal, downTotal, activeFlows, upPeak, downPeak) = sample.traffic
    if (previous == null || sample.atMs <= previous.atMs) {
        return copy(upTotal = upTotal, downTotal = downTotal, activeFlows = activeFlows, upPeak = upPeak, downPeak = downPeak)
    }
    val seconds = (sample.atMs - previous.atMs) / 1000.0
    val up = ((upTotal - previous.traffic.upTotal).coerceAtLeast(0) / seconds).toLong()
    val down = ((downTotal - previous.traffic.downTotal).coerceAtLeast(0) / seconds).toLong()
    return Traffic(
        upRate = up,
        downRate = down,
        upTotal = upTotal,
        downTotal = downTotal,
        upHistory = (upHistory + up).takeLast(HISTORY),
        downHistory = (downHistory + down).takeLast(HISTORY),
        activeFlows = activeFlows,
        upPeak = upPeak,
        downPeak = downPeak,
    )
}
