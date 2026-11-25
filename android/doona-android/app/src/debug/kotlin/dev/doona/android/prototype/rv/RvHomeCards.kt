package dev.doona.android.prototype.rv

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Constraints
import dev.doona.android.R
import dev.doona.android.designsystem.rv.Rv
import dev.doona.android.designsystem.rv.RvCard
import dev.doona.android.designsystem.rv.RvCardHeader
import dev.doona.android.designsystem.rv.RvFigureText
import dev.doona.android.designsystem.rv.RvFlag
import dev.doona.android.designsystem.rv.RvLatencyChange
import dev.doona.android.designsystem.rv.RvRow
import dev.doona.android.designsystem.rv.RvSize
import dev.doona.android.designsystem.rv.RvSpace
import dev.doona.android.designsystem.rv.RvText
import dev.doona.android.designsystem.rv.RvUsageChart
import dev.doona.android.designsystem.rv.RvUsageLegend
import dev.doona.android.designsystem.rv.RvUsageSize
import dev.doona.android.designsystem.rv.figure
import dev.doona.android.designsystem.rv.rememberRvFormat
import dev.doona.android.designsystem.rv.rvMoves
import dev.doona.android.feature.NOT_WIRED
import dev.doona.android.feature.model.RvDay
import dev.doona.android.feature.model.RvNode
import dev.doona.android.feature.model.RvSubscription
import dev.doona.android.feature.nodes.ui.RvSubscriptionRow
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 今日用量 in the style of Revolut's 'Spent this month' (measured §10.2, study §5): the title, today's total and a
 * comparison with yesterday at the start; download and upload as stepped cumulative areas stacked in the right half, so
 * the top edge is the total; and the legend at the bottom start. The comparison has no colour: traffic has no good or
 * bad direction. Where the text does not fit beside the chart (a large font size), the card grows: the text takes the
 * whole width and wraps, the chart follows [RvSpace.chartGap] under it at the whole width and its height, and the legend
 * wraps under the chart.
 */
@Composable
internal fun RvUsageCard(day: RvDay, nowHour: Float, yesterdayByNow: Long) {
    val format = rememberRvFormat()
    val colors = Rv.colors
    val type = Rv.type
    val change = day.total - yesterdayByNow
    val compare = if (change < 0) R.string.rv_less_than_yesterday else R.string.rv_more_than_yesterday
    val legendLine = with(LocalDensity.current) { type.tileCaption.lineHeight.toDp() }
    RvCard {
        Layout(
            {
                Column {
                    RvText(stringResource(R.string.rv_today_usage), type.cardTitle, color = colors.textSecondary)
                    Spacer(Modifier.height(RvSpace.tight))
                    RvFigureText(format.bytes(day.total).figure(), type.cardValue, type.cardUnit)
                    RvText(stringResource(compare, format.bytes(abs(change)).toString()), type.tileCaption, color = colors.textSecondary, maxLines = Int.MAX_VALUE)
                }
                RvUsageChart(day, nowHour, Modifier.padding(bottom = RvUsageSize.chartAboveLegend + legendLine))
                RvUsageLegend(day)
            },
            Modifier.fillMaxWidth().padding(RvUsageSize.pad),
        ) { (text, chart, legend), constraints ->
            val width = constraints.maxWidth
            // The chart's room as the card's fixed height leaves it, the legend's line under the chart included.
            val height = (RvUsageSize.card - RvUsageSize.pad * 2).roundToPx()
            val chartWidth = (width * CHART_SHARE).roundToInt()
            val beside = text.maxIntrinsicWidth(height) <= width - chartWidth - RvSpace.trailingGap.roundToPx() && legend.maxIntrinsicWidth(height) <= width
            val textPart = text.measure(Constraints(maxWidth = width, maxHeight = if (beside) height else Constraints.Infinity))
            val chartPart = chart.measure(Constraints.fixed(if (beside) chartWidth else width, height))
            val legendPart = legend.measure(Constraints(maxWidth = width))
            if (beside) {
                layout(width, height) {
                    chartPart.place(width - chartWidth, 0)
                    textPart.place(0, 0)
                    legendPart.place(0, height - legendPart.height)
                }
            } else {
                val chartTop = textPart.height + RvSpace.chartGap.roundToPx()
                val legendTop = chartTop + height - (RvUsageSize.chartAboveLegend + legendLine).roundToPx() + RvUsageSize.chartAboveLegend.roundToPx()
                layout(width, legendTop + legendPart.height) {
                    textPart.place(0, 0)
                    chartPart.place(0, chartTop)
                    legendPart.place(0, legendTop)
                }
            }
        }
    }
}

/**
 * 訂閱 in the style of Revolut's Total wealth: the card's own header, the data left on every subscription, and a row
 * for each, with a neutral disc (colour means a direction on this page, L-17), its use against its quota, its end date
 * as of [nowEpochMs] and what is left. Its header opens 訂閱 ([onOpen]).
 */
@Composable
internal fun RvSubscriptionsCard(subscriptions: List<RvSubscription>, nowEpochMs: Long, onOpen: () -> Unit = NOT_WIRED) {
    val format = rememberRvFormat()
    RvCard {
        RvCardHeader(stringResource(R.string.rv_subscriptions), onOpen)
        Row(Modifier.padding(horizontal = RvSpace.rowPadding), verticalAlignment = Alignment.Bottom) {
            RvFigureText(format.bytes(subscriptions.sumOf { it.leftBytes }).figure(), Rv.type.cardValue, Rv.type.cardUnit, Modifier.alignByBaseline())
            Spacer(Modifier.width(RvSpace.tight * 2))
            RvText(stringResource(R.string.rv_remaining), Rv.type.cardUnit, Modifier.alignByBaseline(), color = Rv.colors.textSecondary)
        }
        Spacer(Modifier.height(RvSpace.tight))
        subscriptions.forEach { RvSubscriptionRow(it, nowEpochMs) }
    }
}

/**
 * 最快節點 in the style of Revolut's Watchlist: its header opens 節點 ([onOpen]); a row per node with its flag (the
 * [currentId] node's with the check), its name, its protocol and region, and its latency over its [RvDelta].
 */
@Composable
internal fun RvFastestCard(nodes: List<RvNode>, currentId: String, onOpen: () -> Unit) {
    RvCard {
        RvCardHeader(stringResource(R.string.rv_fastest_nodes), onOpen)
        // A new test re-sorts the rows: each moves to its new place, keyed by its node.
        nodes.forEachIndexed { order, node ->
            key(node.id) {
                RvRow({ RvFlag(node.place.flag, RvSize.rowDisc, current = node.id == currentId) }, node.name, node.protocol + " · " + stringResource(node.place.label), Modifier.rvMoves(), titleKeepWords = false) {
                    RvLatencyChange(node.latencyMs, node.changeMs, order = order)
                }
            }
        }
    }
}

/** The chart takes the card's right half. */
private const val CHART_SHARE = 0.5f
