package dev.doona.android.prototype.rv

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import dev.doona.android.R
import dev.doona.android.prototype.rv.data.RvData
import dev.doona.android.prototype.rv.data.RvOutboundPart
import dev.doona.android.prototype.rv.data.RvRouteMode

/**
 * C4 · Route map: a small Sankey drawn in the card. On the left what matches, in the profile's order (the rule groups,
 * 其他, then 其餘, which no rule took); on the right where it goes, the main node first; each rule joined to its place by
 * a band as thick as today's bytes. The main node's band, from 其餘, crosses the others in the accent and is drawn on top.
 * A place opens its group. In 全域 one band runs from 全部流量 to the node.
 */
@Composable
internal fun RvRouteMap(parts: List<RvOutboundPart>, mode: RvRouteMode, routes: RvActivityRoutes) {
    val colors = rvPartColors(parts)
    // Left: the rules in order and the catch-all last; right: the parts as they come, the main node first.
    val left = if (mode == RvRouteMode.Rule) parts.indices.drop(1) + 0 else parts.indices.toList()
    val heaviest = parts.maxOf { it.bytes }.coerceAtLeast(1L)
    val thickness = parts.map { RvOutboundSize.routeThin + (RvOutboundSize.routeThick - RvOutboundSize.routeThin) * (it.bytes.toFloat() / heaviest) }
    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = RvSpace.rowPadding).height(RvOutboundSize.routeSlot * parts.size)) {
        val from = RvOutboundSize.routeLabel + RvOutboundSize.routeGap
        val to = maxWidth - RvOutboundSize.routePlace - RvOutboundSize.routeGap
        Canvas(Modifier.matchParentSize()) {
            // Main last, so its band lies on top where it crosses.
            for (i in parts.indices.reversed()) {
                val alpha = if (parts[i].main) RvAlpha.ROUTE_MAIN else RvAlpha.ROUTE_BAND
                band(slotCentre(left.indexOf(i)), slotCentre(i), from.toPx(), to.toPx(), thickness[i].toPx(), colors[i], alpha)
            }
        }
        Column(Modifier.width(RvOutboundSize.routeLabel)) {
            left.forEach { i -> RuleLabel(ruleName(parts[i], mode)) }
        }
        Column(Modifier.offset(x = maxWidth - RvOutboundSize.routePlace).width(RvOutboundSize.routePlace)) {
            parts.forEach { PlaceItem(it, mode, routes.open(it)) }
        }
    }
}

/** What a part matches: its rule group, 其他 for the folded ones, 其餘 for the main node in 規則, all traffic in 全域. */
@Composable
private fun ruleName(part: RvOutboundPart, mode: RvRouteMode): String = when {
    part.other -> stringResource(R.string.rv_outbound_other)
    part.group != null -> stringResource(part.group)
    mode == RvRouteMode.Rule -> stringResource(R.string.rv_route_rest)
    else -> stringResource(R.string.rv_route_all)
}

@Composable
private fun RuleLabel(text: String) {
    Box(Modifier.fillMaxWidth().height(RvOutboundSize.routeSlot), contentAlignment = Alignment.CenterEnd) {
        RvText(text, Rv.type.pill, align = TextAlign.End)
    }
}

/** A place on the right: its mark, its short name (the main node's with 主節點), and today's bytes; it opens its group. */
@Composable
private fun PlaceItem(part: RvOutboundPart, mode: RvRouteMode, onClick: () -> Unit) {
    val format = rememberRvFormat()
    val node = part.nodeId?.let { RvData.node(it) }
    val name = when {
        part.main && node != null -> listOfNotNull(node.short, part.role(mode)).joinToString(" · ")
        else -> part.place()
    }
    Row(
        Modifier
            .fillMaxWidth()
            .height(RvOutboundSize.routeSlot)
            .rvPress(RoundedCornerShape(RvOutboundSize.tileRadius), Role.Button, onClick)
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(RvOutboundSize.routeGap),
    ) {
        RvPartMark(part, RvOutboundSize.routeFlag)
        Column {
            RvText(name, Rv.type.pill)
            RvText(format.bytes(part.bytes).toString(), Rv.type.tileCaption, color = Rv.colors.textSecondary)
        }
    }
}

/** The middle of the [slot]th slot from the top, in px. */
private fun DrawScope.slotCentre(slot: Int): Float = (slot + HALF) * RvOutboundSize.routeSlot.toPx()

/**
 * A band [thick] px wide from ([x0], [y0]) to ([x1], [y1]), easing across the middle as Sankey links do, in [color] at
 * [alpha], with a solid end bar at each side.
 */
private fun DrawScope.band(y0: Float, y1: Float, x0: Float, x1: Float, thick: Float, color: Color, alpha: Float) {
    val mid = (x0 + x1) / 2
    val half = thick / 2
    val path = Path().apply {
        moveTo(x0, y0 - half)
        cubicTo(mid, y0 - half, mid, y1 - half, x1, y1 - half)
        lineTo(x1, y1 + half)
        cubicTo(mid, y1 + half, mid, y0 + half, x0, y0 + half)
        close()
    }
    drawPath(path, color.copy(alpha = color.alpha * alpha))
    val end = RvOutboundSize.routeEnd.toPx()
    drawRect(color, Offset(x0 - end, y0 - half), Size(end, thick))
    drawRect(color, Offset(x1, y1 - half), Size(end, thick))
}

private const val HALF = 0.5f
