package dev.doona.android.prototype.rv.data

import dev.doona.android.designsystem.rv.RvPlace
import dev.doona.android.feature.model.RV_INLINE_KEY
import dev.doona.android.feature.model.RvNodeGroup
import dev.doona.android.feature.model.RvNodeSort
import dev.doona.android.feature.model.rvByName
import dev.doona.android.feature.model.rvNodeGroups
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The node list's orders: each key's rule, and failed or untested nodes last whatever the key. */
class RvNodeOrderTest {
    /** The sample with one node never tested, besides the node whose last test timed out. */
    private val nodes = RvData.nodes.map { if (it.id == UNTESTED) it.untested() else it }

    @Test
    fun failedAndUntestedNodesComeLastInEveryGroupUnderEveryKey() {
        assertTrue("the sample has a node whose last test timed out", nodes.count { it.latencyMs == null } >= 2)
        for (sort in RvNodeSort.entries) {
            for (group in rvNodeGroups(nodes, RvData.subscriptions, sort)) {
                val failed = group.nodes.indexOfFirst { it.latencyMs == null }
                if (failed >= 0) assertTrue("$sort ${group.key}", group.nodes.drop(failed).all { it.latencyMs == null })
            }
        }
    }

    @Test
    fun nodesAddedByHandCloseTheListUnderTheirOwnCard() {
        val byHand = nodes.first().copy(id = "static-1", subscription = "", subscriptionId = null)
        val groups = rvNodeGroups(nodes + byHand, RvData.subscriptions, RvNodeSort.Latency)
        assertEquals(RvData.subscriptions.map { it.id } + RV_INLINE_KEY, groups.map { it.key })
        assertEquals(listOf("static-1"), (groups.last() as RvNodeGroup.Inline).nodes.map { it.id })
    }

    @Test
    fun nameOrderLeavesTheEmojiOut() {
        val plain = nodes.first { it.latencyMs != null }
        val apple = plain.copy(id = "a", name = "\uD83C\uDF4E Apple")
        val banana = plain.copy(id = "b", name = "Banana")
        assertEquals("🍎 Apple sorts under A", listOf("a", "b"), listOf(banana, apple).sortedWith(rvByName).map { it.id })
    }

    @Test
    fun everyKeyShowsEveryNodeOnce() {
        for (sort in RvNodeSort.entries) {
            val shown = rvNodeGroups(nodes, RvData.subscriptions, sort).flatMap { it.nodes }.map { it.id }
            assertEquals(sort.name, nodes.map { it.id }.sorted(), shown.sorted())
        }
    }

    @Test
    fun latencyNameAndStabilityKeepTheSubscriptionsAndOrderInsideThem() {
        for (sort in listOf(RvNodeSort.Latency, RvNodeSort.Stability, RvNodeSort.Name)) {
            val groups = rvNodeGroups(nodes, RvData.subscriptions, sort)
            assertEquals(RvData.subscriptions.map { it.id }, groups.map { it.key })
            groups.forEach { group ->
                val sub = (group as RvNodeGroup.Subscription).subscription
                assertTrue(group.nodes.all { it.subscription == sub.name })
            }
        }
        rvNodeGroups(nodes, RvData.subscriptions, RvNodeSort.Latency).forEach { group ->
            val answered = group.nodes.mapNotNull { it.latencyMs }
            assertEquals(answered.sorted(), answered)
        }
        rvNodeGroups(nodes, RvData.subscriptions, RvNodeSort.Name).forEach { group ->
            val names = group.nodes.filter { it.latencyMs != null }.map { it.name }
            assertEquals(names.sortedWith(String.CASE_INSENSITIVE_ORDER), names)
        }
    }

    @Test
    fun stabilityPutsMoreAnsweredTestsFirstThenTheSmallerSpread() {
        rvNodeGroups(nodes, RvData.subscriptions, RvNodeSort.Stability).forEach { group ->
            group.nodes.filter { it.latencyMs != null }.zipWithNext().forEach { (a, b) ->
                assertTrue("${a.id} before ${b.id}", a.answered > b.answered || (a.answered == b.answered && (a.spreadMs ?: Double.MAX_VALUE) <= (b.spreadMs ?: Double.MAX_VALUE)))
            }
        }
    }

    @Test
    fun regionGroupsEachCountryAcrossSubscriptionsFastestCountryFirst() {
        val groups = rvNodeGroups(nodes, RvData.subscriptions, RvNodeSort.Region).map { it as RvNodeGroup.Place }
        // One card per country the sample has nodes in; 其他, for nodes with no country, has none in it.
        assertEquals(nodes.map { it.place }.toSet().size, groups.size)
        groups.forEach { group -> assertTrue(group.nodes.all { it.place == group.place }) }
        assertTrue("香港 has nodes from both subscriptions", groups.first { it.place == RvPlace.HK }.nodes.map { it.subscription }.toSet().size == 2)
        val leads = groups.mapNotNull { it.nodes.first().latencyMs }
        assertEquals(leads.sorted(), leads)
    }

    private companion object {
        const val UNTESTED = "jp-02"
    }
}
