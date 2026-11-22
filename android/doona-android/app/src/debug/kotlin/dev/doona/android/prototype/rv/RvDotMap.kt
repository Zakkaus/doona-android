package dev.doona.android.prototype.rv

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import dev.doona.android.prototype.rv.data.RvPlace
import kotlin.math.floor

/** [RvLandMask] decoded once: true where a cell holds land. */
private val land: List<BooleanArray> by lazy {
    RvLandMask.rows.map { row -> BooleanArray(RvLandMask.COLUMNS) { col -> (row[col / BITS].digitToInt(HEX) shr (BITS - 1 - col % BITS)) and 1 == 1 } }
}

/** One band of latitude on screen: its height and the dots on it. */
private class DotRow(val y: Float, val dots: List<Offset>)

/**
 * Revolut's dot texture (#07, #08) drawn as a world map behind the content: a dot on every land cell, in [color],
 * fading out toward the bottom so the round actions sit on the plain page. The map is centred on [focus]'s cell, which
 * lands [focusTop] from the top and is marked with one brighter dot and a soft ring; with no focus it centres on East
 * Asia and marks nothing.
 */
internal fun Modifier.rvDotMap(focus: RvPlace?, color: Color, focusTop: Dp): Modifier = drawWithCache {
    val pitch = RvDots.pitch.toPx()
    val centre = Offset(size.width / 2f, focusTop.toPx())
    val rows = dotRows(focus?.lat ?: DEFAULT_LAT, focus?.lon ?: DEFAULT_LON, centre, pitch, size.height)
    onDrawBehind {
        for (row in rows) {
            val fade = ((RvDots.FADE_END - row.y / size.height) / (RvDots.FADE_END - RvDots.FADE_START)).coerceIn(0f, 1f)
            if (fade > 0f) {
                drawPoints(row.dots, PointMode.Points, color.copy(alpha = RvDots.ALPHA * fade), RvDots.dot.toPx(), StrokeCap.Round)
            }
        }
        if (focus != null) marker(centre, color)
    }
}

/** The land dots of every band between the top and [height], placed so the cell at [lat], [lon] sits on [centre]. */
private fun dotRows(lat: Float, lon: Float, centre: Offset, pitch: Float, height: Float): List<DotRow> {
    val focusCol = floor((lon + DEGREES_WEST) / RvLandMask.CELL).toInt()
    val focusRow = floor((RvLandMask.NORTH - lat) / RvLandMask.CELL).toInt()
    val reach = (centre.x / pitch).toInt() + 1
    return (0 until RvLandMask.ROWS).mapNotNull { row ->
        val y = centre.y + (row - focusRow) * pitch
        if (y < -pitch || y > height) return@mapNotNull null
        // Columns wrap at the antimeridian, so a node near it still has land on both sides.
        val dots = (-reach..reach).filter { land[row][Math.floorMod(focusCol + it, RvLandMask.COLUMNS)] }.map { Offset(centre.x + it * pitch, y) }
        DotRow(y, dots)
    }
}

/** The selected node's place: a soft glow, a thin ring and one bright dot, all in the map's colour. */
private fun DrawScope.marker(at: Offset, color: Color) {
    val glow = RvDots.glow.toPx()
    drawCircle(Brush.radialGradient(listOf(color.copy(alpha = RvDots.GLOW_ALPHA), color.copy(alpha = 0f)), at, glow), glow, at)
    drawCircle(color.copy(alpha = RvDots.RING_ALPHA), RvDots.ring.toPx(), at, style = Stroke(RvDots.ringStroke.toPx()))
    drawCircle(color, RvDots.marker.toPx() / 2f, at)
}

private const val BITS = 4
private const val HEX = 16
private const val DEGREES_WEST = 180f

/** Where the map centres with no node: East Asia, where the sample's nodes are. */
private const val DEFAULT_LAT = 30f
private const val DEFAULT_LON = 115f
