package dev.doona.android.feature.policies

import dev.doona.android.engine.EngineException
import dev.doona.android.engine.ErrorCode
import dev.doona.android.feature.model.CoreNodePlaces
import dev.doona.android.feature.model.RvLabel
import dev.doona.android.feature.model.RvRuleEnd
import dev.doona.android.feature.model.RvRuleMatch
import dev.doona.android.vpn.HonkFixtures
import dev.doona.android.vpn.PolicyHonkFixtures
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
