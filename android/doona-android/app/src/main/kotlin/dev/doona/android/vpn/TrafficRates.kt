package dev.doona.android.vpn

import dev.doona.android.engine.Traffic

/** One reading of the core's byte totals, at [atMs] on a monotonic clock. */
internal data class TrafficSample(val upTotal: Long, val downTotal: Long, val atMs: Long)

private const val HISTORY = 60

/**
 * [Traffic] after [sample]. Without a [previous] sample, as after sampling resumes, [sample] only sets the baseline:
 * a rate over the pause would be an average, not the current speed.
 */
internal fun Traffic.next(previous: TrafficSample?, sample: TrafficSample): Traffic {
    if (previous == null || sample.atMs <= previous.atMs) {
        return copy(upTotal = sample.upTotal, downTotal = sample.downTotal)
    }
    val seconds = (sample.atMs - previous.atMs) / 1000.0
    val up = ((sample.upTotal - previous.upTotal).coerceAtLeast(0) / seconds).toLong()
    val down = ((sample.downTotal - previous.downTotal).coerceAtLeast(0) / seconds).toLong()
    return Traffic(
        upRate = up,
        downRate = down,
        upTotal = sample.upTotal,
        downTotal = sample.downTotal,
        upHistory = (upHistory + up).takeLast(HISTORY),
        downHistory = (downHistory + down).takeLast(HISTORY),
    )
}
