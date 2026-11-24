package dev.doona.android.prototype.rv.stats

import dev.doona.android.engine.ScoreCounters
import dev.doona.android.engine.ScoreState
import dev.doona.android.prototype.rv.data.RvData
import dev.doona.android.prototype.rv.policy.RvScoreSample

/** The 統計 page as [RvData] has it at 10:24: today so far, both subscriptions, every node, the current one selected. */
internal fun rvStatsSample() = RvStatsState(
    range = RvStatsRange.Today,
    today = RvData.today,
    yesterdaySoFar = RvData.YESTERDAY_BY_NOW,
    subscriptions = RvData.subscriptions,
    nodes = RvData.nodes,
    selectedId = RvData.CURRENT_ID,
    split = RvData.split,
    nowEpochMs = RvData.nowEpochMs,
)

/** The same page before the first byte of the day, with no subscription quota and no test run yet. */
internal fun rvStatsQuiet() = RvStatsState(
    range = RvStatsRange.Today,
    today = RvData.quietDay,
    yesterdaySoFar = null,
    subscriptions = emptyList(),
    nodes = RvData.nodes.map { it.untested() },
    selectedId = RvData.CURRENT_ID,
    split = null,
    nowEpochMs = RvData.nowEpochMs,
)

/**
 * The Score card's sample: the 香港 region of the group page's samples, TCP verified on its fastest node as there, UDP on
 * the second but still waiting for comparable traffic, with the counters honk would have after a morning's use.
 */
internal fun rvStatsScore(): RvStatsScore {
    val chosen = RvScoreSample.chosen
    val group = chosen.copy(
        tcp = chosen.tcp.copy(counters = ScoreCounters(BUSINESS_TCP, TRIALS_OK_TCP, TRIALS_FAILED_TCP, 1)),
        udp = chosen.udp.copy(state = "provisional", waitReason = "comparableTraffic", counters = ScoreCounters(BUSINESS_UDP, TRIALS_OK_UDP, 0, 0)),
    )
    return RvStatsScore(ScoreState(BUSINESS_ROOT, listOf(group)), listOf(RvScoreSample.group))
}

private const val BUSINESS_TCP = 128L
private const val BUSINESS_UDP = 41L

/** Deduplicated across networks: a connection that tried both counts once. */
private const val BUSINESS_ROOT = 152L
private const val TRIALS_OK_TCP = 9L
private const val TRIALS_FAILED_TCP = 2L
private const val TRIALS_OK_UDP = 3L
