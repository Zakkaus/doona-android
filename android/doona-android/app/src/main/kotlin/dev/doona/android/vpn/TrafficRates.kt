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
    val totals = sample.traffic
    if (previous == null || sample.atMs <= previous.atMs) {
        return copy(upTotal = totals.upTotal, downTotal = totals.downTotal, activeFlows = totals.activeFlows, upPeak = totals.upPeak, downPeak = totals.downPeak)
    }
    val seconds = (sample.atMs - previous.atMs) / 1000.0
    val up = ((totals.upTotal - previous.traffic.upTotal).coerceAtLeast(0) / seconds).toLong()
    val down = ((totals.downTotal - previous.traffic.downTotal).coerceAtLeast(0) / seconds).toLong()
    return Traffic(
        upRate = up,
        downRate = down,
        upTotal = totals.upTotal,
        downTotal = totals.downTotal,
        upHistory = (upHistory + up).takeLast(HISTORY),
        downHistory = (downHistory + down).takeLast(HISTORY),
        activeFlows = totals.activeFlows,
        upPeak = totals.upPeak,
        downPeak = totals.downPeak,
    )
}
