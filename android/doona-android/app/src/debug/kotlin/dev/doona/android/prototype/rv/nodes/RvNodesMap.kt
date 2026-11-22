package dev.doona.android.prototype.rv.nodes

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.unit.dp
import dev.doona.android.prototype.rv.Rv
import dev.doona.android.prototype.rv.data.RvPlace
import kotlin.math.PI
import kotlin.math.ln
import kotlin.math.tan

/** Every country of [RvNodesWorld] as one path on its plane, decoded once. */
private val shapes: Map<String, Path> by lazy { RvNodesWorld.countries.associate { it.code to decode(it.rings) } }

/**
 * Revolut's 'Your trips' map: the world as one solid light-grey shape across the width, every country in [places]
 * filled in the primary colour. A place too small for the 1:110m shapes (Hong Kong, Singapore) is a primary dot, ringed
 * in the card's colour so it stands off the land around it.
 */
@Composable
internal fun RvNodesMap(places: Set<RvPlace>, modifier: Modifier = Modifier) {
    val colors = Rv.colors
    val land = colors.text.copy(alpha = if (colors.dark) LAND_DARK else LAND_LIGHT)
    val mark = colors.positive
    val ring = colors.card
    Spacer(
        modifier.fillMaxWidth().aspectRatio(RvNodesWorld.WIDTH / RvNodesWorld.HEIGHT).drawWithCache {
            val codes = places.mapTo(mutableSetOf()) { it.name }
            val rest = Path()
            val marked = Path()
            for ((code, path) in shapes) (if (code in codes) marked else rest).addPath(path)
            val k = size.width / RvNodesWorld.WIDTH
            val dots = places.filter { it.name !in shapes }.map { project(it.lat, it.lon) * k }
            val dot = DOT.toPx()
            val halo = dot + HALO.toPx()
            onDrawBehind {
                scale(k, k, pivot = Offset.Zero) {
                    drawPath(rest, land)
                    drawPath(marked, mark)
                }
                for (at in dots) {
                    drawCircle(ring, halo, at)
                    drawCircle(mark, dot, at)
                }
            }
        },
    )
}

/** Where [lat], [lon] falls on the [RvNodesWorld] plane: the Miller projection, as tools/rv_nodes_countries.py draws it. */
private fun project(lat: Float, lon: Float): Offset {
    val unit = RvNodesWorld.WIDTH / (2 * PI)
    return Offset((lon + HALF_TURN) / FULL_TURN * RvNodesWorld.WIDTH, ((miller(RvNodesWorld.NORTH) - miller(lat)) * unit).toFloat())
}

private fun miller(lat: Float): Double = MILLER_K * ln(tan(PI / 4 + MILLER_LAT * Math.toRadians(lat.toDouble())))

/** Google's encoded polylines, one per ring, separated by spaces: each point a zigzag delta from the one before. */
private fun decode(rings: String): Path = Path().apply {
    for (ring in rings.split(' ')) {
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

private const val HALF_TURN = 180f
private const val FULL_TURN = 360f
private const val MILLER_K = 1.25
private const val MILLER_LAT = 0.4

private const val CHAR_BASE = 63
private const val LOW_BITS = 0x1F
private const val CONTINUES = 0x20
private const val BITS = 5
