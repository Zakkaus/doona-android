package dev.doona.android.prototype.rv

import androidx.compose.ui.geometry.Offset
import dev.doona.android.designsystem.rv.RV_MAP_REST_LON
import dev.doona.android.designsystem.rv.RvDots
import dev.doona.android.designsystem.rv.RvPlace
import dev.doona.android.designsystem.rv.RvSpace
import dev.doona.android.designsystem.rv.RvSpot
import dev.doona.android.designsystem.rv.rvMapLand
import dev.doona.android.designsystem.rv.rvMapSpot
import dev.doona.android.prototype.rv.data.RvCity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * 活動's map for a node in any country or region of the place table (doona-core's 54), or in any city of the sample: the
 * pan takes the short way round from wherever the map is, and at its end the spot's dot sits on land inside the map's
 * visible area, its glow included.
 */
class RvMapPanTest {
    private val mapped = RvPlace.entries.filter { it.mapped }

    /** Every spot a node can mark: each place's centroid and each sample city, by name. */
    private val spots: List<Pair<String, RvSpot>> = mapped.map { it.name to it.spot!! } + RvCity.entries.map { it.name to it.spot }

    /** Where a pan may start: at rest, on any spot, and on any spot a turn either way (a pan across the antimeridian). */
    private val starts = listOf(RV_MAP_REST_LON) + spots.flatMap { (_, at) -> listOf(at.lon, at.lon - FULL_TURN, at.lon + FULL_TURN) }

    @Test
    fun theTableIsTheCoresFiftyFourRegions() {
        assertEquals(CORE_REGIONS.split(' '), mapped.map { it.name })
    }

    @Test
    fun everyPanTakesTheShortWayAndEndsOnTheSpot() {
        spots.forEach { (name, at) ->
            starts.forEach { from ->
                val to = rvPanLon(from, at.lon)
                assertTrue("$name from $from runs ${to - from} degrees", abs(to - from) <= HALF_TURN)
                assertEquals("$name from $from ends on its longitude", at.lon, rvWrapLon(to), EPSILON)
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
            spots.forEach { (name, at) ->
                assertTrue("$name lies on a land dot", rvMapLand(at.lat, at.lon))
                starts.forEach { from ->
                    val end = rvWrapLon(rvPanLon(from, at.lon))
                    val spot = rvMapSpot(at.lat, end, at.lat, at.lon, centre, RvDots.pitch.value)
                    assertTrue("$name from $from at ${width}dp lands at $spot", spot.x in left..right && spot.y in top..bottom)
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

        /** doona-core `regions/data.rs`, in its order. */
        const val CORE_REGIONS = "HK TW JP SG US KR CA GB DE FR NL RU AU IN TR BR AR MY TH VN PH ID AE CH SE FI IT ES PL MO NZ MX NO DK IE " +
            "ZA IL UA PT CN KZ AT BE LU CZ RO IS CL CO SA PK NG EG KH"

        /** The narrowest phone the app lays out for and the sample's. */
        val WIDTHS = listOf(360f, 412f)
    }
}
