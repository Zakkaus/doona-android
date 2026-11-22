package dev.doona.android.prototype.rv

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.unit.dp
import dev.doona.android.prototype.rv.data.RvPlace
import kotlin.math.PI
import kotlin.math.ln
import kotlin.math.tan

/** Every country of [RvWorld] as its rings, one path each on its plane, decoded once. */
private val shapes: Map<String, List<Path>> by lazy { RvWorld.countries.associate { it.code to decode(it.rings) } }

/**
 * The sample's one world map, Revolut's 'Your trips' (節點 and 統計): the world as one solid light-grey shape across the
 * width, every country in [places] filled in the primary colour, with a 0.5 dp seam in the card's colour round each so
 * neighbours stay apart (S7-08). A place the 1:110m shapes leave out or draw in a box under [SMALL_AREA] dp² (Hong
 * Kong, Singapore, Taiwan) also gets a primary dot, ringed in the card's colour so it stands off the land around it (S7-04).
 * A ring under [MIN_RING] across is left out, so no island shows as a lone speck (N-03: Hawaii).
 */
@Composable
internal fun RvWorldMap(places: Set<RvPlace>, modifier: Modifier = Modifier) {
    val colors = Rv.colors
    val land = colors.text.copy(alpha = if (colors.dark) LAND_DARK else LAND_LIGHT)
    val mark = colors.positive
    val ring = colors.card
    Spacer(
        modifier.fillMaxWidth().aspectRatio(RvWorld.WIDTH / RvWorld.HEIGHT).drawWithCache {
            val codes = places.mapTo(mutableSetOf()) { it.name }
            val k = size.width / RvWorld.WIDTH
            val least = MIN_RING.toPx() / k
            val seen = { rings: List<Path> -> Path().apply { rings.filter { it.getBounds().maxDimension >= least }.forEach { addPath(it) } } }
            val rest = Path()
            for ((code, rings) in shapes) if (code !in codes) rest.addPath(seen(rings))
            val marked = places.mapNotNull { shapes[it.name] }.map(seen)
            val small = SMALL_AREA * density * density
            val whole = { rings: List<Path> -> Path().apply { rings.forEach { addPath(it) } }.getBounds() }
            val tiny = places.filter { place -> shapes[place.name]?.let(whole)?.let { it.width * it.height * k * k < small } ?: true }
            val dots = tiny.map { project(it.lat, it.lon) * k }
            val dot = DOT.toPx()
            val halo = dot + HALO.toPx()
            val seam = Stroke(SEAM.toPx() / k)
            onDrawBehind {
                scale(k, k, pivot = Offset.Zero) {
                    drawPath(rest, land)
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

/** Where [lat], [lon] falls on the [RvWorld] plane: the Miller projection, as tools/rv_world.py draws it. */
private fun project(lat: Float, lon: Float): Offset {
    val unit = RvWorld.WIDTH / (2 * PI)
    return Offset((lon + HALF_TURN) / FULL_TURN * RvWorld.WIDTH, ((miller(RvWorld.NORTH) - miller(lat)) * unit).toFloat())
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
            shift += BITS
        } while (chunk >= CONTINUES)
        return if (value and 1 == 1) (value shr 1).inv() else value shr 1
    }
}

private const val LAND_LIGHT = 0.08f
private const val LAND_DARK = 0.14f
private val DOT = 3.dp
private val HALO = 1.5.dp
private val SEAM = 0.5.dp

/**
 * A filled country whose bounding box is under this many dp² is too small to see, so it gets a dot (S7-04: about 16 dp²
 * of land; the box is about twice the land). Taiwan's box is 6 dp²; the Netherlands', 12 dp², still shows as a shape.
 */
private const val SMALL_AREA = 8f

/** A ring narrower and shorter than this draws as a speck, not a shape. */
private val MIN_RING = 2.dp

private const val HALF_TURN = 180f
private const val FULL_TURN = 360f
private const val MILLER_K = 1.25
private const val MILLER_LAT = 0.4

private const val CHAR_BASE = 63
private const val LOW_BITS = 0x1F
private const val CONTINUES = 0x20
private const val BITS = 5
