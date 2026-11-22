package dev.doona.android.prototype.rv.stats

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import dev.doona.android.R
import dev.doona.android.engine.Subscription
import dev.doona.android.prototype.rv.RV_NO_VALUE
import dev.doona.android.prototype.rv.Rv
import dev.doona.android.prototype.rv.RvCard
import dev.doona.android.prototype.rv.RvFigure
import dev.doona.android.prototype.rv.RvGhostBars
import dev.doona.android.prototype.rv.RvIconDisc
import dev.doona.android.prototype.rv.RvRow
import dev.doona.android.prototype.rv.RvSpace
import dev.doona.android.prototype.rv.RvText
import dev.doona.android.prototype.rv.withoutFlag
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.math.abs

/**
 * 已用流量, Revolut's 'Spent' card: today's bytes in both directions, the change against yesterday by the same time,
 * and the running total stepped by the hour in the right part of the card. The app-side daily history backs it in v0.1.
 */
@Composable
internal fun RvSpentCard(state: RvStatsState) {
    val locale = LocalConfiguration.current.locales[0]
    val day = state.today
    RvCard(Modifier.height(RvStatsSize.spent)) {
        Column(Modifier.fillMaxSize().padding(RvStatsSpace.pad)) {
            Row(Modifier.weight(1f)) {
                Column(Modifier.weight(1f - RvStatsSize.SPENT_CHART_SHARE)) {
                    RvStatsLabel(stringResource(R.string.rv_stats_used))
                    RvStatsFigure(bytesFigure(day.total, locale))
                    Spacer(Modifier.height(RvSpace.tight))
                    val yesterday = state.yesterdaySoFar
                    when {
                        day.quiet -> RvText(stringResource(R.string.rv_stats_nothing_today), Rv.type.tileCaption, color = Rv.colors.textSecondary)

                        yesterday != null && yesterday > 0 -> {
                            val change = (day.total - yesterday).toDouble() / yesterday
                            val percent = NumberFormat.getPercentInstance(locale).format(abs(change))
                            RvStatsDelta(change >= 0, percent, stringResource(R.string.rv_stats_vs_yesterday), Rv.colors.positive)
                        }
                    }
                }
                RvSteppedArea(day.hours, nowInHours(state), Modifier.weight(RvStatsSize.SPENT_CHART_SHARE).fillMaxHeight())
            }
            Spacer(Modifier.height(RvSpace.subGap))
            RvStatsSource(stringResource(R.string.rv_stats_source, stringResource(R.string.rv_stats_source_history)))
        }
    }
}

/** The current hour's index plus the share of it gone, in the device's zone: where the running total ends. */
private fun nowInHours(state: RvStatsState): Float {
    val now = Instant.ofEpochMilli(state.nowEpochMs).atZone(ZoneId.systemDefault())
    return state.today.hours.lastIndex + now.minute / MINUTES_PER_HOUR
}

/** One of the paged tiles: what it counts, its figure, where the figure comes from, and its bars by the hour. */
internal class RvDayTile(val label: String, val figure: RvFigure, val source: String, val hours: List<Long>?, val color: Color, val v02: Boolean)

/**
 * A paged tile, Revolut's 'Income' card: its label, the day's figure, its source, then a bar per hour along the
 * bottom. With no traffic yet it takes the empty card's ghost bars.
 */
@Composable
internal fun RvDayTileCard(tile: RvDayTile, modifier: Modifier = Modifier) {
    RvCard(modifier.height(RvStatsSize.tile)) {
        Column(Modifier.fillMaxSize().padding(RvStatsSpace.pad)) {
            RvStatsLabel(tile.label, tile.v02)
            Spacer(Modifier.height(RvSpace.tight))
            RvStatsFigure(tile.figure, Rv.type.tileValue, Rv.type.tileUnit)
            RvStatsSource(tile.source)
            Spacer(Modifier.weight(1f))
            val hours = tile.hours
            if (hours == null || hours.all { it == 0L }) RvGhostBars(Modifier.height(RvStatsSize.bars)) else RvHourBars(hours, tile.color)
        }
    }
}

/**
 * 訂閱流量, Revolut's 'Total assets' card: what every subscription used together, the ring of their quotas, and a legend
 * entry per subscription with its usage and expiry. The snapshot's quota fields back it today.
 */
@Composable
internal fun RvQuotaCard(subscriptions: List<Subscription>) {
    val locale = LocalConfiguration.current.locales[0]
    val withQuota = subscriptions.filter { (it.totalBytes ?: 0L) > 0L }
    val palette = listOf(Rv.colors.down.line, Rv.colors.up.line, Rv.colors.textSecondary)
    val segments = withQuota.mapIndexed { i, s -> RvRingSegment(palette[i % palette.size], s.usedBytes ?: 0L, s.totalBytes ?: 0L) }
    RvCard {
        Column(Modifier.padding(RvStatsSpace.pad)) {
            Row {
                Column(Modifier.weight(1f)) {
                    RvStatsLabel(stringResource(R.string.rv_stats_quota))
                    if (withQuota.isEmpty()) {
                        RvStatsFigure(RvFigure(RV_NO_VALUE, ""))
                        RvText(stringResource(R.string.rv_stats_no_quota), Rv.type.rowSub, color = Rv.colors.textSecondary)
                    } else {
                        RvStatsFigure(bytesFigure(segments.sumOf { it.used }, locale, fixed = false))
                        val total = bytesFigure(segments.sumOf { it.total }, locale, fixed = false)
                        val count = pluralStringResource(R.plurals.rv_stats_subscriptions, withQuota.size, withQuota.size)
                        RvText(stringResource(R.string.rv_stats_quota_total, total.whole + total.unit, count), Rv.type.rowSub, color = Rv.colors.textSecondary)
                    }
                }
                RvQuotaRing(segments)
            }
            withQuota.forEachIndexed { i, sub ->
                Spacer(Modifier.height(if (i == 0) RvStatsSpace.chartGap else RvStatsSpace.legendGap))
                QuotaLegend(sub, segments[i].color)
            }
            Spacer(Modifier.height(RvStatsSpace.chartGap))
            RvStatsSource(stringResource(R.string.rv_stats_source, stringResource(R.string.rv_stats_source_quota)))
        }
    }
}

/** `■ Harbor Cloud · 62.4 / 200 GB`, and the date it runs out at the end of the line. */
@Composable
private fun QuotaLegend(sub: Subscription, color: Color) {
    val locale = LocalConfiguration.current.locales[0]
    val total = bytesFigure(sub.totalBytes ?: 0L, locale, fixed = false)
    val used = decimal((sub.usedBytes ?: 0L) / bytesPer(total.unit), locale, fixed = false)
    Row(verticalAlignment = Alignment.CenterVertically) {
        RvStatsSwatch(color)
        Spacer(Modifier.width(RvSpace.subGap + RvSpace.tight))
        RvText("${sub.name}$SEPARATOR$used / ${total.whole}${total.unit}", Rv.type.rowSub, Modifier.weight(1f))
        val expires = sub.expiresAtEpochMs?.let { stringResource(R.string.rv_expires, DATE.withLocale(locale).format(Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()))) }
        RvText(expires ?: stringResource(R.string.rv_stats_no_expiry), Rv.type.tileCaption, color = Rv.colors.textSecondary, align = TextAlign.End)
    }
}

/**
 * 節點分佈, Revolut's 'Your trips' card: the world in grey with every country that has a node in the primary colour, and
 * how many countries and nodes under it. The countries come from the node names and flags the app already reads.
 */
@Composable
internal fun RvNodeMapCard(state: RvStatsState) {
    val countries = state.nodes.mapNotNull { countryOf(it.name) }.toSet()
    RvCard {
        Column(Modifier.padding(RvStatsSpace.pad)) {
            RvStatsLabel(stringResource(R.string.rv_stats_nodes))
            Spacer(Modifier.height(RvStatsSpace.chartGap))
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
 * 延遲分佈: the median of the tested nodes as the figure, then Revolut's EPS dot chart of every tested node from the
 * fastest, the current node in its chip column. The latency results back it.
 */
@Composable
internal fun RvLatencyCard(state: RvStatsState) {
    val tested = state.nodes.filter { it.latencyMs != null }.sortedBy { it.latencyMs }
    val values = tested.mapNotNull { it.latencyMs }
    val selected = tested.indexOfFirst { it.id == state.selectedId }.takeIf { it >= 0 }
    val unit = stringResource(R.string.rv_stats_ms)
    RvCard {
        Column(Modifier.padding(RvStatsSpace.pad)) {
            RvStatsLabel(stringResource(R.string.rv_stats_latency))
            RvStatsFigure(RvFigure(values.getOrNull(values.size / 2)?.toString() ?: RV_NO_VALUE, " $unit"))
            RvText(stringResource(R.string.rv_stats_median, values.size), Rv.type.rowSub, color = Rv.colors.textSecondary)
            Spacer(Modifier.height(RvStatsSpace.chartGap))
            val label = selected?.let { "${withoutFlag(tested[it].name)}$SEPARATOR${values[it]} $unit" } ?: ""
            RvLatencyDots(values, selected, label)
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
    val percent = NumberFormat.getPercentInstance(LocalConfiguration.current.locales[0])
    val proxy = split?.proxyShare ?: 0f
    RvCard {
        Column(Modifier.padding(RvStatsSpace.pad)) {
            RvStatsLabel(stringResource(R.string.rv_stats_split), v02 = true)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    RvStatsFigure(RvFigure(split?.let { percent.format(proxy.toDouble()) } ?: RV_NO_VALUE, ""))
                    RvText(stringResource(R.string.rv_stats_via_proxy), Rv.type.rowSub, color = Rv.colors.textSecondary)
                }
                Column(Modifier.weight(1f)) {
                    RatingRow(proxy, percent.format(proxy.toDouble()), stringResource(R.string.rv_stats_proxy), Rv.colors.down.line, split != null)
                    RatingRow(1f - proxy, percent.format(1.0 - proxy), stringResource(R.string.rv_stats_direct), Rv.colors.textSecondary, split != null)
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
        RvText(if (known) "$percent $label" else label, Rv.type.rowSub, Modifier.width(RvStatsSize.ratingLabel), align = TextAlign.End)
    }
}

/** 工具, Revolut's Tools card: a row per action with its disc, title, subtitle and chevron. */
@Composable
internal fun RvToolsCard(tools: List<RvStatsTool>, onTool: (Int) -> Unit) {
    RvCard {
        Column(Modifier.fillMaxWidth().padding(top = RvStatsSpace.pad), verticalArrangement = Arrangement.Top) {
            Row(Modifier.padding(horizontal = RvSpace.rowPadding)) { RvStatsLabel(stringResource(R.string.rv_stats_tools)) }
            tools.forEachIndexed { i, tool ->
                RvRow(
                    leading = { RvIconDisc(tool.icon) },
                    title = tool.title,
                    subtitle = tool.subtitle,
                    onClick = { onTool(i) },
                    trailing = { RvStatsChevron() },
                )
            }
        }
    }
}

private val DATE = DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT)
private const val SEPARATOR = " · "
private const val MINUTES_PER_HOUR = 60f
