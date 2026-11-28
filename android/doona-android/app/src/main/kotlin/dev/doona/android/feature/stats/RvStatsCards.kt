package dev.doona.android.feature.stats

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import dev.doona.android.R
import dev.doona.android.designsystem.rv.RV_NO_VALUE
import dev.doona.android.designsystem.rv.Rv
import dev.doona.android.designsystem.rv.RvBarChart
import dev.doona.android.designsystem.rv.RvCard
import dev.doona.android.designsystem.rv.RvCardHeader
import dev.doona.android.designsystem.rv.RvChevron
import dev.doona.android.designsystem.rv.RvFigure
import dev.doona.android.designsystem.rv.RvIconDisc
import dev.doona.android.designsystem.rv.RvLatencySpread
import dev.doona.android.designsystem.rv.RvLegendItem
import dev.doona.android.designsystem.rv.RvMeter
import dev.doona.android.designsystem.rv.RvPlace
import dev.doona.android.designsystem.rv.RvRing
import dev.doona.android.designsystem.rv.RvRingSegment
import dev.doona.android.designsystem.rv.RvRow
import dev.doona.android.designsystem.rv.RvSpace
import dev.doona.android.designsystem.rv.RvStatsAlpha
import dev.doona.android.designsystem.rv.RvStatsSize
import dev.doona.android.designsystem.rv.RvStatsSpace
import dev.doona.android.designsystem.rv.RvText
import dev.doona.android.designsystem.rv.RvUsageChart
import dev.doona.android.designsystem.rv.RvUsageLegend
import dev.doona.android.designsystem.rv.RvUsageSize
import dev.doona.android.designsystem.rv.RvWorldMap
import dev.doona.android.designsystem.rv.SEPARATOR
import dev.doona.android.designsystem.rv.figure
import dev.doona.android.designsystem.rv.rememberRvFormat
import dev.doona.android.feature.model.RvSplit
import dev.doona.android.feature.model.RvSubscription
import dev.doona.android.feature.model.rvMedian
import kotlin.math.abs

/**
 * 已用流量, Revolut's 'Spent' card: the range's bytes in both directions, the change against the range before by the
 * same point (yesterday, the week before; a month has none), and the range stacked by direction in the right part of the
 * card over its legend, by the hour as 活動's 今日用量 draws the day (N-11) or by the day. The app-side daily history
 * backs it in v0.1. Bytes are not counted by subscription, so while one is picked its tag says 全部訂閱.
 */
@Composable
internal fun RvSpentCard(state: RvStatsState) {
    val format = rememberRvFormat()
    val period = state.period
    val day = period?.days ?: state.today
    val scope = stringResource(R.string.rv_stats_all_subscriptions).takeIf { state.subscriptionId != null }
    RvCard(Modifier.height(RvStatsSize.spent)) {
        Row(Modifier.weight(1f)) {
            Column(Modifier.weight(1f - RvStatsSize.SPENT_CHART_SHARE)) {
                RvCardHeader(stringResource(R.string.rv_stats_used), tag = scope)
                Column(Modifier.padding(horizontal = RvSpace.rowPadding)) {
                    RvStatsFigure(format.bytes(day.total).figure())
                    Spacer(Modifier.height(RvSpace.tight))
                    val before = if (period != null) period.before else state.yesterdaySoFar
                    val against = state.range.against
                    when {
                        day.quiet -> RvText(stringResource(state.range.quiet), Rv.type.tileCaption, color = Rv.colors.textSecondary)

                        before != null && before > 0 && against != null -> {
                            val change = (day.total - before).toDouble() / before
                            val percent = format.percent(abs(change))
                            RvStatsDelta(change >= 0, percent, against, Rv.colors.positive)
                        }
                    }
                }
            }
            val share = Modifier.weight(RvStatsSize.SPENT_CHART_SHARE).testTag(RV_SPENT_CHART).fillMaxHeight().padding(top = RvSpace.rowPadding, end = RvSpace.rowPadding)
            if (period == null) RvUsageChart(day, state.nowHour, share) else RvUsageChart(day, period.now, share, period.slots, gridEvery(period.slots))
        }
        Spacer(Modifier.height(RvUsageSize.chartAboveLegend))
        RvUsageLegend(day, cardBody)
    }
}

/** The test tag of 已用流量's chart. */
internal const val RV_SPENT_CHART = "rv-spent-chart"

/**
 * One of the paged tiles: what it counts ([tag] when that differs from the chips), its figure, its bars ([hours], one per
 * hour or day of [slots]), and the line it shows with no traffic ([quiet]).
 */
internal class RvDayTile(
    val label: String,
    val figure: RvFigure,
    val hours: List<Long>?,
    val color: Color,
    val slots: Int = HOURS,
    val quiet: String,
    val tag: String? = null,
)

/**
 * A paged tile, Revolut's 'Income' card: its header, the day's figure, then a bar per hour along the bottom. With no
 * data or no traffic it shows the empty grid and a short line, never made-up bars (Addendum A; the owner picked the
 * grid, 2026-11-24 15:24).
 */
@Composable
internal fun RvDayTileCard(tile: RvDayTile, modifier: Modifier = Modifier) {
    RvCard(modifier.aspectRatio(1f)) {
        RvCardHeader(tile.label, tag = tile.tag)
        Column(Modifier.weight(1f).then(cardBody)) {
            RvStatsFigure(tile.figure, Rv.type.tileValue, Rv.type.tileUnit)
            val hours = tile.hours
            Spacer(Modifier.weight(1f))
            when {
                hours == null -> RvEmptyChart(stringResource(R.string.rv_stats_no_data), Modifier.height(RvStatsSize.bars), tile.slots)
                hours.all { it == 0L } -> RvEmptyChart(tile.quiet, Modifier.height(RvStatsSize.bars), tile.slots)
                else -> RvBarChart(hours, tile.color, slots = tile.slots)
            }
        }
    }
}

/**
 * 訂閱流量, Revolut's 'Total assets' card: what every subscription used together, the ring of their quotas, and a legend
 * entry per subscription: its usage and expiry by the used colour, what is left by the lighter one (S05). The snapshot's
 * quota fields back it today.
 */
@Composable
internal fun RvQuotaCard(subscriptions: List<RvSubscription>, nowEpochMs: Long) {
    val format = rememberRvFormat()
    val withQuota = subscriptions.filter { it.totalBytes > 0L }
    // Categories, not directions: the ring keeps download's and upload's colours for those alone (L-17).
    val palette = Rv.colors.categories
    val card = Rv.colors.card
    val segments = withQuota.mapIndexed { i, s ->
        val color = palette[i % palette.size]
        RvRingSegment(color, lerp(color, card, RvStatsAlpha.RING_REST), s.usedBytes, s.totalBytes)
    }
    RvCard {
        RvCardHeader(stringResource(R.string.rv_stats_quota))
        Column(cardBody) {
            Row {
                Column(Modifier.weight(1f)) {
                    if (withQuota.isEmpty()) {
                        RvStatsFigure(RvFigure(RV_NO_VALUE, ""))
                        RvText(stringResource(R.string.rv_stats_no_quota), Rv.type.rowSub, color = Rv.colors.textSecondary)
                    } else {
                        RvStatsFigure(format.bytes(segments.sumOf { it.used }).figure())
                        val total = format.bytes(segments.sumOf { it.total })
                        val count = pluralStringResource(R.plurals.rv_stats_subscriptions, withQuota.size, withQuota.size)
                        RvText(stringResource(R.string.rv_stats_quota_total, total.toString(), count), Rv.type.rowSub, color = Rv.colors.textSecondary)
                    }
                }
                RvRing(segments)
            }
            withQuota.forEachIndexed { i, sub ->
                Spacer(Modifier.height(if (i == 0) RvStatsSpace.chartGap else RvStatsSpace.legendGap))
                QuotaLegend(sub, nowEpochMs, segments[i])
            }
        }
    }
}

/** `■ Harbor Cloud，61.7/200 GB` with the date it runs out at the end, over `■ 剩餘 138 GB` in the lighter step. */
@Composable
private fun QuotaLegend(sub: RvSubscription, nowEpochMs: Long, segment: RvRingSegment) {
    val format = rememberRvFormat()
    val total = format.bytes(sub.totalBytes)
    val used = format.usedOf(sub.usedBytes, total)
    val expires = sub.expiresAtEpochMs?.let { stringResource(R.string.rv_expires, format.expiry(it, nowEpochMs)) }
    val name = sub.name + stringResource(R.string.rv_comma) + "$used/$total"
    RvLegendItem(segment.color, name, Modifier.fillMaxWidth(), Rv.colors.text, expires ?: stringResource(R.string.rv_subs_no_expiry))
    RvLegendItem(segment.rest, stringResource(R.string.rv_stats_quota_left, format.bytes(sub.leftBytes).toString()), Modifier.padding(top = RvSpace.tight))
}

/**
 * 節點分布, Revolut's 'Your trips' card: the world in grey with every country that has a node in the primary colour, and
 * how many countries and nodes under it.
 */
@Composable
internal fun RvNodeMapCard(state: RvStatsState) {
    val nodes = state.shownNodes
    val countries = nodes.mapNotNullTo(mutableSetOf()) { it.place.takeIf(RvPlace::mapped) }
    RvCard {
        RvCardHeader(stringResource(R.string.rv_stats_nodes))
        Column(cardBody) {
            RvWorldMap(countries)
            Spacer(Modifier.height(RvStatsSpace.chartGap))
            val countryCount = pluralStringResource(R.plurals.rv_stats_countries, countries.size, countries.size)
            val nodeCount = pluralStringResource(R.plurals.rv_stats_node_count, nodes.size, nodes.size)
            RvText(countryCount + SEPARATOR + nodeCount, Rv.type.rowTitle)
        }
    }
}

/**
 * 延遲分布: the median of the tested nodes as the figure, then Revolut's EPS dot chart of every tested node from the
 * fastest, the median as its dotted line and the current node in its chip column.
 */
@Composable
internal fun RvLatencyCard(state: RvStatsState) {
    val tested = state.shownNodes.filter { it.latencyMs != null }.sortedBy { it.latencyMs }
    val values = tested.mapNotNull { it.latencyMs }
    val selected = tested.indexOfFirst { it.id == state.selectedId }.takeIf { it >= 0 }
    val unit = stringResource(R.string.rv_stats_ms)
    val median = rvMedian(values)
    RvCard {
        RvCardHeader(stringResource(R.string.rv_stats_latency))
        Column(cardBody) {
            RvStatsFigure(RvFigure(median?.toString() ?: RV_NO_VALUE, " $unit"))
            RvText(stringResource(R.string.rv_stats_median, values.size), Rv.type.rowSub, color = Rv.colors.textSecondary)
            Spacer(Modifier.height(RvStatsSpace.chartGap))
            // The current node's chip says so in words, not by its tint alone.
            val inUse = stringResource(R.string.rv_nodes_in_use)
            val label = selected?.let { "$inUse$SEPARATOR${tested[it].name}$SEPARATOR${values[it]} $unit" } ?: ""
            RvLatencySpread(values, selected, label, median, unit)
        }
    }
}

/**
 * 分流比例, Revolut's analyst ratings: the share that went through a proxy as the figure, and a bar each for proxy and
 * direct, from the bytes of each route target (`direct` against the rest); with no
 * figures it is its own empty state: a dash, 尚無資料 and the two bars as bare tracks (N-14). Its tag names what it
 * counts when that differs from the chips ([scope]).
 */
@Composable
internal fun RvSplitCard(split: RvSplit?, scope: List<String> = emptyList()) {
    val format = rememberRvFormat()
    val proxy = split?.proxyShare ?: 0f
    RvCard {
        RvCardHeader(stringResource(R.string.rv_stats_split), tag = scope.takeIf { it.isNotEmpty() }?.joinToString(SEPARATOR))
        Column(cardBody) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    RvStatsFigure(RvFigure(split?.let { format.percent(proxy.toDouble()) } ?: RV_NO_VALUE, ""))
                    RvText(stringResource(if (split != null) R.string.rv_stats_via_proxy else R.string.rv_stats_no_data), Rv.type.rowSub, color = Rv.colors.textSecondary)
                }
                Column(Modifier.weight(1f)) {
                    RvMeter(proxy, format.percent(proxy.toDouble()), stringResource(R.string.rv_stats_proxy), Rv.colors.down.line, split != null)
                    RvMeter(1f - proxy, format.percent(1.0 - proxy), stringResource(R.string.rv_stats_direct), Rv.colors.textSecondary, split != null)
                }
            }
        }
    }
}

/** 工具, Revolut's Tools card: a row per action with its disc, title, subtitle and chevron. */
@Composable
internal fun RvToolsCard(tools: List<RvStatsTool>) {
    RvCard {
        RvCardHeader(stringResource(R.string.rv_stats_tools))
        tools.forEach { tool ->
            RvRow(
                leading = { RvIconDisc(tool.icon) },
                title = tool.title,
                subtitle = tool.subtitle,
                onClick = tool.onClick,
                trailing = { RvChevron() },
            )
        }
    }
}

/** A card's body under its header: the card's padding at the sides and the bottom. */
internal val cardBody = Modifier.padding(start = RvSpace.rowPadding, end = RvSpace.rowPadding, bottom = RvSpace.rowPadding)

/** The chart grid's step: every 4 hours of a day, every day of a week, every week of a month. */
internal fun gridEvery(slots: Int): Int = when (slots) {
    HOURS -> RvUsageSize.GRID_HOURS
    DAYS_PER_WEEK -> 1
    else -> DAYS_PER_WEEK
}
