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
 * - [period]: 本週 or 本月 by the day, from `usage().days` and today; null for 今天.
 * - [subscriptionId]: the subscription the chip picked, null for all. It narrows the cards the catalog can split
 *   ([shownSubscriptions], [shownNodes]); traffic is counted for all subscriptions together, so the traffic cards stay
 *   at all of them and say so (unwired-rows 4.4, reduced).
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
    val period: RvStatsPeriod? = null,
    val subscriptionId: String? = null,
) {
    /** The subscriptions 訂閱流量 shows: the picked one, or all. */
    val shownSubscriptions: List<RvSubscription> get() = subscriptions.filter { subscriptionId == null || it.id == subscriptionId }

    /** The nodes 節點分布 and 延遲分布 show: the picked subscription's, or all. */
    val shownNodes: List<RvNode> get() = nodes.filter { subscriptionId == null || it.subscription == subscriptionId }
}

/**
 * 本週 or 本月 by the day: [days] from the range's first day to today, which is still filling, with no connections (the
 * app keeps only bytes for past days); [slots] days in the whole range (7, or the month's length); [now] where the
 * running total ends, in days; [before] the week before by the same point, null for a month, because the 31 days kept
 * do not reach back over the month before.
 */
@Immutable
internal class RvStatsPeriod(val days: RvDay, val slots: Int, val now: Float, val before: Long?)

/**
 * The range chip's choices, with the line a range with no bytes shows and what its change compares with (none for a
 * month). v0.1 keeps days, so a week and a month are seven or about thirty of them.
 */
internal enum class RvStatsRange(@StringRes val label: Int, @StringRes val quiet: Int, @StringRes val against: Int?) {
    Today(R.string.rv_stats_today, R.string.rv_stats_nothing_today, R.string.rv_stats_vs_yesterday),
    Week(R.string.rv_stats_week, R.string.rv_stats_nothing_week, R.string.rv_stats_vs_last_week),
    Month(R.string.rv_stats_month, R.string.rv_stats_nothing_month, null),
}

/**
 * How 連線次數 and 分流比例 look in 本週 and 本月, where the app has no daily figures for them, for the owner to compare.
 * [Today] (recommended) keeps today's figures and adds 今天 to the card's name; [NoData] shows their empty state.
 */
internal enum class RvStatsGapLook { Today, NoData }

/** What the Score card reads: honk's `scoreState`, and each Score group's name and members. */
@Immutable
internal class RvStatsScore(val state: ScoreState, val groups: List<RvStatsScoreGroup>)

/** A Score group as the card names it when there are several, and its members, to find the node each network chose. */
@Immutable
internal class RvStatsScoreGroup(val id: String, val name: String, val members: List<RvNode>)
