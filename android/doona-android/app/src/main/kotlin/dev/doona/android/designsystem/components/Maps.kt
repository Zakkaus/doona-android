package dev.doona.android.designsystem.components

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.doona.android.designsystem.theme.DoonaDotMap
import dev.doona.android.designsystem.theme.DoonaTheme
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.tan

/** A place on a map: its country's ISO 3166-1 alpha-2 [code] (which picks its shape) and its centroid in degrees. */
@Immutable
class DoonaMapPlace(val code: String, val lat: Float, val lon: Float)

/** [LandMask] decoded once: true where a cell holds land, less every piece of land too small to read as one (N-09). */
private val land: List<BooleanArray> by lazy {
    withoutSpecks(LandMask.rows.map { row -> BooleanArray(LandMask.COLUMNS) { col -> (row[col / BITS].digitToInt(HEX) shr (BITS - 1 - col % BITS)) and 1 == 1 } })
}

/**
 * [cells] less every piece of land of fewer than [MIN_LAND] cells, touching ones counted together (diagonals too, and
 * across the antimeridian): one or two dots alone read as a stray mark, not land (N-03, N-09).
 */
private fun withoutSpecks(cells: List<BooleanArray>): List<BooleanArray> {
    val seen = cells.map { BooleanArray(it.size) }
    val kept = cells.map { it.copyOf() }
    for (row in cells.indices) {
        for (col in 0 until LandMask.COLUMNS) {
            if (!cells[row][col] || seen[row][col]) continue
            val piece = mutableListOf(row to col)
            seen[row][col] = true
            var next = 0
            while (next < piece.size) {
                val (r, c) = piece[next++]
                for (dr in -1..1) {
                    for (dc in -1..1) {
                        val nr = r + dr
                        val nc = Math.floorMod(c + dc, LandMask.COLUMNS)
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
 * Revolut's dot texture (#07, #08) drawn as a world map behind the element's content: a dot on every land cell, in
 * [color] (the theme's `dots` connected, `dotsIdle` not, S5-10), fading out toward the bottom so what sits under it is on
 * the plain page. The map is centred on [focus]'s cell, which lands [focusTop] from the top and is marked with one
 * brighter dot and a soft ring; with no focus it centres on East Asia and marks nothing. With [fadeTop] (the status bar),
 * no dot is drawn above it, so none sits on the clock (S5-03), and they fade in over 24 dp under it. Inside [clear] (the
 * hero's text, in this element's coordinates) grown by 8 dp they are kept faint, so no dot runs through the figures (S5-02).
 */
fun Modifier.dotMap(focus: DoonaMapPlace?, color: Color, focusTop: Dp, clear: Rect? = null, fadeTop: Dp? = null): Modifier = drawWithCache {
    val pitch = DoonaDotMap.pitch.toPx()
    val centre = Offset(size.width / 2f, focusTop.toPx())
    val rows = dotRows(focus?.lat ?: DEFAULT_LAT, focus?.lon ?: DEFAULT_LON, centre, pitch, size.height)
    val zone = clear?.inflate(DoonaDotMap.clearPad.toPx())
    val topFade = DoonaDotMap.topFade.toPx()
    val fadeFrom = fadeTop?.toPx()
    onDrawBehind {
        for (row in rows) {
            val bottom = ((DoonaDotMap.FADE_END - row.y / size.height) / (DoonaDotMap.FADE_END - DoonaDotMap.FADE_START)).coerceIn(0f, 1f)
            val top = fadeFrom?.let { ((row.y - it) / topFade).coerceIn(0f, 1f) } ?: 1f
            val alpha = DoonaDotMap.ALPHA * bottom * top
            if (alpha <= 0f) continue
            val inZone = zone != null && row.y in zone.top..zone.bottom
            val (faint, full) = if (inZone) row.dots.partition { it.x in zone.left..zone.right } else emptyList<Offset>() to row.dots
            drawPoints(full, PointMode.Points, color.copy(alpha = alpha), DoonaDotMap.dot.toPx(), StrokeCap.Round)
            drawPoints(faint, PointMode.Points, color.copy(alpha = minOf(alpha, DoonaDotMap.CLEAR_ALPHA)), DoonaDotMap.dot.toPx(), StrokeCap.Round)
        }
        if (focus != null) marker(centre, color)
    }
}

/** The land dots of every band between the top and [height], placed so the cell at [lat], [lon] sits on [centre]. */
private fun dotRows(lat: Float, lon: Float, centre: Offset, pitch: Float, height: Float): List<DotRow> {
    val focusCol = floor((lon + DEGREES_WEST) / LandMask.CELL).toInt()
    val focusRow = floor((LandMask.NORTH - lat) / LandMask.CELL).toInt()
    val reach = (centre.x / pitch).toInt() + 1
    return (0 until LandMask.ROWS).mapNotNull { row ->
        val y = centre.y + (row - focusRow) * pitch
        if (y < -pitch || y > height) return@mapNotNull null
        // Columns wrap at the antimeridian, so a node near it still has land on both sides.
        val dots = (-reach..reach).filter { land[row][Math.floorMod(focusCol + it, LandMask.COLUMNS)] }.map { Offset(centre.x + it * pitch, y) }
        DotRow(y, dots)
    }
}

/** The selected place: a soft glow, a thin ring and one bright dot, all in the map's colour. */
private fun DrawScope.marker(at: Offset, color: Color) {
    val glow = DoonaDotMap.glow.toPx()
    drawCircle(Brush.radialGradient(listOf(color.copy(alpha = DoonaDotMap.GLOW_ALPHA), color.copy(alpha = GLOW_EDGE)), at, glow), glow, at)
    drawCircle(color.copy(alpha = DoonaDotMap.RING_ALPHA), DoonaDotMap.ring.toPx(), at, style = Stroke(DoonaDotMap.ringStroke.toPx()))
    drawCircle(color, DoonaDotMap.marker.toPx() / 2f, at)
}

/** Every country of [WorldShapes] as its rings, one path each on its plane, decoded once. */
private val shapes: Map<String, List<Path>> by lazy { WorldShapes.countries.associate { it.code to decode(it.rings) } }

/**
 * The world map, Revolut's 'Your trips' (節點): the world as one solid light-grey shape across the width, every country
 * of [places] filled in the primary colour, with a 0.5 dp seam in the card's colour round each so neighbours stay apart
 * (S7-08). A place the 1:110m shapes leave out or draw in a box under 8 dp² (Hong Kong, Singapore, Taiwan) also gets a
 * primary dot, ringed in the card's colour so it stands off the land around it (S7-04). A ring under 2 dp across is
 * left out, so no island shows as a lone speck (N-03: Hawaii).
 */
@Composable
fun DoonaWorldMap(places: Set<DoonaMapPlace>, modifier: Modifier = Modifier) {
    val colors = DoonaTheme.colors
    val d = DoonaWorldMapDefaults
    val landInk = colors.text.copy(alpha = if (colors.dark) d.LAND_DARK else d.LAND_LIGHT)
    val mark = colors.positive
    val ring = colors.card
    Spacer(
        modifier.layoutRole(LayoutRole.Chart).fillMaxWidth().aspectRatio(WorldShapes.WIDTH / WorldShapes.HEIGHT).drawWithCache {
            val codes = places.mapTo(mutableSetOf()) { it.code.uppercase() }
            val k = size.width / WorldShapes.WIDTH
            val least = d.minRing.toPx() / k
            val seen = { rings: List<Path> -> Path().apply { rings.filter { it.getBounds().maxDimension >= least }.forEach { addPath(it) } } }
            val rest = Path()
            for ((code, rings) in shapes) if (code !in codes) rest.addPath(seen(rings))
            val marked = codes.mapNotNull { shapes[it] }.map(seen)
            val small = d.SMALL_AREA * density * density
            val whole = { rings: List<Path> -> Path().apply { rings.forEach { addPath(it) } }.getBounds() }
            val tiny = places.filter { place -> shapes[place.code.uppercase()]?.let(whole)?.let { it.width * it.height * k * k < small } ?: true }
            val dots = tiny.map { project(it.lat, it.lon) * k }
            val dot = d.dot.toPx()
            val halo = dot + d.halo.toPx()
            val seam = Stroke(d.seam.toPx() / k)
            onDrawBehind {
                scale(k, k, pivot = Offset.Zero) {
                    drawPath(rest, landInk)
                    for (path in marked) drawPath(path, mark)
                    for (path in marked) drawPath(path, ring, style = seam)
                }
                for (at in dots) {
                    drawCircle(ring, halo, at)
                    drawCircle(mark, dot, at)
                }
            }
        },
    )
}

/** Where [lat], [lon] falls on the [WorldShapes] plane: the Miller projection, as tools/rv_world.py draws it. */
private fun project(lat: Float, lon: Float): Offset {
    val unit = WorldShapes.WIDTH / (2 * PI)
    return Offset((lon + HALF_TURN) / FULL_TURN * WorldShapes.WIDTH, ((miller(WorldShapes.NORTH) - miller(lat)) * unit).toFloat())
}

private fun miller(lat: Float): Double = MILLER_K * ln(tan(PI / 4 + MILLER_LAT * Math.toRadians(lat.toDouble())))

/** Google's encoded polylines, one per ring, separated by spaces: each point a zigzag delta from the one before. */
private fun decode(rings: String): List<Path> = rings.split(' ').map { ring ->
    Path().apply {
        val reader = PolylineReader(ring)
        var x = 0
        var y = 0
        var first = true
        while (reader.more) {
            x += reader.next()
            y += reader.next()
            if (first) moveTo(x.toFloat(), y.toFloat()) else lineTo(x.toFloat(), y.toFloat())
            first = false
        }
        close()
    }
}

private class PolylineReader(private val text: String) {
    private var at = 0
    val more get() = at < text.length

    fun next(): Int {
        var value = 0
        var shift = 0
        do {
            val chunk = text[at++].code - CHAR_BASE
            value = value or ((chunk and LOW_BITS) shl shift)
            shift += POLYLINE_BITS
        } while (chunk >= CONTINUES)
        return if (value and 1 == 1) (value shr 1).inv() else value shr 1
    }
}

/** The world map's own metrics: the land's ink, a small place's dot and its ring, the seam, the smallest shapes shown. */
internal object DoonaWorldMapDefaults {
    const val LAND_LIGHT = 0.08f
    const val LAND_DARK = 0.14f
    val dot = 3.dp
    val halo = 1.5.dp
    val seam = 0.5.dp

    /**
     * A filled country whose bounding box is under this many dp² is too small to see, so it gets a dot (S7-04: about
     * 16 dp² of land; the box is about twice the land). Taiwan's box is 6 dp²; the Netherlands', 12 dp², still shows.
     */
    const val SMALL_AREA = 8f

    /** A ring narrower and shorter than this draws as a speck, not a shape. */
    val minRing = 2.dp
}

/** The glow fades to its colour at no alpha, not to transparent black, so its edge does not darken. */
private const val GLOW_EDGE = 0f

/** Land of fewer cells than this is left out of the dot map. */
private const val MIN_LAND = 3
private const val BITS = 4
private const val HEX = 16
private const val DEGREES_WEST = 180f

/** Where the dot map centres with no place: East Asia. */
private const val DEFAULT_LAT = 30f
private const val DEFAULT_LON = 115f

private const val HALF_TURN = 180f
private const val FULL_TURN = 360f
private const val MILLER_K = 1.25
private const val MILLER_LAT = 0.4

private const val CHAR_BASE = 63
private const val LOW_BITS = 0x1F
private const val CONTINUES = 0x20
private const val POLYLINE_BITS = 5
