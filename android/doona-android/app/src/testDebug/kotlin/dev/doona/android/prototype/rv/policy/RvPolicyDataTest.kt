package dev.doona.android.prototype.rv.policy

import dev.doona.android.R
import dev.doona.android.feature.model.RvGroupKind
import dev.doona.android.feature.model.RvLabel
import dev.doona.android.feature.model.RvPolicyState
import dev.doona.android.feature.model.RvRuleState
import dev.doona.android.prototype.rv.data.RvData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The 策略 page's routing agrees with the one dataset and with 活動's outbound card, on plain JVM. */
class RvPolicyDataTest {
    @Test
    fun theMainNodeIsTheNodeInUse() {
        assertEquals(RvData.CURRENT_ID, RvPolicyData.main.selected?.id)
        assertEquals("Hong Kong 02 IPLC", RvPolicyData.main.selected?.name)
        assertEquals(RvData.nodes.size, RvPolicyData.main.members.size)
    }

    @Test
    fun ruleGroupsUseTheNodesActivityShows() {
        for ((group, name) in listOf(RvPolicyData.streaming to R.string.rv_rule_streaming, RvPolicyData.ai to R.string.rv_rule_ai)) {
            assertEquals(RvData.ruleOutbounds.first { it.group == name }.nodeId, group.selectedId)
            assertEquals(RvLabel.Res(name), group.name)
        }
        assertEquals("Taipei 04", RvPolicyData.streaming.selected?.name)
        assertEquals("Tokyo 02", RvPolicyData.ai.selected?.name)
    }

    @Test
    fun urlTestGroupsUseTheirFastestNode() {
        val auto = RvPolicyData.groups.filter { it.kind == RvGroupKind.Auto }
        assertEquals(5, auto.size)
        auto.forEach { assertEquals(it.byLatency.first().id, it.selectedId) }
        assertEquals("Taipei 04", RvPolicyData.auto.selected?.name)
        assertEquals(26, RvPolicyData.auto.selected?.latencyMs)
    }

    @Test
    fun theListHoldsEveryRuleWithTheCardsInPlace() {
        val all = RvPolicyData.allRules
        assertEquals(RvPolicyData.RULES, all.size)
        assertEquals(RvPolicyData.rules.dropLast(1), all.take(4))
        assertEquals(RvPolicyData.rules.last(), all.last())
        // The card's five carry all of today's bytes, so the rest carried none.
        assertEquals(RvPolicyData.rules.sumOf { it.bytes }, all.sumOf { it.bytes })
        assertEquals(listOf(11, 1, 2), RvRuleState.entries.map { s -> all.count { it.state == s } })
    }

    @Test
    fun theRulesCarryTodaysBytes() {
        val rules = RvPolicyData.rules
        assertTrue(rules.all { it.bytes > 0 })
        assertEquals(RvData.today.total, rules.sumOf { it.bytes })
        assertEquals(RvData.split.proxyBytes, rules.filter { it.group != null }.sumOf { it.bytes })
        assertEquals(RvData.split.directBytes, rules.filter { it.group == null }.sumOf { it.bytes })
        assertEquals(RvPolicyData.main, rules.last().group)
        assertTrue(rules.size < RvPolicyData.RULES)
        assertEquals(14, RvPolicyData.RULES)
    }

    @Test
    fun theAutoGroupHasTheDatasetsName() {
        val xml = File("src/main/res/values-zh-rTW/strings_rv_policy.xml").readText()
        assertEquals(RvData.AUTO_GROUP, Regex("<string name=\"rv_policy_group_auto\">([^<]*)</string>").find(xml)!!.groupValues[1])
    }

    @Test
    fun eachWayOfMatchingKeepsItsPartOfTheHost() {
        val host = RvPolicyData.connection.host
        assertEquals("gemini.google.com", RvMatchBy.Full.of(host))
        assertEquals("google.com", RvMatchBy.Suffix.of(host))
        assertEquals("gemini", RvMatchBy.Keyword.of(host))
    }

    @Test
    fun choosingANodeChangesOnlyThatGroup() {
        val state = RvPolicyState().choose(RvPolicyData.streaming.id, "hk-01")
        assertEquals("hk-01", state.group(RvPolicyData.streaming).selectedId)
        assertEquals(RvPolicyData.ai.selectedId, state.group(RvPolicyData.ai).selectedId)
    }
}
