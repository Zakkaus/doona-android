package dev.doona.android.designsystem.rv

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke

/** One part of [RvRing]: its colour, the lighter step of it for what is left, how much is used and how much there is. */
@Immutable
internal class RvRingSegment(val color: Color, val rest: Color, val used: Long, val total: Long)

/**
 * Revolut's 'Total assets' ring (web `Donut`): an 8 dp ring with butt ends and a segment per [segments] entry, each as
 * long as its share of the whole: the used part in its colour, then what is left in the lighter step, every two parts
 * [RvStatsSize.ringGap] apart in the card's colour. With nothing to show it is a full ring on the track, as Revolut
 * draws a single category. 訂閱流量 draws a subscription per segment.
 */
@Composable
internal fun RvRing(segments: List<RvRingSegment>, modifier: Modifier = Modifier) {
    val track = Rv.colors.track
    Canvas(modifier.size(RvStatsSize.ring)) {
        val stroke = RvStatsSize.ringStroke.toPx()
        val inset = Offset(stroke / 2, stroke / 2)
        val box = Size(size.width - stroke, size.height - stroke)
        val sum = segments.sumOf { it.total }.toFloat()
        if (sum <= 0f) {
            drawArc(track, 0f, FULL_TURN, false, inset, box, style = Stroke(stroke))
            return@Canvas
        }
        val gap = Math.toDegrees(RvStatsSize.ringGap.toPx() / (box.width / 2).toDouble()).toFloat()
        val arc = { color: Color, from: Float, sweep: Float -> if (sweep > 0f) drawArc(color, from, sweep, false, inset, box, style = Stroke(stroke)) }
        var start = START_ANGLE
        segments.forEach { s ->
            val sweep = FULL_TURN * s.total / sum
            val begin = start + gap / 2
            val usable = (sweep - gap).coerceAtLeast(0f)
            val share = (s.used.toFloat() / s.total).coerceIn(0f, 1f)
            when {
                share >= 1f -> arc(s.color, begin, usable)

                share <= 0f -> arc(s.rest, begin, usable)

                else -> {
                    // The used part and the rest each give up half a gap where they meet.
                    val used = usable * share
                    arc(s.color, begin, used - gap / 2)
                    arc(s.rest, begin + used + gap / 2, usable - used - gap / 2)
                }
            }
            start += sweep
        }
    }
}

private const val FULL_TURN = 360f
private const val START_ANGLE = -90f
