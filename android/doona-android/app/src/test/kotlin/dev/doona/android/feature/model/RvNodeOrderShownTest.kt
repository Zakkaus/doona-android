package dev.doona.android.feature.model

import dev.doona.android.designsystem.rv.RvPlace
import org.junit.Assert.assertEquals
import org.junit.Test

/** The lists' orders over the names as the rows draw them, and over the nodes of no subscription (組態內). */
class RvNodeOrderShownTest {
    private fun node(id: String, name: String, ms: Int?, subscription: String = "Harbor", place: RvPlace = RvPlace.HK) =
        RvNode(id, name, "", place, null, "VLESS", "$id.example.net", 443, "TCP", true, subscription, listOf(ms))

    private val harbor = RvSubscription("s-1", "Harbor", 3, 0L, 0L, 0L, null)

    @Test
    fun byNameSortsTheShownNameWithItsEmojiLeftOut() {
        val nodes = listOf(node("hk2", "HK 02", 30), node("hk1", "🇭🇰 HK 01", 40), node("ae", "🇦🇪 UAE 01", 10), node("au", "🇦🇺 Australia 01", 20))
        assertEquals(listOf("au", "hk1", "hk2", "ae"), rvSectionOrder(nodes, RvNodeSort.Name).map { it.id })
    }

    @Test
    fun byNameKeepsUntestedNodesLast() {
        val nodes = listOf(node("b", "B 01", 30), node("a", "A 01", null))
        assertEquals(listOf("b", "a"), nodes.sortedWith(rvByName).map { it.id })
    }

    @Test
    fun everyOrderListsTheNodesOfNoSubscriptionUnder組態內() {
        val nodes = listOf(node("hk", "HK 01", 30), node("mine", "Home 01", 20, subscription = "", place = RvPlace.JP))
        for (sort in RvNodeSort.entries) {
            val groups = rvNodeGroups(nodes, listOf(harbor), sort)
            assertEquals("$sort", setOf("hk", "mine"), groups.flatMap { it.nodes }.map { it.id }.toSet())
            if (sort != RvNodeSort.Region) assertEquals("$sort", listOf("mine"), groups.filterIsInstance<RvNodeGroup.Inline>().single().nodes.map { it.id })
        }
    }
}
