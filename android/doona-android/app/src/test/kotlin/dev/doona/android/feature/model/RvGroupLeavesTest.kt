package dev.doona.android.feature.model

import dev.doona.android.engine.Group
import dev.doona.android.engine.GroupKind
import dev.doona.android.engine.Member
import dev.doona.android.engine.Network
import dev.doona.android.engine.fake.FakeEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The shared group helpers: every leaf node once, and the node a group uses on a network through nested groups. */
class RvGroupLeavesTest {
    private val asia = Group("g-asia", "Asia", GroupKind.UrlTest, listOf(Member("n-jp", "Tokyo 01"), Member("n-sg", "Singapore 01")), Group.Selection("n-sg", pinned = false))
    private val main = Group(
        "g-main",
        "Mine",
        GroupKind.Selector,
        listOf(Member("n-us", "Dallas 01"), Member("g-asia", "Asia", isGroup = true), Member("n-jp", "Tokyo 01")),
        Group.Selection("g-asia", pinned = true),
        Group.Selection("n-us", pinned = true),
    )
    private val groups = listOf(main, asia).associateBy { it.id }

    @Test
    fun leafMembersListEveryNodeOnceInTheCatalogsOrderWithoutTheNestedGroups() {
        val catalog = checkNotNull(FakeEngine(groups = listOf(main, asia)).catalog.value)
        assertEquals(listOf("n-us", "n-jp", "n-sg"), rvLeafMembers(catalog).map { it.id })
    }

    @Test
    fun theSelectedLeafFollowsNestedGroupsPerNetwork() {
        assertEquals("n-sg", rvSelectedLeaf(main, Network.Tcp, groups))
        assertEquals("n-us", rvSelectedLeaf(main, Network.Udp, groups))
    }

    @Test
    fun aCycleOrNoChoiceNamesNoNode() {
        val a = Group("g-a", "A", GroupKind.Selector, listOf(Member("g-b", "B", isGroup = true)), Group.Selection("g-b", pinned = true))
        val b = Group("g-b", "B", GroupKind.Selector, listOf(Member("g-a", "A", isGroup = true)), Group.Selection("g-a", pinned = true))
        assertNull(rvSelectedLeaf(a, Network.Tcp, mapOf("g-a" to a, "g-b" to b)))
        val none = Group("g-n", "N", GroupKind.Selector, emptyList(), Group.Selection(null, pinned = false))
        assertNull(rvSelectedLeaf(none, Network.Tcp, mapOf("g-n" to none)))
    }
}
