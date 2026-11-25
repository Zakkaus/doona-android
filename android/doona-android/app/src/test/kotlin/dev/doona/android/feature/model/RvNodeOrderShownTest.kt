package dev.doona.android.feature.model

import dev.doona.android.designsystem.rv.RvPlace
import dev.doona.android.designsystem.rv.rvShownName
import org.junit.Assert.assertEquals
import org.junit.Test

/** The lists' orders over the names as the rows draw them, and over the nodes of no subscription (組態內). */
class RvNodeOrderShownTest {
    private fun node(id: String, name: String, ms: Int?, subscriptionId: String = "s-1", place: RvPlace = RvPlace.HK) =
        RvNode(id, name, "", place, null, "VLESS", "$id.example.net", 443, "TCP", true, if (subscriptionId.isEmpty()) "" else "Harbor", subscriptionId, listOf(ms), shareable = false)

    /** The rows' titles as the pages give them ([dev.doona.android.feature.nodes.ui.rememberRvNodeTitles]) for names that keep words. */
    private val title: (RvNode) -> String = { rvShownName(it.name).text }

    private val harbor = RvSubscription("s-1", "Harbor", 3, 0L, 0L, 0L, null)

    @Test
    fun byNameSortsTheShownNameWithItsEmojiLeftOut() {
        val nodes = listOf(node("hk2", "HK 02", 30), node("hk1", "🇭🇰 HK 01", 40), node("ae", "🇦🇪 UAE 01", 10), node("au", "🇦🇺 Australia 01", 20))
        assertEquals(listOf("au", "hk1", "hk2", "ae"), rvSectionOrder(nodes, RvNodeSort.Name, title).map { it.id })
    }

    @Test
    fun byNameKeepsUntestedNodesLast() {
        val nodes = listOf(node("b", "B 01", 30), node("a", "A 01", null))
        assertEquals(listOf("b", "a"), nodes.sortedWith(rvByName(title)).map { it.id })
    }

    @Test
    fun everyOrderListsTheNodesOfNoSubscriptionUnder組態內() {
        val nodes = listOf(node("hk", "HK 01", 30), node("mine", "Home 01", 20, subscriptionId = "", place = RvPlace.JP))
        for (sort in RvNodeSort.entries) {
            val groups = rvNodeGroups(nodes, listOf(harbor), sort, title)
            assertEquals("$sort", setOf("hk", "mine"), groups.flatMap { it.nodes }.map { it.id }.toSet())
            if (sort != RvNodeSort.Region) assertEquals("$sort", listOf("mine"), groups.filterIsInstance<RvNodeGroup.Inline>().single().nodes.map { it.id })
        }
    }

    /** Two subscriptions of one name (one host, two tokens: the name defaults to the host) keep their own nodes. */
    @Test
    fun subscriptionsOfOneNameKeepTheirOwnNodes() {
        val other = harbor.copy(id = "s-2")
        val nodes = listOf(node("a", "HK 01", 30), node("b", "HK 02", 20, subscriptionId = "s-2"))
        for (sort in RvNodeSort.entries.filter { it != RvNodeSort.Region }) {
            val groups = rvNodeGroups(nodes, listOf(harbor, other), sort, title).filterIsInstance<RvNodeGroup.Subscription>()
            assertEquals("$sort", listOf(listOf("a"), listOf("b")), groups.map { g -> g.nodes.map { it.id } })
        }
    }
}
