package dev.doona.android.prototype.rv.stats

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.VectorGroup
import androidx.compose.ui.graphics.vector.VectorPath
import androidx.compose.ui.graphics.vector.toPath
import androidx.compose.ui.res.vectorResource
import dev.doona.android.R
import dev.doona.android.prototype.rv.Rv
import dev.doona.android.prototype.rv.data.RvPlace
import kotlin.math.PI
import kotlin.math.ln
import kotlin.math.tan

/** The 1:110m world of res/drawable/rv_stats_world.xml: all land as one path, and each country's outline by ISO code. */
@Immutable
internal class RvWorld(val width: Float, val height: Float, val land: Path, val countries: Map<String, Path>)

@Composable
private fun rememberWorld(): RvWorld {
    val vector = ImageVector.vectorResource(R.drawable.rv_stats_world)
    return remember(vector) {
        // One path for the land, so neighbouring countries meet without the hairline seam two fills leave.
        val land = Path()
        val countries = mutableMapOf<String, Path>()
        fun walk(group: VectorGroup) {
            for (node in group) {
                when (node) {
                    is VectorGroup -> walk(node)

                    is VectorPath -> {
                        val path = node.pathData.toPath().apply { fillType = node.pathFillType }
                        land.addPath(path)
                        if (node.name.isNotEmpty()) countries[node.name] = path
                    }
                }
            }
        }
        walk(vector.root)
        RvWorld(vector.viewportWidth, vector.viewportHeight, land, countries)
    }
}

/**
 * Revolut's 'Your trips' map: the world solid in a light grey across the card, every country in [marked] (ISO codes)
 * filled in the primary colour. A marked place too small for the 1:110m map, as Hong Kong and Singapore, is a dot.
 */
@Composable
internal fun RvWorldMap(marked: Set<String>, modifier: Modifier = Modifier) {
    val world = rememberWorld()
    val colors = Rv.colors
    val land = colors.text.copy(alpha = RvStatsAlpha.LAND)
    val dots = marked.filter { it !in world.countries }.mapNotNull { code -> RvPlace.entries.firstOrNull { it.name == code } }
    Canvas(modifier.fillMaxWidth().aspectRatio(world.width / world.height)) {
        val k = size.width / world.width
        scale(k, k, pivot = Offset.Zero) {
            drawPath(world.land, land)
            marked.forEach { code -> world.countries[code]?.let { drawPath(it, colors.positive) } }
        }
        val dot = RvStatsSize.placeDot.toPx() / 2
        dots.forEach { place ->
            val at = project(place.lat, place.lon) * k
            drawCircle(colors.card, dot + RvStatsSize.placeRing.toPx(), at)
            drawCircle(colors.positive, dot, at)
        }
    }
}

/** Where a place lands in the map's viewport: tools/rv_stats_world.py's Miller projection, with its constants. */
private fun project(lat: Float, lon: Float) = Offset((lon + HALF_TURN) * SCALE, (TOP - miller(lat)) * SCALE)

private fun miller(lat: Float): Float = Math.toDegrees(MILLER_Y * ln(tan(PI / 4 + MILLER_LAT * Math.toRadians(lat.toDouble())))).toFloat()

private const val NORTH = 83f
private const val SCALE = 2f
private const val HALF_TURN = 180f
private const val MILLER_Y = 1.25
private const val MILLER_LAT = 0.4
private val TOP = miller(NORTH)
