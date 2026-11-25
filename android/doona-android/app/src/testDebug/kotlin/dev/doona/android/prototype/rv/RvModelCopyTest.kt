package dev.doona.android.prototype.rv

import dev.doona.android.R
import dev.doona.android.feature.model.RvDay
import dev.doona.android.feature.model.RvNode
import dev.doona.android.feature.model.RvSession
import dev.doona.android.prototype.rv.data.RvData
import dev.doona.android.prototype.rv.policy.RvEffectiveConfig
import dev.doona.android.prototype.rv.policy.RvPolicyData
import dev.doona.android.prototype.rv.policy.RvPolicyProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** The models a page takes by parameter copy their lists, so a caller that keeps and changes its list cannot change a page's input. */
class RvModelCopyTest {
    @Test
    fun aDayKeepsTheHoursItWasGiven() {
        val down = mutableListOf(1L, 2L)
        val connections = mutableListOf(3)
        val day = RvDay(down, mutableListOf(4L, 5L), connections)
        down[0] = 100L
        connections.add(9)
        assertEquals(listOf(1L, 2L), day.down)
        assertEquals(listOf(3), day.connections)
        assertEquals(RvDay(listOf(1L, 2L), listOf(4L, 5L), listOf(3)), day)
        assertNotEquals(RvDay(listOf(1L, 2L), listOf(4L, 6L), listOf(3)), day)
    }

    @Test
    fun aSessionKeepsTheMinuteItWasGiven() {
        val minute = mutableListOf(1f, 2f)
        val byHour = mutableListOf(5L)
        val session = RvSession(0L, 1_000L, byHour, byHour.toList(), 2f, 2f, minute, minute.toList())
        minute[0] = 9f
        byHour.clear()
        assertEquals(listOf(1f, 2f), session.minuteDown)
        assertEquals(listOf(5L), session.downByHour)
        assertEquals(5L, session.downTotal)
    }

    @Test
    fun aPolicyProfileKeepsItsListsAndEqualsOneBuiltFromTheSameContents() {
        val nodes: MutableList<RvNode> = RvData.nodes.toMutableList()
        val profile = profile(nodes)
        nodes.removeAt(0)
        assertEquals(RvData.nodes, profile.nodes)
        assertEquals("a rebuilt profile must key the node sort the same", profile(RvData.nodes.toList()).nodes, profile.nodes)
    }

    @Test
    fun anEffectiveConfigKeepsItsLists() {
        val rules = RvPolicyData.allRules.toMutableList()
        val config = RvEffectiveConfig(RvPolicyData.main, RvPolicyData.groups, rules, R.string.rv_main_node, RvData.subscriptions)
        val size = rules.size
        rules.clear()
        assertEquals(size, config.rules.size)
    }

    private fun profile(nodes: List<RvNode>) = RvPolicyProfile(
        main = RvPolicyData.main,
        groups = RvPolicyData.groups,
        rules = RvPolicyData.rules,
        ruleCount = RvPolicyData.RULES,
        nodes = nodes,
        subscriptions = RvData.subscriptions,
        nowEpochMs = RvData.nowEpochMs,
    )
}
