package dev.doona.android.prototype.rv.stats

import dev.doona.android.prototype.rv.data.RvData

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
