package dev.doona.android.feature.stats

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import dev.doona.android.R
import dev.doona.android.engine.ScoreState
import dev.doona.android.feature.model.RvDay
import dev.doona.android.feature.model.RvNode
import dev.doona.android.feature.model.RvSplit
import dev.doona.android.feature.model.RvSubscription

/**
 * What the 統計 page shows, and where it comes from ([rvStatsState] maps the engine's reads):
 * - [today]: `usage().today` by the hour up to the current one; its [RvDay.connections] are the flows opened each hour.
 * - [yesterdaySoFar]: yesterday's bytes by the same time of day (`today.yesterdayByNow`), null when not kept.
 * - [subscriptions]: `usedBytes`, `totalBytes` and `expiresAtEpochMs` of the engine's subscriptions.
 * - [nodes] and [selectedId]: the catalog's nodes with their last latency, and the main group's node.
 * - [split]: today's bytes that went direct (`outbounds` id `direct`) against the rest; null before the first byte.
 * - [score]: honk's `scoreState` with the Score groups' names and members; null when no group uses Score.
 */
@Immutable
internal class RvStatsState(
    val range: RvStatsRange,
    val today: RvDay,
    val yesterdaySoFar: Long?,
    val subscriptions: List<RvSubscription>,
    val nodes: List<RvNode>,
    val selectedId: String?,
    val split: RvSplit?,
    val nowEpochMs: Long,
    val score: RvStatsScore? = null,
)

/** The range chip's choices; v0.1 keeps days, so a week and a month are seven or about thirty of them. */
internal enum class RvStatsRange(@StringRes val label: Int) {
    Today(R.string.rv_stats_today),
    Week(R.string.rv_stats_week),
    Month(R.string.rv_stats_month),
}

/** What the Score card reads: honk's `scoreState`, and each Score group's name and members. */
@Immutable
internal class RvStatsScore(val state: ScoreState, val groups: List<RvStatsScoreGroup>)

/** A Score group as the card names it when there are several, and its members, to find the node each network chose. */
@Immutable
internal class RvStatsScoreGroup(val id: String, val name: String, val members: List<RvNode>)
