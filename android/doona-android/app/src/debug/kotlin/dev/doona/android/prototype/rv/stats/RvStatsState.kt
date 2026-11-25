package dev.doona.android.prototype.rv.stats

import androidx.annotation.StringRes
import dev.doona.android.R
import dev.doona.android.feature.model.RvDay
import dev.doona.android.feature.model.RvNode
import dev.doona.android.feature.model.RvSplit
import dev.doona.android.feature.model.RvSubscription

/**
 * What the 統計 page shows, and where v0.1 gets it:
 * - [today] and [yesterdaySoFar]: a new app-side store that keeps a day in hours by sampling the snapshot's `traffic`
 *   totals (bytes since `start`, so the store adds the change between two samples). It is Kotlin only; the core does not
 *   change. Its [RvDay.connections] would need a count of opened connections, which the snapshot lacks
 *   (`activeFlows` is only the current number), so it is v0.2 and comes from the sample.
 * - [subscriptions]: `usedBytes`, `totalBytes` and `expiresAtEpochMs` of the snapshot's subscriptions, real today.
 * - [nodes] and [selectedId]: the catalog's members with their latencies, and the node carrying traffic.
 * - [split]: bytes per outbound, which needs per-outbound counters in the core, an additive change for v0.2.
 */
internal class RvStatsState(
    val range: RvStatsRange,
    val today: RvDay,
    val yesterdaySoFar: Long?,
    val subscriptions: List<RvSubscription>,
    val nodes: List<RvNode>,
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
