package dev.doona.android.prototype.rv.data

import dev.doona.android.designsystem.rv.RvPlace
import dev.doona.android.designsystem.rv.RvRegion
import dev.doona.android.designsystem.rv.RvSpot
import dev.doona.android.designsystem.rv.rvFlagArt
import dev.doona.android.prototype.rv.nodes.RvNodesPageState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The place table as the node list and the maps read it: each of the core's regions under its part of the world (the
 * UN's), each with a flag, and a node marking its city when it has one, else its country's centroid.
 */
class RvPlaceTableTest {
    @Test
    fun everyPlaceIsUnderItsPartOfTheWorld() {
        val expected = mapOf(
            RvRegion.Asia to "HK TW JP SG KR IN TR MY TH VN PH ID AE MO IL CN KZ SA PK KH",
            RvRegion.Europe to "GB DE FR NL RU CH SE FI IT ES PL NO DK IE UA PT AT BE LU CZ RO IS",
            RvRegion.Americas to "US CA BR AR MX CL CO",
            RvRegion.Oceania to "AU NZ",
            RvRegion.Africa to "ZA NG EG",
            RvRegion.Other to "Other",
        )
        expected.forEach { (region, codes) ->
            assertEquals(region.name, codes.split(' ').sorted(), RvPlace.entries.filter { it.region == region }.map { it.name }.sorted())
        }
    }

    @Test
    fun everyMappedPlaceHasAFlag() {
        RvPlace.entries.filter { it.mapped }.forEach { assertNotNull("$it has no flag", rvFlagArt(it.flag!!)) }
    }

    @Test
    fun aNodeMarksItsCityElseItsCountryAndOtherMarksNothing() {
        val tokyo = RvData.nodes.first { it.id == "jp-01" }
        assertEquals(RvCity.Tokyo, tokyo.city)
        assertEquals(RvSpot(35.68f, 139.69f), tokyo.spot)
        assertEquals(RvPlace.JP.spot, tokyo.copy(city = null).spot)
        assertNull(tokyo.copy(place = RvPlace.Other).spot)
        RvData.nodes.forEach { assertEquals(it.id, it.place, it.city?.place) }
    }

    @Test
    fun theWorldNodesGroupUnderTheirPartsOfTheWorld() {
        val world = RvData.worldNodes
        assertEquals(listOf("ae-01", "in-01", "tr-01", "us-07", "br-01", "ch-01", "pl-01", "za-01", "nz-01"), world.map { it.id })
        world.forEach { assertEquals(it.id, it, RvData.node(it.id)) }
        val groups = RvNodesPageState(world, "ae-01", RvData.testTimes).byRegion().associate { (region, nodes) -> region to nodes.map { it.id }.sorted() }
        assertEquals(
            mapOf(
                RvRegion.Asia to listOf("ae-01", "in-01", "tr-01"),
                RvRegion.Americas to listOf("br-01", "us-07"),
                RvRegion.Europe to listOf("ch-01", "pl-01"),
                RvRegion.Africa to listOf("za-01"),
                RvRegion.Oceania to listOf("nz-01"),
            ),
            groups,
        )
    }
}
