package dev.doona.android.designsystem.rv

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.util.fastForEach
import kotlin.math.floor

/** [RvLandMask] decoded once: true where a cell holds land, less every piece of land too small to read as one (N-09). */
private val land: List<BooleanArray> by lazy {
    withoutSpecks(RvLandMask.rows.map { row -> BooleanArray(RvLandMask.COLUMNS) { col -> (row[col / BITS].digitToInt(HEX) shr (BITS - 1 - col % BITS)) and 1 == 1 } })
}

/**
 * [cells] less every piece of land of fewer than [MIN_LAND] cells, touching ones counted together (diagonals too, and
 * across the antimeridian): one or two dots alone read as a stray mark, not land (N-03, N-09).
 */
private fun withoutSpecks(cells: List<BooleanArray>): List<BooleanArray> {
    val seen = cells.map { BooleanArray(it.size) }
    val kept = cells.map { it.copyOf() }
    for (row in cells.indices) {
        for (col in 0 until RvLandMask.COLUMNS) {
            if (!cells[row][col] || seen[row][col]) continue
            val piece = mutableListOf(row to col)
            seen[row][col] = true
            var next = 0
            while (next < piece.size) {
                val (r, c) = piece[next++]
                for (dr in -1..1) {
                    for (dc in -1..1) {
                        val nr = r + dr
                        val nc = Math.floorMod(c + dc, RvLandMask.COLUMNS)
                        if (nr in cells.indices && cells[nr][nc] && !seen[nr][nc]) {
                            seen[nr][nc] = true
                            piece += nr to nc
                        }
                    }
                }
            }
            if (piece.size < MIN_LAND) piece.forEach { (r, c) -> kept[r][c] = false }
        }
    }
    return kept
}

/** One band of latitude on screen: its height and the dots on it. */
private class DotRow(val y: Float, val dots: List<Offset>)

/**
 * Revolut's dot texture (#07, #08) drawn as a world map behind the content: a dot on every land cell, in [color],
 * fading out toward the bottom so the round actions sit on the plain page. The map is centred on [focus]'s cell (a node's
 * city, or its country's centroid), which lands [focusTop] from the top; the marker, one brighter dot and a soft ring,
 * sits where [focus] falls inside that cell ([rvMarkerAt]), so two cities of one cell are marked apart. With no focus
 * it centres on East Asia and marks nothing. With [fadeTop] (the status bar), no dot is drawn above it, so none sits on the clock (S5-03),
 * and they fade in over [RvDots.topFade] under it. Inside [clear] (the hero's text, in this element's coordinates) grown by
 * [RvDots.clearPad] they are kept faint, so no dot runs through the figures (S5-02).
 */
internal fun Modifier.rvDotMap(focus: RvSpot?, color: Color, focusTop: Dp, clear: Rect? = null, fadeTop: Dp? = null, live: RvDotMotion = RV_STILL): Modifier = drawWithCache {
    val pitch = RvDots.pitch.toPx()
    val centre = Offset(size.width / 2f, focusTop.toPx())
    val rows = dotRows(live.lat?.invoke() ?: focus?.lat ?: RV_MAP_REST_LAT, live.lon?.invoke() ?: focus?.lon ?: RV_MAP_REST_LON, centre, pitch, size.height)
    val zone = clear?.inflate(RvDots.clearPad.toPx())
    val topFade = RvDots.topFade.toPx()
    val fadeFrom = fadeTop?.toPx()
    // Split once here, not on each frame of a stretch: the dots kept faint under the hero's text, and the rest.
    val bands = rows.map { row ->
        val inZone = zone != null && row.y in zone.top..zone.bottom
        val (faint, full) = if (inZone) row.dots.partition { it.x in zone.left..zone.right } else emptyList<Offset>() to row.dots
        DotBand(row.y, full, faint)
    }
    onDrawBehind {
        val art = live.art()
        // Read here, so a turn of colour redraws the map and lays nothing out.
        val ink = live.tint?.invoke() ?: color
        // P2 stretches the map downward from its top: each band moves down in proportion to its height.
        val stretch = live.stretch() / size.height
        bands.fastForEach { band ->
            val bottom = ((RvDots.FADE_END - band.y / size.height) / (RvDots.FADE_END - RvDots.FADE_START)).coerceIn(0f, 1f)
            val top = fadeFrom?.let { ((band.y - it) / topFade).coerceIn(0f, 1f) } ?: 1f
            val alpha = RvDots.ALPHA * bottom * top * art
            if (alpha <= 0f) return@fastForEach
            translate(top = band.y * stretch) {
                drawPoints(band.full, PointMode.Points, ink.copy(alpha = alpha), RvDots.dot.toPx(), StrokeCap.Round)
                drawPoints(band.faint, PointMode.Points, ink.copy(alpha = minOf(alpha, RvDots.CLEAR_ALPHA)), RvDots.dot.toPx(), StrokeCap.Round)
            }
        }
        if (focus != null) marker(rvMarkerAt(focus, centre, pitch).let { it.copy(y = it.y * (1 + stretch)) }, ink, art)
    }
}

/**
 * The marker's pulse for the [rvDotMap] with the same [focus], [focusTop] and [live]: given an element of its own over
 * the map (in a layer of its own), a frame of the loop redraws the ring alone, not the map and what lies on it. It
 * reads nothing else while the pulse is at 0.
 */
internal fun Modifier.rvDotPulse(focus: RvSpot?, color: Color, focusTop: Dp, live: RvDotMotion): Modifier = drawBehind {
    val phase = live.pulse()
    if (focus == null || phase <= 0f) return@drawBehind
    val stretch = live.stretch() / size.height
    val at = rvMarkerAt(focus, Offset(size.width / 2f, focusTop.toPx()), RvDots.pitch.toPx())
    pulse(at.copy(y = at.y * (1 + stretch)), live.tint?.invoke() ?: color, live.art(), phase)
}

/** A band of the map: its [y], its dots drawn [full], and those kept [faint] under the hero's text. */
private class DotBand(val y: Float, val full: List<Offset>, val faint: List<Offset>)

/** The land dots of every band between the top and [height], placed so the cell at [lat], [lon] sits on [centre]. */
private fun dotRows(lat: Float, lon: Float, centre: Offset, pitch: Float, height: Float): List<DotRow> {
    val focusCol = column(lon)
    val focusRow = row(lat)
    val reach = (centre.x / pitch).toInt() + 1
    return (0 until RvLandMask.ROWS).mapNotNull { row ->
        val y = centre.y + (row - focusRow) * pitch
        if (y < -pitch || y > height) return@mapNotNull null
        // Columns wrap at the antimeridian, so a node near it still has land on both sides.
        val dots = (-reach..reach).filter { land[row][Math.floorMod(focusCol + it, RvLandMask.COLUMNS)] }.map { Offset(centre.x + it * pitch, y) }
        DotRow(y, dots)
    }
}

/** The map's column of [lon], counted from 180 degrees west; any whole turn off it gives a column as many turns off. */
private fun column(lon: Float): Int = floor((lon + DEGREES_WEST) / RvLandMask.CELL).toInt()

/** The map's row of [lat], counted from [RvLandMask.NORTH] down. */
private fun row(lat: Float): Int = floor((RvLandMask.NORTH - lat) / RvLandMask.CELL).toInt()

/**
 * Where [rvDotMap] draws the dot of the cell at [lat], [lon] when the map is centred on [centreLat], [centreLon] at
 * [centre], its dots [pitch] apart: the columns wrap at the antimeridian, so the cell is taken the short way round.
 */
internal fun rvMapSpot(centreLat: Float, centreLon: Float, lat: Float, lon: Float, centre: Offset, pitch: Float): Offset {
    val half = RvLandMask.COLUMNS / 2
    val columns = Math.floorMod(column(lon) - column(centreLon) + half, RvLandMask.COLUMNS) - half
    return Offset(centre.x + columns * pitch, centre.y + (row(lat) - row(centreLat)) * pitch)
}

/**
 * Where [rvDotMap] marks [spot] when the map is centred on its cell at [centre], dots [pitch] apart: the cell's dot stands
 * for the cell's middle, so the marker sits off it by where [spot] falls inside the cell, less than half a pitch each
 * way. The land lookup stays on the 2-degree grid; only the marker takes the fractional place.
 */
internal fun rvMarkerAt(spot: RvSpot, centre: Offset, pitch: Float): Offset {
    val col = (spot.lon + DEGREES_WEST) / RvLandMask.CELL
    val row = (RvLandMask.NORTH - spot.lat) / RvLandMask.CELL
    return Offset(centre.x + (col - floor(col) - HALF) * pitch, centre.y + (row - floor(row) - HALF) * pitch)
}

/** Whether [rvDotMap] draws a land dot in the cell at [lat], [lon]. */
internal fun rvMapLand(lat: Float, lon: Float): Boolean = land.getOrNull(row(lat))?.get(Math.floorMod(column(lon), RvLandMask.COLUMNS)) == true

/**
 * What moves on the dot map: [art] scales its alpha (a tab's hero fade), [stretch] pulls it that many pixels longer from
 * its top (P2), [pulse] (0 to 1, 0 none) runs a copy of the marker's ring out to the glow's edge while connected,
 * [lat] and [lon] centre the map where a node switch has panned it so far (null: on the focus, or at rest without one),
 * and [tint] is its colour so far while it turns to a new one (null: the map's own).
 */
internal class RvDotMotion(
    val art: () -> Float = { 1f },
    val stretch: () -> Float = { 0f },
    val pulse: () -> Float = { 0f },
    val lat: (() -> Float)? = null,
    val lon: (() -> Float)? = null,
    val tint: (() -> Color)? = null,
) {
    /** The same motion, its colour read from [tint] as it turns. */
    fun tinted(tint: () -> Color) = RvDotMotion(art, stretch, pulse, lat, lon, tint)
}

private val RV_STILL = RvDotMotion()

/** The marker's ring again, [phase] of the way out to the glow's edge and fading as it goes; nothing at 0. */
private fun DrawScope.pulse(at: Offset, color: Color, art: Float, phase: Float) {
    if (phase <= 0f || phase >= 1f) return
    val radius = RvDots.ring.toPx() + (RvDots.glow.toPx() - RvDots.ring.toPx()) * phase
    drawCircle(color.copy(alpha = RvDots.RING_ALPHA * (1 - phase) * art), radius, at, style = Stroke(RvDots.ringStroke.toPx()))
}

/**
 * The selected node's place: a soft glow, a thin ring and one bright dot, all in the map's colour, at [art]'s alpha.
 */
private fun DrawScope.marker(at: Offset, color: Color, art: Float) {
    val glow = RvDots.glow.toPx()
    drawCircle(Brush.radialGradient(listOf(color.copy(alpha = RvDots.GLOW_ALPHA * art), color.copy(alpha = 0f)), at, glow), glow, at) // literal: the glow's transparent end
    drawCircle(color.copy(alpha = RvDots.RING_ALPHA * art), RvDots.ring.toPx(), at, style = Stroke(RvDots.ringStroke.toPx()))
    drawCircle(color, RvDots.marker.toPx() / 2f, at, alpha = art)
}

/** Land of fewer cells than this is left out of the map. */
private const val MIN_LAND = 3
private const val BITS = 4
private const val HEX = 16
private const val DEGREES_WEST = 180f
private const val HALF = 0.5f

/** Where the map centres with no node, at rest: East Asia, where the sample's nodes are. */
internal const val RV_MAP_REST_LAT = 30f
internal const val RV_MAP_REST_LON = 115f
