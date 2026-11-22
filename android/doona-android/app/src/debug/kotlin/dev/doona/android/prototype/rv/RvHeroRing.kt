package dev.doona.android.prototype.rv

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.max
import dev.doona.android.R
import dev.doona.android.engine.Status
import dev.doona.android.feature.activity.ActivityUiState
import dev.doona.android.prototype.rv.data.RvMinuteShare
import dev.doona.android.prototype.rv.data.RvRouteMode
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * V3 · Outbound ring: the duck in the middle of a ring, and on the ring each outbound that carried the last minute, a
 * flag (or 直連's disc) whose area follows its share, joined to the duck by a thin spoke in its route's colour and
 * labelled beside it; the figure and the actions follow under the ring. A node opens its group. 全域 has one node;
 * disconnected, the duck stands alone, greyed.
 */
@Composable
internal fun RvRingHero(state: ActivityUiState, mode: RvRouteMode, routes: RvActivityRoutes, insets: WindowInsets, actions: @Composable () -> Unit) {
    RvHeroColumn(insets) {
        Spacer(Modifier.height(rvBarHeight() + RvHeroSize.ringAbove))
        OutboundRing(rvMinuteShares(state, mode), mode, state.status is Status.Running, routes)
        Spacer(Modifier.height(RvHeroSize.ringBelow))
        RvHeroFigure(state, mode)
        Spacer(Modifier.height(RvSpace.heroToActions))
        actions()
    }
}

/** Where a node sits on the ring, as an angle in radians, and how wide it is. */
private class RingSpot(val angle: Float, val size: Dp)

@Composable
private fun OutboundRing(shares: List<RvMinuteShare>, mode: RvRouteMode, running: Boolean, routes: RvActivityRoutes) {
    val colors = rvShareColors(shares)
    val grid = Rv.colors.grid
    val spots = shares.mapIndexed { i, share -> spotOf(i, shares.size, share.percent) }
    BoxWithConstraints(Modifier.fillMaxWidth().height(RvHeroSize.ringBlock)) {
        val centre = DpOffset(maxWidth / 2, maxHeight / 2)
        Canvas(Modifier.matchParentSize()) {
            if (shares.isEmpty()) return@Canvas
            val c = Offset(centre.x.toPx(), centre.y.toPx())
            val radius = RvHeroSize.ringRadius.toPx()
            drawCircle(grid, radius, c, style = Stroke(1f))
            spots.forEachIndexed { i, spot ->
                val way = Offset(cos(spot.angle), sin(spot.angle))
                val from = c + way * (RvHeroSize.duck.toPx() / 2)
                val to = c + way * (radius - spot.size.toPx() / 2 - RvHeroSize.nodeRing.toPx())
                drawLine(colors[i], from, to, RvHeroSize.spoke.toPx())
            }
        }
        RingDuck(running, Modifier.offset(centre.x - RvHeroSize.duck / 2, centre.y - RvHeroSize.duck / 2))
        shares.forEachIndexed { i, share -> RingNode(share, spots[i], centre, maxWidth, colors[i], mode, routes.open(share)) }
    }
}

/**
 * The [i]th of [count] nodes, spread evenly from the upper left, where the main node always sits, so none comes under the
 * top bar and the labels go out to the sides. Its disc's area follows its [percent] of the minute.
 */
private fun spotOf(i: Int, count: Int, percent: Int): RingSpot {
    val angle = Math.toRadians(UPPER_LEFT + FULL_TURN * i / count).toFloat()
    val size = max(RvHeroSize.nodeMin, RvHeroSize.nodeMax * sqrt(percent / ALL))
    return RingSpot(angle, size)
}

@Composable
private fun RingDuck(running: Boolean, modifier: Modifier) {
    val grey = if (running) null else ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) })
    Box(modifier.size(RvHeroSize.duck).clip(CircleShape).background(Rv.colors.discOnHeader), contentAlignment = Alignment.Center) {
        Image(
            painterResource(R.drawable.ic_duck),
            contentDescription = null,
            Modifier.size(RvHeroSize.duckGlyph),
            alpha = if (running) 1f else RvAlpha.GREYED,
            colorFilter = grey,
        )
    }
}

/**
 * A node on the ring: its mark on a ring in its route's [color], a 48 dp target, and its label on the outer side, as wide
 * as the room left to the gutter on a [width] wide hero.
 */
@Composable
private fun RingNode(share: RvMinuteShare, spot: RingSpot, centre: DpOffset, width: Dp, color: Color, mode: RvRouteMode, onClick: () -> Unit) {
    val format = rememberRvFormat()
    val x = centre.x + RvHeroSize.ringRadius * cos(spot.angle)
    val y = centre.y + RvHeroSize.ringRadius * sin(spot.angle)
    val ringed = spot.size + RvHeroSize.nodeRing * 2
    val target = max(ringed, RvSize.touch)
    val role = share.role(mode)
    val percent = format.percent(share.percent / ALL.toDouble())
    val detail = listOfNotNull(role, percent).joinToString(" · ")
    val name = share.name()
    Box(
        Modifier
            .offset(x - target / 2, y - target / 2)
            .size(target)
            .clip(CircleShape)
            .rvPress(CircleShape, Role.Button, onClick)
            .semantics { contentDescription = "$name $detail" },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(ringed).clip(CircleShape).background(color), contentAlignment = Alignment.Center) {
            RvShareMark(share, spot.size, ring = Rv.colors.page, fill = Rv.colors.discOnHeader)
        }
    }
    // Labels go outward: left of a node on the left half, right of the others.
    val left = cos(spot.angle) < -SIDE
    val edge = ringed / 2 + RvHeroSize.nodeLabelGap
    val room = if (left) x - edge - RvSpace.gutter else width - RvSpace.gutter - x - edge
    val labelX = if (left) x - edge - room else x + edge
    Box(Modifier.offset(labelX, y - RvSize.touch / 2).width(room).height(RvSize.touch), contentAlignment = if (left) Alignment.CenterEnd else Alignment.CenterStart) {
        val align = if (left) TextAlign.End else TextAlign.Start
        Column(horizontalAlignment = if (left) Alignment.End else Alignment.Start) {
            RvText(name, Rv.type.pill, align = align)
            RvText(detail, Rv.type.tileCaption, color = Rv.colors.textSecondary, align = align)
        }
    }
}

private const val ALL = 100f
private const val UPPER_LEFT = -150.0
private const val FULL_TURN = 360.0

/** A node this far left of the duck (as a cosine) labels to its left. */
private const val SIDE = 0.1f
