package dev.doona.android.feature.model

import dev.doona.android.designsystem.rv.RvPlace
import dev.doona.android.vpn.honk.Node
import dev.doona.android.vpn.honk.Provider
import dev.doona.android.vpn.nativeJson
import org.junit.Assert.assertEquals
import org.junit.Test

class HonkNodesTest {
    @Test
    fun aNodeOfHonksInlineProviderIsAddedByItsLink() {
        val providers = listOf(provider("inline", "inline", "inline"), provider("s1", "Harbor Cloud", "subscription")).associateBy { it.id }

        val inline = rvNode(node("inline"), providers, NOWHERE)
        val harbor = rvNode(node("s1"), providers, NOWHERE)
        val none = rvNode(node(null), providers, NOWHERE)

        assertEquals(RV_INLINE to "", inline.subscriptionId to inline.subscription)
        assertEquals(RV_INLINE to "", none.subscriptionId to none.subscription)
        assertEquals("s1" to "Harbor Cloud", harbor.subscriptionId to harbor.subscription)
    }

    @Test
    fun theTrendIsTheLatencyLessTheAverageOfTen() {
        val faster = rvNode(probed("healthy", 45.0, 51.4), emptyMap(), NOWHERE)
        val young = rvNode(probed("healthy", 45.0, null), emptyMap(), NOWHERE)
        val down = rvNode(probed("unavailable", null, 51.4), emptyMap(), NOWHERE)

        assertEquals(45 to -6, faster.latencyMs to faster.changeMs)
        assertEquals(45 to null, young.latencyMs to young.changeMs)
        assertEquals(null, down.changeMs)
        assertEquals(null, faster.withTest(40).trendMs)
    }

    private fun node(providerId: String?, health: String = "") = nativeJson.decodeFromString(
        Node.serializer(),
        """{"id":"n1","name":"hk-03","protocol":"trojan","subscription_tag":null,"provider_id":${providerId?.let { "\"$it\"" }},"group_ids":[],"health":[$health]}""",
    )

    private fun probed(state: String, latency: Double?, avg10: Double?) = node(
        null,
        """{"transport":"tcp","state":"$state","latency_ms":$latency,"moving_avg_ms":null,"avg10_ms":$avg10,""" +
            """"observed_at":"2026-08-15T10:00:00Z","error":null}""",
    )

    private fun provider(id: String, name: String, kind: String) = nativeJson.decodeFromString(
        Provider.serializer(),
        """{"id":"$id","name":"$name","kind":"$kind","url_redacted":null,"node_count":1,"updated_at":null,"expires_at":null,""" +
            """"traffic":null,"status":"ok","last_error":null}""",
    )

    private companion object {
        val NOWHERE = NodePlaces { NodePlace(RvPlace.Other, null, it) }
    }
}
