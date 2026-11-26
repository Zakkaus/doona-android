package dev.doona.android.feature.stats

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import dev.doona.android.R
import dev.doona.android.feature.model.RvDay
import dev.doona.android.feature.model.RvNode
import dev.doona.android.feature.model.RvScorePick
import dev.doona.android.feature.model.RvSplit
import dev.doona.android.feature.model.RvSubscription

/**
 * What the 統計 page shows, and where it comes from ([rvStatsState] maps honk's reads):
 * - [today]: by the hour up to the current one, with [RvDay.connections] the flows opened each hour. honk keeps no usage
 *   by the day (its traffic history spans ten minutes and its counters restart with it), so every hour is zero (q-usage).
 * - [yesterdaySoFar]: yesterday's bytes by the same time of day; null, since honk keeps none.
 * - [subscriptions]: the quota and expiry of honk's providers.
 * - [nodes] and [selectedId]: honk's nodes with their last probe, and the node the main group's TCP selection ends on.
 * - [nowHour]: the local time of day in hours (10.4 at 10:24), where today's running total ends.
 * - [split]: today's bytes that went direct against the rest; null (the card's empty state), since honk counts bytes per
 *   outbound only since it started (q-direct-proxy).
 * - [score]: honk's Score groups and what each chose per network; null when no group uses Score.
 * - [period]: 本週 or 本月 by the day; null for 今天.
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
    val shownNodes: List<RvNode> get() = nodes.filter { subscriptionId == null || it.subscriptionId == subscriptionId }
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

/**
 * What the Score card reads: the connections the Score groups started ([starts]), the trials run to compare their nodes
 * that succeeded ([trialsOk]) and failed ([trialsFailed]), and each Score group's choice per network.
 */
@Immutable
internal class RvStatsScore(val starts: Long, val trialsOk: Long, val trialsFailed: Long, val groups: List<RvStatsScoreGroup>)

/**
 * A Score group as the card names it when there are several, what it chose on each network ([tcp], [udp]), and the nodes
 * to find that choice among ([members]).
 */
@Immutable
internal class RvStatsScoreGroup(val id: String, val name: String, val members: List<RvNode>, val tcp: RvScorePick, val udp: RvScorePick)
