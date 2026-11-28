package dev.doona.android.feature.connections

import dev.doona.android.feature.model.CoreNodePlaces
import dev.doona.android.feature.model.RvGroupKind
import dev.doona.android.feature.model.RvMatchKind
import dev.doona.android.feature.model.RvRuleEnd
import dev.doona.android.feature.model.RvRuleMatch
import dev.doona.android.honk.HonkConnection
import dev.doona.android.honk.HonkFixtures
import dev.doona.android.honk.NativeCatalog
import dev.doona.android.honk.NativeConnections
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** [rvConnections]: honk's connections, groups and nodes (the RV sample in honk's schema) in 連線's words. */
class ConnectionsModelTest {
    private val honk = HonkFixtures.rvSample().apply { on("GET", LIST_PATH, 200, HonkFixtures.body("connections/connections-sample")) }
    private val catalog = NativeCatalog(honk)
    private val groups = catalog.groups()
    private val nodes = catalog.nodes()

    private fun map(connections: List<HonkConnection>) = connections.map(connectionMapper(groups, nodes, CoreNodePlaces))

    @Test
    fun aProxiedConnectionNamesItsGroupNodeAndRule() {
        val chrome = map(NativeConnections(honk).list().rows).first { it.id == "tcp-sample-01" }

        assertEquals("Chrome", chrome.title)
        assertEquals("gemini.google.com", chrome.host)
        assertEquals(443, chrome.port)
        assertEquals("gemini.google.com:443", chrome.endpoint)
        assertEquals(RvConnectionGroup("AI", RvGroupKind.Select), chrome.group)
        assertEquals("Tokyo 02", chrome.node!!.name)
        assertEquals("Trojan", chrome.node!!.protocol)
        val rule = chrome.rule as RvRuleMatch.Value
        assertEquals(RvMatchKind.Full to "gemini.google.com", rule.kind to rule.value)
        assertEquals(5, chrome.ruleIndex)
        assertNull(chrome.end)
    }

    @Test
    fun aDirectConnectionEndsInDirectWithNoNode() {
        val wechat = map(NativeConnections(honk).list().rows).first { it.id == "tcp-sample-03" }

        assertEquals(RvRuleEnd.Direct, wechat.end)
        assertNull(wechat.group)
        assertNull(wechat.node)
        assertEquals("CN", (wechat.rule as RvRuleMatch.GeoIp).code)
    }

    @Test
    fun blockAMissingGroupAndNoOutboundEachSaySo() {
        val shown = map(listOf(connection("block"), connection("Gone"), connection(null)))

        assertEquals(listOf(RvRuleEnd.Reject, RvRuleEnd.Missing, null), shown.map { it.end })
        assertEquals(listOf(null, null, null), shown.map { it.group })
    }

    @Test
    fun aConnectionWithNoProcessOrRuleIsTitledByItsHost() {
        val shown = map(listOf(connection("direct"))).single()

        assertEquals("91.108.56.130", shown.title)
        assertNull(shown.rule)
        assertNull(shown.ruleIndex)
        assertEquals("[2001:db8::1]:443", map(listOf(connection("direct").copy(host = "2001:db8::1"))).single().endpoint)
    }

    private fun connection(outbound: String?) = HonkConnection(
        id = "c",
        udp = false,
        host = "91.108.56.130",
        port = 443,
        hasDomain = false,
        process = null,
        outbound = outbound,
        chain = emptyList(),
        ruleId = null,
        ruleExpression = null,
        startedAtEpochMs = 0,
        upBytes = 0,
        downBytes = 0,
    )

    private companion object {
        const val LIST_PATH = "/api/v1/connections?limit=1000&detail=full"
    }
}
