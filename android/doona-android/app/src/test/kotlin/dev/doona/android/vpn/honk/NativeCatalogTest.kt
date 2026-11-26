package dev.doona.android.vpn.honk

import dev.doona.android.engine.EngineException
import dev.doona.android.engine.ErrorCode
import dev.doona.android.vpn.FakeHonk
import dev.doona.android.vpn.HonkFixtures
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Bodies are honk's contract shapes: the fixtures under `resources/honk/` and the operations honk answers a change with. */
class NativeCatalogTest {
    private val api = HonkFixtures.rvSample()
    private val catalog = NativeCatalog(api)

    @Test
    fun providersDecodeTrafficAndExpiryAsHonkSendsThem() {
        val (harbor, lantern) = catalog.providers()

        assertEquals("Harbor Cloud", harbor.name)
        assertEquals(ProviderKind.Subscription, harbor.kind)
        assertEquals(32, harbor.nodeCount)
        assertEquals("200000000000", harbor.traffic?.totalBytes)
        assertEquals("2026-12-31T12:00:00Z", lantern.expiresAt)
        assertNull(lantern.lastError)
    }

    @Test
    fun providersFollowTheCursor() {
        val fake = FakeHonk()
        fake.on("GET", "/api/v1/providers", 200, """{"providers":[${provider("p1")}],"next_cursor":"c/2"}""")
        fake.on("GET", "/api/v1/providers?cursor=c%2F2", 200, """{"providers":[${provider("p2")}],"next_cursor":null}""")

        assertEquals(listOf("p1", "p2"), NativeCatalog(fake).providers().map { it.id })
        assertEquals(listOf("/api/v1/providers", "/api/v1/providers?cursor=c%2F2"), fake.calls.map { it.path })
    }

    @Test
    fun nodesCarryTheirGroupsAndHealth() {
        val nodes = catalog.nodes()
        val hongKong = nodes.first()

        assertEquals(48, nodes.size)
        assertEquals("s1", hongKong.providerId)
        assertEquals(listOf("g-Proxy", "g-Auto", "g-Streaming", "g-HK"), hongKong.groupIds)
        assertEquals(33.0, hongKong.health.single().latencyMs)
    }

    @Test
    fun groupsReadEachGroupInFull() {
        val groups = catalog.groups()

        assertEquals("g-Proxy", groups.first().id)
        assertEquals(PolicyKind.Selector, groups.first().policy.kind)
        assertEquals(groups.map { "/api/v1/groups/${it.id}" }, api.calls.drop(1).map { it.path })
    }

    @Test
    fun selectingPutsTheMemberAndItsNetwork() {
        api.on("PUT", "/api/v1/groups/g-Proxy/selection", 200, "{}")

        catalog.select("g-Proxy", "n-1", SelectionNetwork.Udp)

        val body = Json.parseToJsonElement(checkNotNull(api.calls.single().body)).jsonObject
        assertEquals("n-1", body.getValue("member_id").jsonPrimitive.content)
        assertEquals("udp", body.getValue("network").jsonPrimitive.content)
    }

    @Test
    fun clearingASelectorIsInvalidArgument() {
        api.on("DELETE", "/api/v1/groups/g-Proxy/selection?network=both", 409, ERROR)

        assertCode(ErrorCode.InvalidArgument) { catalog.clearSelection("g-Proxy", SelectionNetwork.Both) }
    }

    @Test
    fun aRefreshWaitsForItsOperation() = runTest {
        api.on("POST", "/api/v1/providers/s1/refresh", 202, accepted("provider_refresh"))
        api.on("GET", OPERATION, 200, operation("provider_refresh", "succeeded", result = provider("s1")))

        assertEquals("s1", catalog.refreshProvider("s1").id)
    }

    @Test
    fun aRefreshThatCouldNotParseIsParseFailed() = runTest {
        api.on("POST", "/api/v1/providers/s1/refresh", 202, accepted("provider_refresh"))
        val error = """{"code":"fetch_failed","message":"no nodes","details":{"stage":"parse_failed"}}"""
        api.on("GET", OPERATION, 200, operation("provider_refresh", "failed", error = error))

        assertCode(ErrorCode.ParseFailed) { catalog.refreshProvider("s1") }
    }

    @Test
    fun aRefreshAlreadyInFlightIsBusy() = runTest {
        api.on("POST", "/api/v1/providers/s1/refresh", 409, ERROR)

        assertCode(ErrorCode.Busy) { catalog.refreshProvider("s1") }
    }

    @Test
    fun addingAProviderSendsItsUserAgentAndANameInUseIsInvalidArgument() = runTest {
        api.on("POST", "/api/v1/providers", 201, provider("p9"))

        assertEquals("p9", catalog.addProvider("Nine", "https://sub.example/9", "clash").id)
        val body = Json.parseToJsonElement(checkNotNull(api.calls.single().body)).jsonObject
        assertEquals("clash", body.getValue("user_agent").jsonPrimitive.content)

        api.on("POST", "/api/v1/providers", 409, ERROR)
        assertCode(ErrorCode.InvalidArgument) { catalog.addProvider("Nine", "https://sub.example/9", null) }
    }

    @Test
    fun aNodeCreatedInTheBackgroundIsReadFromItsOperation() = runTest {
        api.on("POST", "/api/v1/nodes", 202, accepted("node_create"))
        api.on("GET", OPERATION, 200, operation("node_create", "succeeded", result = NODE))

        val node = catalog.addNode("Mine", "trojan://secret@host:443")

        assertEquals("n-mine", node.id)
        assertNull(node.providerId)
    }

    @Test
    fun removingWhatHonkNoLongerHasIsNotFound() = runTest {
        api.on("DELETE", "/api/v1/nodes/n-mine", 200, """{"deleted":0}""")
        api.on("DELETE", "/api/v1/providers/gone", 404, ERROR)

        assertCode(ErrorCode.NotFound) { catalog.removeNode("n-mine") }
        assertCode(ErrorCode.NotFound) { catalog.removeProvider("gone") }
    }

    @Test
    fun aProbeAsksForOneHttpRoundTripAndWaitsForItsOperation() = runTest {
        api.on("POST", "/api/v1/probes", 202, HonkFixtures.body("operations/probe-queued"))
        api.on("GET", "/api/v1/operations/op-01HZX4K8W9", 200, HonkFixtures.body("operations/probe-complete"))

        catalog.probe(ProbeTarget.Group("g-Proxy"))
        catalog.probe(ProbeTarget.Node("n-1"))

        val (group, node) = api.calls.filter { it.method == "POST" }.map { Json.parseToJsonElement(checkNotNull(it.body)).jsonObject }
        assertEquals("""{"type":"group","group_id":"g-Proxy"}""", group.getValue("target").toString())
        assertEquals("""{"type":"node","node_id":"n-1"}""", node.getValue("target").toString())
        assertEquals("http", group.getValue("kind").jsonPrimitive.content)
        assertEquals("""["tcp"]""", group.getValue("transport").toString())
        assertEquals(2, api.calls.count { it.path == "/api/v1/operations/op-01HZX4K8W9" })
    }

    private inline fun assertCode(code: ErrorCode, block: () -> Unit) {
        val error = runCatching(block).exceptionOrNull()
        assertEquals(code, (error as? EngineException)?.code)
    }

    private companion object {
        const val OPERATION = "/api/v1/operations/op-1"
        const val ERROR = """{"error":{"code":"conflict","message":"The request conflicts with the current state."},"request_id":"request-1"}"""
        const val NODE = """{"id":"n-mine","name":"Mine","protocol":"trojan","stream_transport":"tcp","subscription_tag":null,""" +
            """"provider_id":null,"group_ids":[],"health":[]}"""

        fun provider(id: String) = """{"id":"$id","name":"$id","kind":"subscription","url_redacted":"https://sub.example/$id",""" +
            """"node_count":0,"updated_at":null,"expires_at":null,"traffic":null,"status":"ok","last_error":null}"""

        fun accepted(kind: String) = """{"operation_id":"op-1","kind":"$kind","status":"queued","href":"$OPERATION"}"""

        fun operation(kind: String, status: String, result: String = "null", error: String = "null") =
            """{"operation_id":"op-1","kind":"$kind","status":"$status","created_at":"2026-11-26T00:00:00Z",""" +
                """"started_at":"2026-11-26T00:00:00Z","finished_at":"2026-11-26T00:00:01Z","result":$result,"error":$error}"""
    }
}
