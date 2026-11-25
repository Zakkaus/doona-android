package dev.doona.android.feature.model

import dev.doona.android.engine.Group
import dev.doona.android.engine.GroupKind
import dev.doona.android.engine.Member
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SelectedNodeTest {
    private fun node(id: String) = Member(id, id)

    private fun inner(id: String) = Member(id, id, isGroup = true)

    private fun group(id: String, members: List<Member>, selectedId: String?) = Group(id, id, GroupKind.Selector, members, selectedId)

    private fun byId(vararg groups: Group) = groups.associateBy { it.id }

    @Test
    fun `a node is its own leaf`() {
        assertEquals("n1", leafOf(node("n1"), emptyMap())?.id)
    }

    @Test
    fun `a group leads to the node its selection ends on, through any depth`() {
        val groups = byId(
            group("g-A", listOf(inner("g-B")), selectedId = "g-B"),
            group("g-B", listOf(inner("g-C")), selectedId = "g-C"),
            group("g-C", listOf(node("n1"), node("n2")), selectedId = "n2"),
        )
        assertEquals("n2", leafOf(inner("g-A"), groups)?.id)
    }

    @Test
    fun `a group with no selection, a missing group or a cycle leads nowhere`() {
        val groups = byId(
            group("g-None", listOf(node("n1")), selectedId = null),
            group("g-Loop1", listOf(inner("g-Loop2")), selectedId = "g-Loop2"),
            group("g-Loop2", listOf(inner("g-Loop1")), selectedId = "g-Loop1"),
        )
        assertNull(leafOf(inner("g-None"), groups))
        assertNull(leafOf(inner("g-Gone"), groups))
        assertNull(leafOf(inner("g-Loop1"), groups))
    }
}
