package dev.doona.android.prototype.rv

import android.content.Context
import android.text.format.Formatter
import androidx.annotation.StringRes
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import dev.doona.android.R
import dev.doona.android.feature.NOT_WIRED
import kotlin.math.abs

/**
 * 今日用量 in the style of Revolut's 'Spent this month' (measured §10.2, study §5): the title, today's total and a
 * comparison with yesterday at the start; download and upload as stepped cumulative areas stacked in the right half, so
 * the top edge is the total; and the legend at the bottom start. The comparison has no colour: traffic has no good or
 * bad direction.
 */
@Composable
internal fun RvUsageCard(day: RvUsageDay) {
    val context = LocalContext.current
    val colors = Rv.colors
    val type = Rv.type
    val change = day.total - day.yesterdayByNow
    val compare = if (change < 0) R.string.rv_less_than_yesterday else R.string.rv_more_than_yesterday
    RvCard(Modifier.height(RvUsageSize.card)) {
        Box(Modifier.fillMaxSize().padding(RvUsageSize.pad)) {
            val legend = with(LocalDensity.current) { type.tileCaption.lineHeight.toDp() }
            UsageChart(day, Modifier.align(Alignment.TopEnd).fillMaxWidth(CHART_SHARE).fillMaxHeight().padding(bottom = RvUsageSize.chartAboveLegend + legend))
            Column {
                RvText(stringResource(R.string.rv_today_usage), type.cardTitle, color = colors.textSecondary)
                Spacer(Modifier.height(RvSpace.tight))
                RvFigureText(figure(context, day.total), type.cardValue, type.cardUnit)
                RvText(stringResource(compare, size(context, abs(change))), type.tileCaption, color = colors.textSecondary)
            }
            Row(Modifier.align(Alignment.BottomStart), horizontalArrangement = Arrangement.spacedBy(RvUsageSize.legendItemGap)) {
                LegendItem(colors.down.line, "${stringResource(R.string.ui_download)} · ${size(context, day.downTotal)}")
                LegendItem(colors.up.line, "${stringResource(R.string.ui_upload)} · ${size(context, day.upTotal)}")
            }
        }
    }
}

@Composable
private fun LegendItem(color: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(RvUsageSize.legendSquare).clip(RoundedCornerShape(RvUsageSize.legendRadius)).background(color))
        Spacer(Modifier.width(RvUsageSize.legendGap))
        RvText(text, Rv.type.tileCaption, color = Rv.colors.textSecondary)
    }
}

/**
 * The day from 00:00 to 24:00 across the canvas and the total × [RvUsageSize.HEADROOM] up it: download's running total as
 * a stepped area, upload's stacked on it, each with a thin line on its top edge, over a hairline grid that fades out
 * toward the start and the bottom. The steps stop at the current time.
 */
@Composable
private fun UsageChart(day: RvUsageDay, modifier: Modifier) {
    val colors = Rv.colors
    Canvas(modifier) {
        fadingGrid(colors.grid)
        val high = day.total * RvUsageSize.HEADROOM
        val chart = StepChart(size.width, size.height, high, day.nowHour)
        val downTop = chart.steps(day.down.runningSum())
        val totalTop = chart.steps(day.down.zip(day.up) { down, up -> down + up }.runningSum())
        drawPath(chart.band(totalTop, downTop), colors.up.area)
        drawPath(chart.band(downTop, null), colors.down.area)
        val stroke = Stroke(RvUsageSize.stroke.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        drawPath(chart.line(downTop), colors.down.line, style = stroke)
        drawPath(chart.line(totalTop), colors.up.line, style = stroke)
    }
}

/** Where an hour and a running total sit on a [width] x [height] canvas whose top is [high] bytes. */
private class StepChart(val width: Float, val height: Float, val high: Float, val now: Float) {
    private fun x(hour: Float) = width * hour / RvUsageSize.HOURS
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

/** A hairline every [RvUsageSize.GRID_HOURS] hours and in [RvUsageSize.GRID_ROWS] rows, fading out to the start and the bottom. */
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

/**
 * 訂閱 in the style of Revolut's Total wealth: the card's own header, the data left on every subscription, and a row
 * for each, with a coloured disc, its use against its quota, its end date and what is left.
 */
@Composable
internal fun RvSubscriptionsCard(subscriptions: List<RvSubscriptionUse>) {
    val colors = Rv.colors
    RvCard {
        RvCardHeader(R.string.rv_subscriptions)
        Row(Modifier.padding(horizontal = RvSpace.rowPadding), verticalAlignment = Alignment.Bottom) {
            RvFigureText(RvFigure(subscriptions.sumOf { it.leftGb }.toString(), GB), Rv.type.cardValue, Rv.type.cardUnit, Modifier.alignByBaseline())
            Spacer(Modifier.width(RvSpace.tight * 2))
            RvText(stringResource(R.string.rv_remaining), Rv.type.cardUnit, Modifier.alignByBaseline(), color = colors.textSecondary)
        }
        Spacer(Modifier.height(RvSpace.tight))
        subscriptions.forEachIndexed { i, sub ->
            val (disc, glyph) = colors.accents[i % colors.accents.size]
            RvRow(
                { AccentDisc(disc, glyph) },
                stringResource(R.string.rv_subscription_name, sub.letter),
                stringResource(R.string.rv_subscription_usage, sub.usedGb, sub.totalGb, sub.ends),
            ) { RvText("${sub.leftGb}$GB", Rv.type.rowValue, align = TextAlign.End) }
        }
        Spacer(Modifier.height(RvSpace.tight * 2))
    }
}

@Composable
private fun AccentDisc(disc: Color, glyph: Color) {
    Box(Modifier.size(RvHomeSize.disc).clip(CircleShape).background(disc), contentAlignment = Alignment.Center) {
        RvGlyph(R.drawable.rv_sym_dns, RvHomeSize.discGlyph, glyph)
    }
}

/**
 * 最快節點 in the style of Revolut's Watchlist: a row per node with its flag, its name, its protocol and region, and its
 * latency over the change since the test before, a falling latency in the good hue and a rising one in the slow hue.
 */
@Composable
internal fun RvFastestCard(nodes: List<RvFastNode>) {
    RvCard {
        RvCardHeader(R.string.rv_fastest_nodes)
        nodes.forEach { node ->
            val place = placeOf(node.name)
            val region = place?.let { stringResource(it.label) }
            RvRow({ RvFlagDisc(place, selected = false) }, withoutFlag(node.name), listOfNotNull(node.protocol, region).joinToString(" · ")) {
                Column(horizontalAlignment = Alignment.End) {
                    RvText(stringResource(R.string.latency_ms, node.latencyMs), Rv.type.rowValue, align = TextAlign.End)
                    RvDelta(node.changeMs)
                }
            }
        }
        Spacer(Modifier.height(RvSpace.tight * 2))
    }
}

/** A change in milliseconds as Revolut writes one: a filled triangle pointing its way, then the amount, in one hue. */
@Composable
private fun RvDelta(changeMs: Int) {
    val color = if (changeMs <= 0) Rv.colors.good else Rv.colors.slow
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(RvHomeSize.deltaGap)) {
        Canvas(Modifier.size(RvHomeSize.delta)) {
            val low = size.height * (1 - TRIANGLE_INSET)
            val high = size.height * TRIANGLE_INSET
            val (base, tip) = if (changeMs <= 0) high to low else low to high
            val path = Path().apply {
                moveTo(0f, base)
                lineTo(size.width, base)
                lineTo(size.width / 2, tip)
                close()
            }
            drawPath(path, color)
            drawPath(path, color, style = Stroke(1f, join = StrokeJoin.Round))
        }
        RvText(stringResource(R.string.latency_ms, abs(changeMs)), Rv.type.rowSub, color = color)
    }
}

/** A home card's own header, as Revolut's `Cards >`: grey semibold text and a chevron; the whole line is one button. */
@Composable
private fun RvCardHeader(@StringRes title: Int) {
    Row(
        Modifier
            .fillMaxWidth()
            .rvPress(RoundedCornerShape(RvSize.cardRadius), Role.Button, NOT_WIRED)
            .padding(horizontal = RvSpace.rowPadding, vertical = RvHomeSize.headerPad),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RvText(stringResource(title), Rv.type.cardTitle, color = Rv.colors.textSecondary)
        RvGlyph(R.drawable.rv_sym_chevron_right, RvHomeSize.headerChevron, Rv.colors.textSecondary)
    }
}

private fun figure(context: Context, bytes: Long): RvFigure {
    val text = size(context, bytes)
    val cut = text.lastIndexOf(' ')
    return if (cut < 0) RvFigure(text, "") else RvFigure(text.substring(0, cut), text.substring(cut))
}

private fun size(context: Context, bytes: Long): String = Formatter.formatShortFileSize(context, bytes)

private fun List<Long>.runningSum(): List<Long> = runningReduce { sum, bytes -> sum + bytes }

/** The chart takes the card's right half. */
private const val CHART_SHARE = 0.5f

/** The delta triangle leaves this share of its box free above and below; the outline rounds its corners. */
private const val TRIANGLE_INSET = 0.1f
private const val GB = " GB"
