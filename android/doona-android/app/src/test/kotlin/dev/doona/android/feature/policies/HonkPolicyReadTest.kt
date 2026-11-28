package dev.doona.android.feature.policies

import dev.doona.android.engine.EngineException
import dev.doona.android.engine.ErrorCode
import dev.doona.android.feature.model.CoreNodePlaces
import dev.doona.android.feature.model.RvLabel
import dev.doona.android.feature.model.RvRuleEnd
import dev.doona.android.feature.model.RvRuleMatch
import dev.doona.android.honk.HonkFixtures
import dev.doona.android.honk.PolicyHonkFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/** honk answers with its contract's examples. */
class HonkPolicyReadTest {
    private val honk = PolicyHonkFixtures.running()

    @Test
    fun rulesNameTheirGroupsByNameAndEndWithTheFallback() {
        val read = HonkPolicyRead.read(honk)
        val groups = read.policyGroups(read.nodes(CoreNodePlaces))

        val (curl, range, rest) = read.policyRules(groups)

        assertEquals(RvRuleEnd.Direct, curl.end)
        assertNull(curl.group)
        assertEquals(RvLabel.Text("proxy"), range.group?.name)
        assertEquals(RvRuleMatch.Rest, rest.match)
        assertEquals(RvRuleEnd.Reject, rest.end)
        assertEquals(listOf("node-hk-01"), read.nodes(CoreNodePlaces).keys.toList())
    }

    @Test
    fun aSelectedSubgroupCanResolveToDifferentLeavesOrNoLeafOnUdp() {
        val api = PolicyHonkFixtures.honkGroups()
        val original = HonkFixtures.body("groups/honk-groups-g-出口")
        val (head, udp) = original.split("\"udp\":", limit = 2)
        for (leaf in listOf("n-0001938e00000000", null)) {
            val tail = udp.replaceFirst("\"n-0001938f00000000\"", leaf?.let { "\"$it\"" } ?: "null")
            api.on("GET", "/api/v1/groups/g-出口", 200, "$head\"udp\":$tail")
            val read = HonkPolicyRead.read(api)
            val group = read.policyGroups(read.nodes(CoreNodePlaces)).getValue("g-出口")
            assertEquals("g-香港", group.selectedId)
            assertEquals("n-0001938f00000000" to leaf, group.tcpLeaf?.id to group.udpLeaf?.id)
        }
    }

    @Test
    fun theMainGroupIsTheOneTheFallbackSendsTo() {
        assertNull("the contract's fallback is block", HonkPolicyRead.read(honk).mainGroupId)

        honk.on("GET", "/api/v1/rules", 200, HonkFixtures.body("rules/running").replace("\"outbound\": \"block\"", "\"outbound\": \"proxy\""))

        assertEquals("group-proxy", HonkPolicyRead.read(honk).mainGroupId)
    }

    @Test
    fun aGenerationHonkCannotPinIsBusy() {
        honk.on("GET", "/api/v1/rules", 503, HonkFixtures.body("errors/snapshot-unavailable"))

        assertEquals(ErrorCode.Busy, assertThrows(EngineException::class.java) { HonkPolicyRead.read(honk) }.code)
    }
}
