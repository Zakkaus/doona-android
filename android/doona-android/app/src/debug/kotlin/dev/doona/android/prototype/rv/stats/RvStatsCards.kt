package dev.doona.android.prototype.rv.stats

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import dev.doona.android.R
import dev.doona.android.prototype.rv.RV_NO_VALUE
import dev.doona.android.prototype.rv.Rv
import dev.doona.android.prototype.rv.RvCard
import dev.doona.android.prototype.rv.RvCardHeader
import dev.doona.android.prototype.rv.RvChevron
import dev.doona.android.prototype.rv.RvFigure
import dev.doona.android.prototype.rv.RvIconDisc
import dev.doona.android.prototype.rv.RvRow
import dev.doona.android.prototype.rv.RvSpace
import dev.doona.android.prototype.rv.RvText
import dev.doona.android.prototype.rv.RvWorldMap
import dev.doona.android.prototype.rv.data.RvSplit
import dev.doona.android.prototype.rv.data.RvSubscription
import dev.doona.android.prototype.rv.data.rvMedian
import dev.doona.android.prototype.rv.figure
import dev.doona.android.prototype.rv.rememberRvFormat
import kotlin.math.abs

/**
 * 已用流量, Revolut's 'Spent' card: today's bytes in both directions, the change against yesterday by the same time,
 * and the running total stepped by the hour in the right part of the card. The app-side daily history backs it in v0.1.
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
            RvSteppedArea(day.hours, nowInHours(state), Modifier.weight(RvStatsSize.SPENT_CHART_SHARE).fillMaxHeight().padding(top = RvStatsSpace.pad, end = RvStatsSpace.pad))
        }
        Spacer(Modifier.height(RvSpace.subGap))
        RvStatsSource(stringResource(R.string.rv_stats_source, stringResource(R.string.rv_stats_source_history)), cardBody)
    }
}

/**
 * The current hour's index plus the share of it gone: where the running total ends. The share comes from the instant
 * itself, as the hour buckets do, so a zone with a half-hour offset does not move it.
 */
private fun nowInHours(state: RvStatsState): Float = state.today.hours.lastIndex + (state.nowEpochMs % MS_PER_HOUR).toFloat() / MS_PER_HOUR

/** One of the paged tiles: what it counts, its figure, where the figure comes from, and its bars by the hour. */
internal class RvDayTile(val label: String, val figure: RvFigure, val source: String, val hours: List<Long>?, val color: Color, val v02: Boolean)

/**
 * A paged tile, Revolut's 'Income' card: its header, the day's figure, its source, then a bar per hour along the
 * bottom. With no traffic, or no data, it shows the empty grid and a short line, never made-up bars (Addendum A).
 */
@Composable
internal fun RvDayTileCard(tile: RvDayTile, modifier: Modifier = Modifier) {
    RvCard(modifier.height(RvStatsSize.tile)) {
        RvCardHeader(tile.label, tag = if (tile.v02) RV_V02 else null)
        Column(Modifier.weight(1f).then(cardBody)) {
            RvStatsFigure(tile.figure, Rv.type.tileValue, Rv.type.tileUnit)
            RvStatsSource(tile.source)
            Spacer(Modifier.weight(1f))
            val hours = tile.hours
            when {
                hours == null -> RvEmptyChart(stringResource(R.string.rv_stats_no_data), Modifier.height(RvStatsSize.bars))
                hours.all { it == 0L } -> RvEmptyChart(stringResource(R.string.rv_stats_nothing_today), Modifier.height(RvStatsSize.bars))
                else -> RvHourBars(hours, tile.color)
            }
        }
    }
}

/**
 * 訂閱流量, Revolut's 'Total assets' card: what every subscription used together, the ring of their quotas, and a legend
 * entry per subscription with its usage and expiry. The snapshot's quota fields back it today.
 */
@Composable
internal fun RvQuotaCard(subscriptions: List<RvSubscription>, nowEpochMs: Long) {
    val format = rememberRvFormat()
    val withQuota = subscriptions.filter { it.totalBytes > 0L }
    // Categories, not directions: the ring keeps download's and upload's colours for those alone (L-17).
    val palette = Rv.colors.categories
    val segments = withQuota.mapIndexed { i, s -> RvRingSegment(palette[i % palette.size], s.usedBytes, s.totalBytes) }
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
                QuotaLegend(sub, nowEpochMs, segments[i].color)
            }
            Spacer(Modifier.height(RvStatsSpace.chartGap))
            RvStatsSource(stringResource(R.string.rv_stats_source, stringResource(R.string.rv_stats_source_quota)))
        }
    }
}

/** `■ Harbor Cloud · 62.4 / 200 GB`, and the date it runs out at the end of the line. */
@Composable
private fun QuotaLegend(sub: RvSubscription, nowEpochMs: Long, color: Color) {
    val format = rememberRvFormat()
    val total = format.bytes(sub.totalBytes)
    val used = format.bytesIn(sub.usedBytes, total)
    Row(verticalAlignment = Alignment.CenterVertically) {
        RvStatsSwatch(color)
        Spacer(Modifier.width(RvSpace.subGap + RvSpace.tight))
        RvText("${sub.name}$SEPARATOR$used / $total", Rv.type.rowSub, Modifier.weight(1f))
        val expires = sub.expiresAtEpochMs?.let { stringResource(R.string.rv_expires, format.expiry(it, nowEpochMs)) }
        RvText(expires ?: stringResource(R.string.rv_stats_no_expiry), Rv.type.tileCaption, color = Rv.colors.textSecondary, align = TextAlign.End)
    }
}

/**
 * 節點分布, Revolut's 'Your trips' card: the world in grey with every country that has a node in the primary colour, and
 * how many countries and nodes under it. The countries come from the node names and flags the app already reads.
 */
@Composable
internal fun RvNodeMapCard(state: RvStatsState) {
    val countries = state.nodes.mapTo(mutableSetOf()) { it.place }
    RvCard {
        RvCardHeader(stringResource(R.string.rv_stats_nodes))
        Column(cardBody) {
            RvWorldMap(countries)
            Spacer(Modifier.height(RvStatsSpace.chartGap))
            val countryCount = pluralStringResource(R.plurals.rv_stats_countries, countries.size, countries.size)
            val nodeCount = pluralStringResource(R.plurals.rv_stats_node_count, state.nodes.size, state.nodes.size)
            RvText(countryCount + SEPARATOR + nodeCount, Rv.type.rowTitle)
            Spacer(Modifier.height(RvSpace.tight))
            RvStatsSource(stringResource(R.string.rv_stats_source, stringResource(R.string.rv_stats_source_nodes)))
        }
    }
}

/**
 * 延遲分布: the median of the tested nodes as the figure, then Revolut's EPS dot chart of every tested node from the
 * fastest, the current node in its chip column. The latency results back it.
 */
@Composable
internal fun RvLatencyCard(state: RvStatsState) {
    val tested = state.nodes.filter { it.latencyMs != null }.sortedBy { it.latencyMs }
    val values = tested.mapNotNull { it.latencyMs }
    val selected = tested.indexOfFirst { it.id == state.selectedId }.takeIf { it >= 0 }
    val unit = stringResource(R.string.rv_stats_ms)
    RvCard {
        RvCardHeader(stringResource(R.string.rv_stats_latency))
        Column(cardBody) {
            RvStatsFigure(RvFigure(rvMedian(values)?.toString() ?: RV_NO_VALUE, " $unit"))
            RvText(stringResource(R.string.rv_stats_median, values.size), Rv.type.rowSub, color = Rv.colors.textSecondary)
            Spacer(Modifier.height(RvStatsSpace.chartGap))
            // The current node's chip says so in words, not by its tint alone.
            val inUse = stringResource(R.string.rv_nodes_in_use)
            val label = selected?.let { "$inUse$SEPARATOR${tested[it].name}$SEPARATOR${values[it]} $unit" } ?: ""
            RvLatencyDots(values, selected, label, unit)
            Spacer(Modifier.height(RvStatsSpace.chartGap))
            RvStatsSource(stringResource(R.string.rv_stats_source, stringResource(R.string.rv_stats_source_latency)))
        }
    }
}

/**
 * 分流比例, Revolut's analyst ratings: the share that went through a proxy as the figure, and a bar each for proxy and
 * direct. The core counts no bytes per outbound yet, so the card carries the v0.2 tag and the sample's figures.
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
                    RvText(stringResource(R.string.rv_stats_via_proxy), Rv.type.rowSub, color = Rv.colors.textSecondary)
                }
                Column(Modifier.weight(1f)) {
                    RatingRow(proxy, format.percent(proxy.toDouble()), stringResource(R.string.rv_stats_proxy), Rv.colors.down.line, split != null)
                    RatingRow(1f - proxy, format.percent(1.0 - proxy), stringResource(R.string.rv_stats_direct), Rv.colors.textSecondary, split != null)
                }
            }
            Spacer(Modifier.height(RvSpace.subGap))
            RvStatsSource(stringResource(R.string.rv_stats_needs, stringResource(R.string.rv_stats_needs_split)))
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
