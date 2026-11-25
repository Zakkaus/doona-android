package dev.doona.android.prototype.rv

import androidx.compose.ui.geometry.Offset
import dev.doona.android.designsystem.rv.RV_MAP_REST_LON
import dev.doona.android.designsystem.rv.RvDots
import dev.doona.android.designsystem.rv.RvPlace
import dev.doona.android.designsystem.rv.RvSpace
import dev.doona.android.designsystem.rv.RvSpot
import dev.doona.android.designsystem.rv.rvDotRows
import dev.doona.android.designsystem.rv.rvMarkerAt
import dev.doona.android.designsystem.rv.rvPanLon
import dev.doona.android.designsystem.rv.rvWrapLon
import dev.doona.android.feature.model.RvCity
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
    fun theFirstTwelveTakeTheCoresAnchorsToo() {
        // doona-core regions/data.rs (regions3, 4e6929b5): one table, the sample's first twelve no longer kept apart.
        val core = mapOf(
            RvPlace.HK to (22.4f to 114.11f), RvPlace.TW to (23.7f to 120.96f), RvPlace.JP to (36.2f to 138.25f),
            RvPlace.SG to (1.35f to 103.82f), RvPlace.US to (37.09f to -95.71f), RvPlace.KR to (35.91f to 127.77f),
            RvPlace.CA to (56.13f to -106.35f), RvPlace.GB to (55.38f to -3.44f), RvPlace.DE to (51.17f to 10.45f),
            RvPlace.FR to (46.23f to 2.21f), RvPlace.NL to (52.13f to 5.29f), RvPlace.RU to (61.52f to 105.32f),
        )
        core.forEach { (place, at) -> assertEquals("$place", at, place.lat to place.lon) }
    }

    @Test
    fun twoCitiesOfOneCellAreMarkedApart() {
        val pitch = PITCH_PX
        val centre = Offset(CENTRE_X, CENTRE_Y)
        listOf(RvCity.Taipei to RvCity.Taichung, RvCity.Seoul to RvCity.Chuncheon).forEach { (a, b) ->
            // One cell: the map drawn under both is the same, so only the marker can tell them apart.
            assertEquals("$a and $b share a cell", dots(a.spot, centre), dots(b.spot, centre))
            val apart = (rvMarkerAt(a.spot, centre, pitch) - rvMarkerAt(b.spot, centre, pitch)).getDistance()
            assertTrue("$a and $b are marked $apart px apart", apart >= pitch / 4)
        }
        spots.forEach { (name, at) ->
            val mark = rvMarkerAt(at, centre, pitch)
            assertTrue("$name's marker stays inside its cell: $mark", abs(mark.x - centre.x) <= pitch / 2 && abs(mark.y - centre.y) <= pitch / 2)
        }
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
        // Every pan ends on the spot's own longitude (everyPanTakesTheShortWayAndEndsOnTheSpot), so the map it leaves is
        // the one centred on the spot.
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
                val drawn = rvDotRows(at.lat, at.lon, centre, RvDots.pitch.value, HEIGHT_DP).flatMap { it.dots }
                assertTrue("$name: the map draws a land dot under its marker", centre in drawn)
                val mark = rvMarkerAt(at, centre, RvDots.pitch.value)
                assertTrue("$name at ${width}dp is marked at $mark", mark.x in left..right && mark.y in top..bottom)
            }
        }
    }

    @Test
    fun theMapWrapsAtTheAntimeridianAsItDrawsIt() {
        val centre = Offset(CENTRE_X, CENTRE_Y)
        // Chukotka runs past 180 degrees: centred a degree west of it, the map draws its east end right of the centre.
        val west = rows(CHUKOTKA_LAT, 179f, centre)
        val east = west.filter { (y, _) -> abs(y - centre.y) <= SEAM_ROWS * PITCH_PX }.flatMap { (_, xs) -> xs.filter { it > centre.x } }
        assertTrue("land east of 180 degrees: $east", east.isNotEmpty())
        // A whole turn either way draws the same map.
        assertEquals(west, rows(CHUKOTKA_LAT, 179f - FULL_TURN, centre))
        assertEquals(west, rows(CHUKOTKA_LAT, 179f + FULL_TURN, centre))
        // A cell east, across the seam, the same land lies a pitch further left (the outermost columns aside).
        val reach = centre.x - PITCH_PX * 2
        val across = rows(CHUKOTKA_LAT, -179f, centre).map { (y, xs) -> y to xs.map { it + PITCH_PX } }
        assertEquals(west.inside(centre.x, reach), across.inside(centre.x, reach))
    }

    /** The land dots the map draws centred on [spot], everywhere in the window. */
    private fun dots(spot: RvSpot, centre: Offset) = rvDotRows(spot.lat, spot.lon, centre, PITCH_PX, HEIGHT_PX).flatMap { it.dots }

    /** Each band the map draws centred on [lat], [lon]: its y and its dots' x. */
    private fun rows(lat: Float, lon: Float, centre: Offset) = rvDotRows(lat, lon, centre, PITCH_PX, HEIGHT_PX).map { row -> row.y to row.dots.map { it.x } }

    /** The bands with only the dots less than [reach] from [centre] across. */
    private fun List<Pair<Float, List<Float>>>.inside(centre: Float, reach: Float) = map { (y, xs) -> y to xs.filter { abs(it - centre) < reach } }

    private companion object {
        const val HALF_TURN = 180f
        const val FULL_TURN = 360f
        const val EPSILON = 1e-3f

        // RvDots.pitch (5.5 dp) at 2 px per dp, and a centre anywhere on the map.
        const val PITCH_PX = 11f
        const val CENTRE_X = 412f
        const val CENTRE_Y = 300f
        const val HEIGHT_PX = 1830f
        const val HEIGHT_DP = 915f

        /** Chukotka, which 180 degrees runs through, and how many bands either side of it the seam test looks at. */
        const val CHUKOTKA_LAT = 67f
        const val SEAM_ROWS = 3

        /** doona-core `regions/data.rs`, in its order. */
        const val CORE_REGIONS = "HK TW JP SG US KR CA GB DE FR NL RU AU IN TR BR AR MY TH VN PH ID AE CH SE FI IT ES PL MO NZ MX NO DK IE " +
            "ZA IL UA PT CN KZ AT BE LU CZ RO IS CL CO SA PK NG EG KH"

        /** The narrowest phone the app lays out for and the sample's. */
        val WIDTHS = listOf(360f, 412f)
    }
}
