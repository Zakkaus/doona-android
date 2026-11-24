package dev.doona.android.engine.fake

import dev.doona.android.engine.Group
import dev.doona.android.engine.GroupKind
import dev.doona.android.engine.Member
import dev.doona.android.engine.Network
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeEngineSelectionTest {
    private val groupId = "g-Auto"
    private val engine = FakeEngine(
        groups = listOf(Group(groupId, "Auto", GroupKind.Score, listOf(Member("a", "A"), Member("b", "B")), "a")),
    )
    private val group: Group get() = engine.groups.value.single()

    @Test
    fun selectingTcpPinsOnlyTcp() = runTest {
        engine.select(groupId, "b", Network.Tcp).getOrThrow()

        assertEquals(Group.Selection("b", true), group.tcp)
        assertEquals(Group.Selection("a", false), group.udp)
        assertTrue(group.networksDiffer)
        assertEquals("b", group.selectedId)
        assertTrue(group.pinned)
    }

    @Test
    fun selectingUdpKeepsTheTcpChoice() = runTest {
        engine.select(groupId, "b", Network.Udp).getOrThrow()

        assertEquals(Group.Selection("a", false), group.tcp)
        assertEquals(Group.Selection("b", true), group.udp)
        assertTrue(group.networksDiffer)
        assertEquals("a", group.selectedId)
        assertFalse(group.pinned)
    }

    @Test
    fun selectingBothReplacesSplitPins() = runTest {
        engine.select(groupId, "b", Network.Tcp).getOrThrow()
        engine.select(groupId, "a", Network.Udp).getOrThrow()
        engine.select(groupId, "b", network = null).getOrThrow()

        assertEquals(Group.Selection("b", true), group.tcp)
        assertEquals(Group.Selection("b", true), group.udp)
        assertFalse(group.networksDiffer)

        engine.select(groupId, "a", Network.Tcp).getOrThrow()
        engine.select(groupId, null, Network.Udp).getOrThrow()
        assertEquals(Group.Selection("a", true), group.tcp)
        assertEquals(Group.Selection("a", false), group.udp)
        assertFalse(group.networksDiffer)

        engine.select(groupId, null, network = null).getOrThrow()
        assertEquals(Group.Selection("a", false), group.tcp)
        assertEquals(Group.Selection("a", false), group.udp)
        assertFalse(group.networksDiffer)
    }
}
