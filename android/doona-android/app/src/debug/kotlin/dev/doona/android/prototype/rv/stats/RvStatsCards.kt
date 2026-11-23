package dev.doona.android.prototype.rv.stats

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import dev.doona.android.R
import dev.doona.android.prototype.rv.RV_GHOST_DOWN
import dev.doona.android.prototype.rv.RV_NO_VALUE
import dev.doona.android.prototype.rv.Rv
import dev.doona.android.prototype.rv.RvCard
import dev.doona.android.prototype.rv.RvCardHeader
import dev.doona.android.prototype.rv.RvChevron
import dev.doona.android.prototype.rv.RvFigure
import dev.doona.android.prototype.rv.RvGhostBars
import dev.doona.android.prototype.rv.RvIconDisc
import dev.doona.android.prototype.rv.RvLegendItem
import dev.doona.android.prototype.rv.RvRow
import dev.doona.android.prototype.rv.RvSpace
import dev.doona.android.prototype.rv.RvText
import dev.doona.android.prototype.rv.RvUsageChart
import dev.doona.android.prototype.rv.RvUsageLegend
import dev.doona.android.prototype.rv.RvUsageSize
import dev.doona.android.prototype.rv.RvWorldMap
import dev.doona.android.prototype.rv.data.RvPlace
import dev.doona.android.prototype.rv.data.RvSplit
import dev.doona.android.prototype.rv.data.RvSubscription
import dev.doona.android.prototype.rv.data.rvMedian
import dev.doona.android.prototype.rv.figure
import dev.doona.android.prototype.rv.rememberRvFormat
import kotlin.math.abs

/**
 * 已用流量, Revolut's 'Spent' card: today's bytes in both directions, the change against yesterday by the same time, and
 * the day stacked by direction in the right part of the card over its legend, as 活動's 今日用量 draws it (N-11). The
 * app-side daily history backs it in v0.1.
 */
@Composable
internal fun RvSpentCard(state: RvStatsState) {
    val format = rememberRvFormat()
    val day = state.today
    RvCard(Modifier.height(RvStatsSize.spent)) {
        Row(Modifier.weight(1f)) {
            Column(Modifier.weight(1f - RvStatsSize.SPENT_CHART_SHARE)) {
                RvCardHeader(stringResource(R.string.rv_stats_used))
                Column(Modifier.padding(horizontal = RvStatsSpace.pad)) {
                    RvStatsFigure(format.bytes(day.total).figure())
                    Spacer(Modifier.height(RvSpace.tight))
                    val yesterday = state.yesterdaySoFar
                    when {
                        day.quiet -> RvText(stringResource(R.string.rv_stats_nothing_today), Rv.type.tileCaption, color = Rv.colors.textSecondary)

                        yesterday != null && yesterday > 0 -> {
                            val change = (day.total - yesterday).toDouble() / yesterday
                            val percent = format.percent(abs(change))
                            RvStatsDelta(change >= 0, percent, stringResource(R.string.rv_stats_vs_yesterday), Rv.colors.positive)
                        }
                    }
                }
            }
            RvUsageChart(day, nowInHours(state), Modifier.weight(RvStatsSize.SPENT_CHART_SHARE).fillMaxHeight().padding(top = RvStatsSpace.pad, end = RvStatsSpace.pad))
        }
        Spacer(Modifier.height(RvUsageSize.chartAboveLegend))
        RvUsageLegend(day, cardBody)
    }
}

/**
 * The current hour's index plus the share of it gone: where the running total ends. The share comes from the instant
 * itself, as the hour buckets do, so a zone with a half-hour offset does not move it.
 */
private fun nowInHours(state: RvStatsState): Float = state.today.hours.lastIndex + (state.nowEpochMs % MS_PER_HOUR).toFloat() / MS_PER_HOUR

/** One of the paged tiles: what it counts, its figure, its bars by the hour, and its ghost bars' heights with no traffic. */
internal class RvDayTile(val label: String, val figure: RvFigure, val hours: List<Long>?, val color: Color, val v02: Boolean, val ghost: FloatArray = RV_GHOST_DOWN)

/**
 * How a tile with no traffic today looks, for the owner to compare (brief pv-c1). [Grid] is today's: the empty grid
 * and a short line. [Activity] (v1) is 活動's tile with no traffic ([RvSparkTile]): `目前沒有流量` under the figure and the
 * tile's own ghost bars. [First] (v2) is the first statistics sample's (b02dbc0e): the ghost bars under the figure alone,
 * one set of heights on every tile.
 */
internal enum class RvStatsEmptyLook { Grid, Activity, First }

/**
 * A paged tile, Revolut's 'Income' card: its header, the day's figure, then a bar per hour along the bottom. With no
 * data it shows the empty grid and a short line, never made-up bars (Addendum A); with no traffic, as [empty] says.
 */
@Composable
internal fun RvDayTileCard(tile: RvDayTile, modifier: Modifier = Modifier, empty: RvStatsEmptyLook = RvStatsEmptyLook.Grid) {
    RvCard(modifier.aspectRatio(1f)) {
        RvCardHeader(tile.label, tag = if (tile.v02) RV_V02 else null)
        Column(Modifier.weight(1f).then(cardBody)) {
            RvStatsFigure(tile.figure, Rv.type.tileValue, Rv.type.tileUnit)
            val hours = tile.hours
            val ghost = empty != RvStatsEmptyLook.Grid && hours != null && hours.all { it == 0L }
            if (ghost && empty == RvStatsEmptyLook.Activity) RvText(stringResource(R.string.rv_no_traffic), Rv.type.tileCaption, color = Rv.colors.textSecondary)
            Spacer(Modifier.weight(1f))
            when {
                hours == null -> RvEmptyChart(stringResource(R.string.rv_stats_no_data), Modifier.height(RvStatsSize.bars))
                ghost -> RvGhostBars(if (empty == RvStatsEmptyLook.First) RV_GHOST_DOWN else tile.ghost, Modifier.height(RvStatsSize.bars))
                hours.all { it == 0L } -> RvEmptyChart(stringResource(R.string.rv_stats_nothing_today), Modifier.height(RvStatsSize.bars))
                else -> RvHourBars(hours, tile.color)
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
                RvQuotaRing(segments)
            }
            withQuota.forEachIndexed { i, sub ->
                Spacer(Modifier.height(if (i == 0) RvStatsSpace.chartGap else RvStatsSpace.legendGap))
                QuotaLegend(sub, nowEpochMs, segments[i])
            }
        }
    }
}

/** `■ Harbor Cloud · 61.7 / 200 GB` with the date it runs out at the end, over `■ 剩餘 138 GB` in the lighter step. */
@Composable
private fun QuotaLegend(sub: RvSubscription, nowEpochMs: Long, segment: RvRingSegment) {
    val format = rememberRvFormat()
    val total = format.bytes(sub.totalBytes)
    val used = format.bytesIn(sub.usedBytes, total)
    val expires = sub.expiresAtEpochMs?.let { stringResource(R.string.rv_expires, format.expiry(it, nowEpochMs)) }
    val name = "${sub.name}$SEPARATOR$used / $total"
    RvLegendItem(segment.color, name, Modifier.fillMaxWidth(), Rv.colors.text, expires ?: stringResource(R.string.rv_stats_no_expiry))
    RvLegendItem(segment.rest, stringResource(R.string.rv_stats_quota_left, format.bytes(sub.leftBytes).toString()), Modifier.padding(top = RvSpace.tight))
}

/**
 * 節點分布, Revolut's 'Your trips' card: the world in grey with every country that has a node in the primary colour, and
 * how many countries and nodes under it.
 */
@Composable
internal fun RvNodeMapCard(state: RvStatsState) {
    val countries = state.nodes.mapNotNullTo(mutableSetOf()) { it.place.takeIf(RvPlace::mapped) }
    RvCard {
        RvCardHeader(stringResource(R.string.rv_stats_nodes))
        Column(cardBody) {
            RvWorldMap(countries)
            Spacer(Modifier.height(RvStatsSpace.chartGap))
            val countryCount = pluralStringResource(R.plurals.rv_stats_countries, countries.size, countries.size)
            val nodeCount = pluralStringResource(R.plurals.rv_stats_node_count, state.nodes.size, state.nodes.size)
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
    val tested = state.nodes.filter { it.latencyMs != null }.sortedBy { it.latencyMs }
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
            RvLatencyDots(values, selected, label, median, unit)
        }
    }
}

/**
 * 分流比例, Revolut's analyst ratings: the share that went through a proxy as the figure, and a bar each for proxy and
 * direct. The core counts no bytes per outbound yet, so the card carries the v0.2 tag and the sample's figures; with no
 * figures it is its own empty state: a dash, 尚無資料 and the two bars as bare tracks (N-14).
 */
@Composable
internal fun RvSplitCard(split: RvSplit?) {
    val format = rememberRvFormat()
    val proxy = split?.proxyShare ?: 0f
    RvCard {
        RvCardHeader(stringResource(R.string.rv_stats_split), tag = RV_V02)
        Column(cardBody) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    RvStatsFigure(RvFigure(split?.let { format.percent(proxy.toDouble()) } ?: RV_NO_VALUE, ""))
                    RvText(stringResource(if (split != null) R.string.rv_stats_via_proxy else R.string.rv_stats_no_data), Rv.type.rowSub, color = Rv.colors.textSecondary)
                }
                Column(Modifier.weight(1f)) {
                    RatingRow(proxy, format.percent(proxy.toDouble()), stringResource(R.string.rv_stats_proxy), Rv.colors.down.line, split != null)
                    RatingRow(1f - proxy, format.percent(1.0 - proxy), stringResource(R.string.rv_stats_direct), Rv.colors.textSecondary, split != null)
                }
            }
        }
    }
}

@Composable
private fun RatingRow(fraction: Float, percent: String, label: String, color: Color, known: Boolean) {
    Row(Modifier.height(RvStatsSize.ratingRow), verticalAlignment = Alignment.CenterVertically) {
        RvRatingBar(if (known) fraction else 0f, color, Modifier.weight(1f))
        Spacer(Modifier.width(RvSpace.trailingGap))
        RvText(if (known) "$percent $label" else label, Rv.type.rowSub, Modifier.widthIn(min = RvStatsSize.ratingLabel), align = TextAlign.End)
    }
}

/** 工具, Revolut's Tools card: a row per action with its disc, title, subtitle and chevron. */
@Composable
internal fun RvToolsCard(tools: List<RvStatsTool>, onTool: (Int) -> Unit) {
    RvCard {
        RvCardHeader(stringResource(R.string.rv_stats_tools))
        tools.forEachIndexed { i, tool ->
            RvRow(
                leading = { RvIconDisc(tool.icon) },
                title = tool.title,
                subtitle = tool.subtitle,
                onClick = { onTool(i) },
                trailing = { RvChevron() },
            )
        }
    }
}

/** A card's body under its header: the card's padding at the sides and the bottom. */
private val cardBody = Modifier.padding(start = RvStatsSpace.pad, end = RvStatsSpace.pad, bottom = RvStatsSpace.pad)

private const val SEPARATOR = " · "
private const val MS_PER_HOUR = 3_600_000L
