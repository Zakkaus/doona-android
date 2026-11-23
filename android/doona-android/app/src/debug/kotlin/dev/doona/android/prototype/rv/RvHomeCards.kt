package dev.doona.android.prototype.rv

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import dev.doona.android.R
import dev.doona.android.feature.NOT_WIRED
import dev.doona.android.prototype.rv.data.RvData
import dev.doona.android.prototype.rv.data.RvDay
import dev.doona.android.prototype.rv.data.RvNode
import dev.doona.android.prototype.rv.data.RvSubscription
import kotlin.math.abs

/**
 * 今日用量 in the style of Revolut's 'Spent this month' (measured §10.2, study §5): the title, today's total and a
 * comparison with yesterday at the start; download and upload as stepped cumulative areas stacked in the right half, so
 * the top edge is the total; and the legend at the bottom start. The comparison has no colour: traffic has no good or
 * bad direction.
 */
@Composable
internal fun RvUsageCard(day: RvDay, nowHour: Float, yesterdayByNow: Long) {
    val format = rememberRvFormat()
    val colors = Rv.colors
    val type = Rv.type
    val change = day.total - yesterdayByNow
    val compare = if (change < 0) R.string.rv_less_than_yesterday else R.string.rv_more_than_yesterday
    RvCard(Modifier.height(RvUsageSize.card)) {
        Box(Modifier.fillMaxSize().padding(RvUsageSize.pad)) {
            val legend = with(LocalDensity.current) { type.tileCaption.lineHeight.toDp() }
            ApprovedUsageChart(day, nowHour, Modifier.align(Alignment.TopEnd).fillMaxWidth(CHART_SHARE).fillMaxHeight().padding(bottom = RvUsageSize.chartAboveLegend + legend))
            Column {
                RvText(stringResource(R.string.rv_today_usage), RvActivityType.cardTitle, color = colors.textSecondary)
                Spacer(Modifier.height(RvSpace.tight))
                RvFigureText(format.bytes(day.total).figure(), type.cardValue, type.cardUnit)
                RvText(stringResource(compare, format.bytes(abs(change)).toString()), type.tileCaption, color = colors.textSecondary)
            }
            RvUsageLegend(day, Modifier.align(Alignment.BottomStart))
        }
    }
}

/** The usage chart's legend: download and upload, each with its line's colour and the day's bytes. */
@Composable
internal fun RvUsageLegend(day: RvDay, modifier: Modifier = Modifier) {
    val format = rememberRvFormat()
    val colors = Rv.colors
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(RvUsageSize.legendItemGap)) {
        LegendItem(colors.down.line, "${stringResource(R.string.ui_download)} · ${format.bytes(day.downTotal)}")
        LegendItem(colors.up.line, "${stringResource(R.string.ui_upload)} · ${format.bytes(day.upTotal)}")
    }
}

@Composable
private fun LegendItem(color: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(RvUsageSize.legendSquare).background(color))
        Spacer(Modifier.width(RvUsageSize.legendGap))
        RvText(text, Rv.type.tileCaption, color = Rv.colors.textSecondary)
    }
}

/**
 * The day from 00:00 to 24:00 across the canvas and the total × [RvUsageSize.HEADROOM] up it: download's running total as
 * a stepped band, upload's stacked on it, both at one alpha in their own hue, each with a thin line on its top edge, over
 * a hairline grid every 4 hours that the steps share. The steps stop at the current time, where a 5 dp dot on a halo
 * marks the total so far (S5-14). 活動's 今日用量 and 統計's 已用流量 both draw the day with it (N-11).
 */
@Composable
internal fun RvUsageChart(day: RvDay, nowHour: Float, modifier: Modifier) {
    val colors = Rv.colors
    Canvas(modifier) {
        // A day with no bytes yet keeps a top of one byte, so every step lies on the bottom rather than at 0 / 0.
        val high = (day.total * RvUsageSize.HEADROOM).coerceAtLeast(1f)
        val chart = StepChart(size.width, size.height, high, nowHour)
        hourGrid(colors.grid, chart)
        val downTop = chart.steps(day.down.runningSum())
        val totalTop = chart.steps(day.down.zip(day.up) { down, up -> down + up }.runningSum())
        drawPath(chart.band(totalTop, downTop), colors.up.band)
        drawPath(chart.band(downTop, null), colors.down.band)
        // Butt ends, so the lines stop where the bands do (S5-15).
        val stroke = Stroke(RvUsageSize.stroke.toPx(), cap = StrokeCap.Butt, join = StrokeJoin.Round)
        drawPath(chart.line(downTop), colors.down.line, style = stroke)
        drawPath(chart.line(totalTop), colors.up.line, style = stroke)
        val now = totalTop.last()
        drawCircle(colors.up.line.copy(alpha = RvAlpha.HALO), RvChartSize.liveHalo.toPx() / 2, now)
        drawCircle(colors.up.line, RvChartSize.liveDot.toPx() / 2, now)
    }
}

/**
 * 活動's 今日用量 chart, as the owner approved it (609336cb): [RvUsageChart]'s steps over a grid that strengthens toward
 * the end of the day and toward its top, both bands at one alpha in their own hue, round line ends, and no mark at the
 * current time. 統計's 已用流量 keeps [RvUsageChart].
 */
@Composable
private fun ApprovedUsageChart(day: RvDay, nowHour: Float, modifier: Modifier) {
    val colors = Rv.colors
    val grid = colors.text.copy(alpha = if (colors.dark) RvActivityTokens.GRID_DARK else RvActivityTokens.GRID_LIGHT)
    val band = if (colors.dark) RvActivityTokens.BAND_DARK else RvActivityTokens.BAND_LIGHT
    Canvas(modifier) {
        fadingGrid(grid)
        val high = (day.total * RvUsageSize.HEADROOM).coerceAtLeast(1f)
        val chart = StepChart(size.width, size.height, high, nowHour)
        val downTop = chart.steps(day.down.runningSum())
        val totalTop = chart.steps(day.down.zip(day.up) { down, up -> down + up }.runningSum())
        drawPath(chart.band(totalTop, downTop), colors.up.line.copy(alpha = band))
        drawPath(chart.band(downTop, null), colors.down.line.copy(alpha = band))
        val stroke = Stroke(RvUsageSize.stroke.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        drawPath(chart.line(downTop), colors.down.line, style = stroke)
        drawPath(chart.line(totalTop), colors.up.line, style = stroke)
    }
}

/**
 * A hairline every [RvUsageSize.GRID_HOURS] hours, stronger toward the day's end, each fading out downward; and
 * [RvUsageSize.GRID_ROWS] rows, each fading in toward the day's end, weaker lower down.
 */
private fun DrawScope.fadingGrid(color: Color) {
    val hairline = 1f
    val columns = RvUsageSize.HOURS / RvUsageSize.GRID_HOURS
    for (i in 0..columns) {
        val x = (size.width - hairline) * i / columns + hairline / 2
        val ink = color.copy(alpha = color.alpha * i / columns)
        drawLine(Brush.verticalGradient(listOf(ink, Color.Transparent)), Offset(x, 0f), Offset(x, size.height), hairline)
    }
    for (j in 0 until RvUsageSize.GRID_ROWS) {
        val y = size.height * j / RvUsageSize.GRID_ROWS + hairline / 2
        val ink = color.copy(alpha = color.alpha * (1 - j.toFloat() / RvUsageSize.GRID_ROWS))
        drawLine(Brush.horizontalGradient(listOf(Color.Transparent, ink)), Offset(0f, y), Offset(size.width, y), hairline)
    }
}

/** Where an hour and a running total sit on a [width] x [height] canvas whose top is [high] bytes. */
private class StepChart(val width: Float, val height: Float, val high: Float, val now: Float) {
    fun x(hour: Float) = width * hour / RvUsageSize.HOURS
    private fun y(bytes: Long) = height * (1 - bytes / high)

    /** The top edge of a running total: level through each hour, rising at the hour's start, ending now. */
    fun steps(totals: List<Long>): List<Offset> = buildList {
        add(Offset(x(0f), y(0)))
        totals.forEachIndexed { hour, total ->
            add(Offset(x(hour.toFloat()), y(total)))
            add(Offset(x(minOf(hour + 1f, now)), y(total)))
        }
    }

    fun line(points: List<Offset>) = Path().apply {
        moveTo(points[0].x, points[0].y)
        points.drop(1).forEach { lineTo(it.x, it.y) }
    }

    /** The area between [top] and [bottom], or down to the canvas's bottom when [bottom] is null. */
    fun band(top: List<Offset>, bottom: List<Offset>?) = line(top).apply {
        if (bottom == null) {
            lineTo(top.last().x, height)
            lineTo(top.first().x, height)
        } else {
            bottom.asReversed().forEach { lineTo(it.x, it.y) }
        }
        close()
    }
}

/**
 * A hairline at every [RvUsageSize.GRID_HOURS] hours, where [chart] puts that hour, so the steps rise on the grid, each
 * fading out toward the bottom; and [RvUsageSize.GRID_ROWS] rows across the whole width.
 */
private fun DrawScope.hourGrid(color: Color, chart: StepChart) {
    val hairline = 1f
    for (hour in 0..RvUsageSize.HOURS step RvUsageSize.GRID_HOURS) {
        val x = chart.x(hour.toFloat()).coerceIn(hairline / 2, size.width - hairline / 2)
        drawLine(Brush.verticalGradient(listOf(color, Color.Transparent)), Offset(x, 0f), Offset(x, size.height), hairline)
    }
    for (j in 0 until RvUsageSize.GRID_ROWS) {
        val y = size.height * j / RvUsageSize.GRID_ROWS + hairline / 2
        drawLine(color, Offset(0f, y), Offset(size.width, y), hairline)
    }
}

/**
 * 訂閱 in the style of Revolut's Total wealth: the card's own header, the data left on every subscription, and a row
 * for each, with a neutral disc (colour means a direction on this page, L-17), its use against its quota, its end date
 * and what is left. Its header opens 訂閱 ([onOpen]).
 */
@Composable
internal fun RvSubscriptionsCard(subscriptions: List<RvSubscription>, onOpen: () -> Unit = NOT_WIRED) {
    val format = rememberRvFormat()
    RvCard {
        RvCardHeader(stringResource(R.string.rv_subscriptions), onOpen)
        Row(Modifier.padding(horizontal = RvSpace.rowPadding), verticalAlignment = Alignment.Bottom) {
            RvFigureText(format.bytes(subscriptions.sumOf { it.leftBytes }).figure(), Rv.type.cardValue, Rv.type.cardUnit, Modifier.alignByBaseline())
            Spacer(Modifier.width(RvSpace.tight * 2))
            RvText(stringResource(R.string.rv_remaining), Rv.type.cardUnit, Modifier.alignByBaseline(), color = Rv.colors.textSecondary)
        }
        Spacer(Modifier.height(RvSpace.tight))
        subscriptions.forEach { RvSubscriptionRow(it) }
    }
}

/** A subscription's row: a neutral disc, its name over its use against its quota and its end date, and what is left. */
@Composable
internal fun RvSubscriptionRow(sub: RvSubscription) {
    val format = rememberRvFormat()
    val total = format.bytes(sub.totalBytes)
    RvRow(
        { RvIconDisc(R.drawable.rv_sym_rss_feed) },
        sub.name,
        stringResource(R.string.rv_subscription_usage, format.bytesIn(sub.usedBytes, total), total.toString(), sub.expiresAtEpochMs?.let { format.expiry(it, RvData.nowEpochMs) } ?: RV_NO_VALUE),
    ) { RvText(format.bytes(sub.leftBytes).toString(), Rv.type.rowValue, align = TextAlign.End) }
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
                RvRow({ RvFlag(node.place.name, RvSize.rowDisc, current = node.id == currentId) }, node.name, node.protocol + " · " + stringResource(node.place.label), Modifier.rvMoves()) {
                    RvLatencyChange(node.latencyMs, node.changeMs, order = order)
                }
            }
        }
    }
}

private fun List<Long>.runningSum(): List<Long> = runningReduce { sum, bytes -> sum + bytes }

/** The chart takes the card's right half. */
private const val CHART_SHARE = 0.5f
