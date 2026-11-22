package dev.doona.android.prototype.rv.stats

import androidx.annotation.StringRes
import dev.doona.android.R
import dev.doona.android.engine.Member
import dev.doona.android.engine.Subscription

/**
 * What the 統計 page shows, and where v0.1 gets it:
 * - [today] and [yesterdaySoFar]: a new app-side store that keeps a day in hours by sampling the snapshot's `traffic`
 *   totals (bytes since `start`, so the store adds the change between two samples). It is Kotlin only; the core does not
 *   change. Its [RvStatsDay.connections] would need a count of opened connections, which the snapshot lacks
 *   (`activeFlows` is only the current number), so it is v0.2 and comes from the sample.
 * - [subscriptions]: `usedBytes`, `totalBytes` and `expiresAtEpochMs` of the snapshot's subscriptions, real today.
 * - [nodes] and [selectedId]: the catalog's members with their latencies, and the node carrying traffic.
 * - [split]: bytes per outbound, which needs per-outbound counters in the core, an additive change for v0.2.
 */
internal class RvStatsState(
    val range: RvStatsRange,
    val today: RvStatsDay,
    val yesterdaySoFar: Long?,
    val subscriptions: List<Subscription>,
    val nodes: List<Member>,
    val selectedId: String?,
    val split: RvSplit?,
    val nowEpochMs: Long,
)

/** The range chip's choices; v0.1 keeps days, so a week and a month are seven or about thirty of them. */
internal enum class RvStatsRange(@StringRes val label: Int) {
    Today(R.string.rv_stats_today),
    Week(R.string.rv_stats_week),
    Month(R.string.rv_stats_month),
}

/** One day by the hour from midnight, oldest first; the last hour is the current one, still filling. */
internal class RvStatsDay(val down: List<Long>, val up: List<Long>, val connections: List<Int>?) {
    val downTotal = down.sum()
    val upTotal = up.sum()
    val total = downTotal + upTotal

    /** Both directions together, hour by hour. */
    val hours: List<Long> = down.zip(up) { d, u -> d + u }

    val quiet = total == 0L
}

/** Bytes that left through a proxy and directly, for the 分流比例 card. */
internal class RvSplit(val proxyBytes: Long, val directBytes: Long) {
    val proxyShare = if (proxyBytes + directBytes == 0L) 0f else proxyBytes.toFloat() / (proxyBytes + directBytes)
}
