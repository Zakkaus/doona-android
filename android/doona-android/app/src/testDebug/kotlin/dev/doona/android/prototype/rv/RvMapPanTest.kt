package dev.doona.android.prototype.rv

import androidx.compose.ui.geometry.Offset
import dev.doona.android.designsystem.rv.RV_MAP_REST_LON
import dev.doona.android.designsystem.rv.RvDots
import dev.doona.android.designsystem.rv.RvPlace
import dev.doona.android.designsystem.rv.RvSpace
import dev.doona.android.designsystem.rv.rvMapLand
import dev.doona.android.designsystem.rv.rvMapSpot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * 活動's map for a node in any country of the place table: the pan takes the short way round from wherever the map is,
 * and at its end the place's dot sits on land inside the map's visible area, its glow included.
 */
class RvMapPanTest {
    private val mapped = RvPlace.entries.filter { it.mapped }

    /** Where a pan may start: at rest, on any place, and on any place a turn either way (a pan across the antimeridian). */
    private val starts = listOf(RV_MAP_REST_LON) + mapped.flatMap { listOf(it.lon, it.lon - FULL_TURN, it.lon + FULL_TURN) }

    @Test
    fun everyPanTakesTheShortWayAndEndsOnThePlace() {
        mapped.forEach { place ->
            starts.forEach { from ->
                val to = rvPanLon(from, place.lon)
                assertTrue("$place from $from runs ${to - from} degrees", abs(to - from) <= HALF_TURN)
                assertEquals("$place from $from ends on its longitude", place.lon, rvWrapLon(to), EPSILON)
            }
        }
    }

    @Test
    fun fromAsiaToAmericaThePanCrossesThePacific() {
        listOf(RvPlace.HK, RvPlace.TW, RvPlace.JP, RvPlace.KR, RvPlace.SG, RvPlace.AU).forEach { asia ->
            listOf(RvPlace.US, RvPlace.CA).forEach { america ->
                val to = rvPanLon(asia.lon, america.lon)
                assertTrue("$asia to $america runs east past 180 degrees: $to", to > asia.lon && to > HALF_TURN)
                val back = rvPanLon(america.lon, asia.lon)
                assertTrue("$america to $asia runs west past -180 degrees: $back", back < america.lon && back < -HALF_TURN)
            }
        }
        assertTrue("Germany from Hong Kong runs west", rvPanLon(RvPlace.HK.lon, RvPlace.DE.lon) < RvPlace.HK.lon)
    }

    @Test
    fun afterEveryPanThePlaceSitsOnLandInsideTheVisibleMap() {
        WIDTHS.forEach { width ->
            // The marker's spot under the status bar, in dp; the map is centred across the screen's width.
            val centre = Offset(width / 2, RvDots.focusTop.value)
            val glow = RvDots.glow.value
            val left = glow
            val right = width - glow
            // Dots start under the status bar's fade; the hero's caption starts at the header's top.
            val top = RvDots.topFade.value + glow
            val bottom = RvSpace.headerTop.value - glow
            mapped.forEach { place ->
                assertTrue("$place lies on a land dot", rvMapLand(place.lat, place.lon))
                starts.forEach { from ->
                    val end = rvWrapLon(rvPanLon(from, place.lon))
                    val spot = rvMapSpot(place.lat, end, place.lat, place.lon, centre, RvDots.pitch.value)
                    assertTrue("$place from $from at ${width}dp lands at $spot", spot.x in left..right && spot.y in top..bottom)
                }
            }
        }
    }

    @Test
    fun theSpotWrapsTheShortWayAcrossTheAntimeridian() {
        val centre = Offset(0f, 0f)
        // A map centred just west of 180 degrees draws a place just east of -180 a column or two to its right, not a
        // whole world to its left.
        val spot = rvMapSpot(0f, 179f, 0f, -179f, centre, 1f)
        assertTrue("the place a degree past the antimeridian: $spot", spot.x in 0f..2f)
    }

    private companion object {
        const val HALF_TURN = 180f
        const val FULL_TURN = 360f
        const val EPSILON = 1e-3f

        /** The narrowest phone the app lays out for and the sample's. */
        val WIDTHS = listOf(360f, 412f)
    }
}
