package dev.doona.android.prototype.rv.data

import dev.doona.android.designsystem.rv.RvPlace
import dev.doona.android.designsystem.rv.RvRegion
import dev.doona.android.feature.model.RvNode
import dev.doona.android.feature.model.RvNodeSort
import dev.doona.android.feature.model.fastest
import dev.doona.android.feature.model.rvNodeGroups
import dev.doona.android.feature.nodes.RvNodesPageState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Nodes whose name gives no country: listed last as 其他, no flag code, no mark on a map. */
class RvUnknownPlaceTest {
    private val nodes = rvUnknownPlaceNodes()

    @Test
    fun otherIsTheOnlyPlaceWithoutAFlagOrASpotOnTheMap() {
        assertNull(RvPlace.Other.flag)
        assertFalse(RvPlace.Other.mapped)
        assertEquals(RvRegion.Other, RvPlace.Other.region)
        RvPlace.entries.filter { it != RvPlace.Other }.forEach {
            assertEquals(it.name, it.flag)
            assertTrue(it.name, it.mapped)
        }
    }

    @Test
    fun otherEndsTheCountryListWhateverItsNodesLatency() {
        val fastest = nodes.minBy { it.latencyMs ?: Int.MAX_VALUE }
        assertEquals("the sample's fastest node has no country", RvPlace.Other, fastest.place)
        val groups = rvNodeGroups(nodes, RvData.subscriptions, RvNodeSort.Region, RvNode::name)
        assertEquals(listOf("TW", "Other"), groups.map { it.key })
        assertEquals(listOf("other-01", "other-03", "other-02").sorted(), groups.last().nodes.map { it.id }.sorted())
    }

    @Test
    fun theMapAndTheCountryCountLeaveOtherOut() {
        val page = RvNodesPageState(nodes, setOf("tw-01"))
        assertEquals(setOf(RvPlace.TW), page.places)
        assertEquals(listOf(RvRegion.Asia, RvRegion.Other), page.byRegion().map { it.first })
    }
}
