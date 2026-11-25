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
 * - [nowHour]: the local time of day in hours (10.4 at 10:24), where today's running total ends; the hour buckets are
 *   local (`DayUsage`), so a zone 30 or 45 minutes off UTC moves it.
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
    val nowHour: Float,
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
 * month). The app keeps days, so 本週 is the seven days from the locale's first day and 本月 the month's 28 to 31 from
 * the 1st.
 */
internal enum class RvStatsRange(@StringRes val label: Int, @StringRes val quiet: Int, val against: RvStatsAgainst?) {
    Today(R.string.rv_stats_today, R.string.rv_stats_nothing_today, RvStatsAgainst(R.string.rv_stats_vs_yesterday, R.string.rv_a11y_stat_up, R.string.rv_a11y_stat_down)),
    Week(R.string.rv_stats_week, R.string.rv_stats_nothing_week, RvStatsAgainst(R.string.rv_stats_vs_last_week, R.string.rv_a11y_stat_week_up, R.string.rv_a11y_stat_week_down)),
    Month(R.string.rv_stats_month, R.string.rv_stats_nothing_month, null),
}

/**
 * What a range's change compares with: the [line] under the figure, and what a screen reader says for a rise ([up]) or a
 * fall ([down]), which names the same period (q-a11y-wording).
 */
@Immutable
internal class RvStatsAgainst(@StringRes val line: Int, @StringRes val up: Int, @StringRes val down: Int)

/** What the Score card reads: honk's `scoreState`, and each Score group's name and members. */
@Immutable
internal class RvStatsScore(val state: ScoreState, val groups: List<RvStatsScoreGroup>)

/** A Score group as the card names it when there are several, and its members, to find the node each network chose. */
@Immutable
internal class RvStatsScoreGroup(val id: String, val name: String, val members: List<RvNode>)
