package dev.doona.android.vpn

import dev.doona.android.engine.EngineException
import dev.doona.android.engine.ErrorCode
import dev.doona.android.engine.Group
import dev.doona.android.engine.GroupKind
import dev.doona.android.engine.Member
import dev.doona.android.engine.Network
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

/** Response bodies are the examples of honk's native API contract (`native_api_openapi.yaml`) and `doc/en/reference/api.md`. */
class NativeCatalogTest {
    private val api = FakeNativeApi()
    private val catalog = NativeCatalog(api)

    @Test
    fun subscriptionsFollowTheCursorAndSkipTheInlineProvider() {
        api.on("GET", "/api/v1/providers", 200, """{"providers":[$PROVIDER_A],"next_cursor":"c/2"}""")
        api.on("GET", "/api/v1/providers?cursor=c%2F2", 200, """{"providers":[$INLINE,$PROVIDER_B],"next_cursor":null}""")

        assertEquals(listOf("provider-a", "provider-b"), catalog.subscriptions().map { it.id })
        assertEquals(listOf("/api/v1/providers", "/api/v1/providers?cursor=c%2F2"), api.calls.map { it.path })
    }

    @Test
    fun trafficAndExpiryComeFromUserinfoAndStayNullWithout() {
        api.on("GET", "/api/v1/providers", 200, """{"providers":[$PROVIDER_A,$PROVIDER_B],"next_cursor":null}""")

        val (a, b) = catalog.subscriptions()

        assertEquals("https://subscriptions.example.com/a/sub?token=secret-a", a.url)
        assertEquals(1, a.nodes)
        assertEquals(1_048_576L + 2_097_152L, a.usedBytes)
        assertEquals(107_374_182_400L, a.totalBytes)
        assertEquals(Instant.parse("2026-10-01T00:00:00Z").toEpochMilli(), a.expiresAtEpochMs)
        assertNull(b.usedBytes)
        assertNull(b.totalBytes)
        assertNull(b.expiresAtEpochMs)
        assertNull(b.error)
    }

    @Test
    fun addSubscriptionPostsAProviderWithItsUserAgent() {
        api.on("POST", "/api/v1/providers", 201, PROVIDER_B)

        val added = catalog.addSubscription("https://subscriptions.example.com/sub?token=secret", "provider-b", "clash.meta")

        assertEquals("provider-b", added.id)
        assertEquals(
            Json.parseToJsonElement(
                """{"name":"provider-b","kind":"subscription","url":"https://subscriptions.example.com/sub?token=secret",""" +
                    """"user_agent":"clash.meta"}""",
            ),
            Json.parseToJsonElement(api.calls.single().body!!),
        )
    }

    @Test
    fun removingAnUnknownSubscriptionIsNotFound() = runTest {
        api.on("DELETE", "/api/v1/providers/provider-x", 200, """{"deleted":0}""")

        assertCode(ErrorCode.NotFound) { catalog.removeSubscription("provider-x") }
    }

    @Test
    fun refreshPollsTheOperationUntilItSucceeds() = runTest {
        api.on("POST", "/api/v1/providers/provider-a/refresh", 202, ACCEPTED)
        api.on("GET", "/api/v1/operations/op-provider-a", 200, operation("running", "null", "null"))
        api.on("GET", "/api/v1/operations/op-provider-a", 200, operation("succeeded", PROVIDER_A, "null"))

        val refreshed = catalog.refreshSubscription("provider-a")

        assertEquals(1, refreshed.nodes)
        assertEquals(3, api.calls.size)
        assertNull(api.calls.first().body)
    }

    @Test
    fun aFailedFetchIsFetchFailed() = runTest {
        api.on("POST", "/api/v1/providers/provider-a/refresh", 202, ACCEPTED)
        val error = """{"code":"fetch_failed","message":"Provider refresh did not complete successfully.","details":null}"""
        api.on("GET", "/api/v1/operations/op-provider-a", 200, operation("failed", "null", error))

        assertCode(ErrorCode.FetchFailed) { catalog.refreshSubscription("provider-a") }
    }

    @Test
    fun aRefreshAlreadyInFlightIsBusy() = runTest {
        val inFlight = envelope("state_conflict", "A refresh for this provider is already queued or running.")
        api.on("POST", "/api/v1/providers/provider-a/refresh", 409, inFlight)

        assertCode(ErrorCode.Busy) { catalog.refreshSubscription("provider-a") }
    }

    @Test
    fun refreshingAnUnknownProviderIsNotFound() = runTest {
        api.on("POST", "/api/v1/providers/provider-x/refresh", 404, envelope("resource_not_found", "The requested resource was not found."))

        assertCode(ErrorCode.NotFound) { catalog.refreshSubscription("provider-x") }
    }

    @Test
    fun aLinkHonkRejectsIsInvalidArgument() = runTest {
        val rejected = """{"error":{"code":"unsupported_value","message":"XHTTP xPaddingBytes must stay within 1-8192",""" +
            """"details":{"fields":["name","link"]}},"request_id":"request-01HZX4K8W6"}"""
        api.on("POST", "/api/v1/nodes", 422, rejected)

        assertCode(ErrorCode.InvalidArgument) { catalog.addNode("hk-03", "vless://uuid@example.com:443?security=tls#hk-03") }
        assertEquals(
            Json.parseToJsonElement("""{"name":"hk-03","link":"vless://uuid@example.com:443?security=tls#hk-03"}"""),
            Json.parseToJsonElement(api.calls.single().body!!),
        )
    }

    @Test
    fun aStaticNodeHasNoSubscription() {
        api.on("POST", "/api/v1/nodes", 201, CREATED_NODE)

        val added = catalog.addNode("hk-03", "vless://uuid@example.com:443?security=tls#hk-03")

        assertEquals(Member(id = "node-hk-03", name = "hk-03"), added)
    }

    @Test
    fun groupsReadEachDetailAndTakeMemberSubscriptionsFromNodes() {
        api.on("GET", "/api/v1/nodes", 200, """{"observed_at":"2026-08-15T10:00:00Z","nodes":[$NODE_HK_01],"next_cursor":null}""")
        api.on("GET", "/api/v1/groups", 200, GROUPS)
        api.on("GET", "/api/v1/groups/group-proxy", 200, GROUP_PROXY)

        val members = listOf(
            Member(id = "node-hk-01", name = "hk-01", subscriptionId = "provider-a"),
            Member(id = "group-jp", name = "jp", isGroup = true),
        )
        val tcp = Group.Selection("node-hk-01", pinned = false)
        val udp = Group.Selection(null, pinned = false)
        assertEquals(listOf(Group("group-proxy", "proxy", GroupKind.UrlTest, members, tcp, udp)), catalog.groups())
    }

    @Test
    fun selectPutsTheMemberAndNetwork() {
        val selected = """{"group_id":"group-proxy","member_id":"node-hk-01","resolved_leaf_node_id":"node-hk-01","network":"both",""" +
            """"source":"runtime","selection_revision":"8","connections_interrupted":false}"""
        api.on("PUT", "/api/v1/groups/group-proxy/selection", 200, selected)

        catalog.select("group-proxy", "node-hk-01", null)

        val sent = api.calls.single()
        assertEquals("PUT /api/v1/groups/group-proxy/selection", "${sent.method} ${sent.path}")
        assertEquals(Json.parseToJsonElement("""{"member_id":"node-hk-01","network":"both"}"""), Json.parseToJsonElement(sent.body!!))
    }

    @Test
    fun aNullMemberClearsTheAutomaticGroupsPin() {
        val cleared = """{"group_id":"group-auto","network":"tcp","selection_revision":"9","connections_interrupted":false,""" +
            """"selection":{"tcp":{"member_id":"node-sg-01","resolved_leaf_node_id":"node-sg-01","source":"policy"},"udp":null}}"""
        api.on("DELETE", "/api/v1/groups/group-auto/selection?network=tcp", 200, cleared)

        catalog.select("group-auto", null, Network.Tcp)

        assertEquals(FakeNativeApi.Call("DELETE", "/api/v1/groups/group-auto/selection?network=tcp", null), api.calls.single())
    }

    @Test
    fun clearingASelectorIsInvalidArgument() = runTest {
        val conflict = envelope("state_conflict", "The current state prevents this change.")
        api.on("DELETE", "/api/v1/groups/group-proxy/selection?network=both", 409, conflict)

        assertCode(ErrorCode.InvalidArgument) { catalog.select("group-proxy", null, null) }
    }

    private suspend fun assertCode(code: ErrorCode, block: suspend () -> Unit) {
        val thrown = runCatching { block() }.exceptionOrNull()
        assertEquals(code, (thrown as? EngineException)?.code)
    }

    private companion object {
        const val PROVIDER_A = """{"id":"provider-a","name":"provider-a","kind":"subscription",
            "url_redacted":"https://subscriptions.example.com/a/sub?token=secret-a","node_count":1,
            "updated_at":"2026-08-15T10:00:00Z","expires_at":"2026-10-01T00:00:00Z",
            "traffic":{"upload_bytes":"1048576","download_bytes":"2097152","total_bytes":"107374182400"},
            "status":"ok","last_error":null,"download":{"route":"routing","group_id":null}}"""

        const val PROVIDER_B = """{"id":"provider-b","name":"provider-b","kind":"subscription",
            "url_redacted":"https://subscriptions.example.com/sub?token=secret","node_count":0,"updated_at":null,
            "expires_at":null,"traffic":null,"status":"stale","last_error":null,
            "download":{"route":"routing","group_id":null}}"""

        const val INLINE = """{"id":"inline","name":"inline","kind":"inline","url_redacted":null,"node_count":1,
            "updated_at":null,"expires_at":null,"traffic":null,"status":"ok","last_error":null,"download":null}"""

        const val ACCEPTED = """{"operation_id":"op-provider-a","kind":"provider_refresh","status":"queued",
            "href":"/api/v1/operations/op-provider-a"}"""

        const val NODE_HK_01 = """{"id":"node-hk-01","name":"hk-01","protocol":"vless","stream_transport":"xhttp",
            "subscription_tag":"provider-a","provider_id":"provider-a","group_ids":["group-proxy"],"health":[]}"""

        const val CREATED_NODE = """{"id":"node-hk-03","name":"hk-03","protocol":"vless","subscription_tag":null,
            "provider_id":"inline","group_ids":["group-proxy"],"health":[]}"""

        const val GROUPS = """[{"id":"group-proxy","name":"proxy","icon":"https://icons.example/proxy.png",
            "config_revision":"17","policy":{"kind":"urltest","native":"urltest"},"member_count":2,
            "selection":{"tcp_member_id":"node-hk-01","udp_member_id":null}}]"""

        const val GROUP_PROXY = """{"id":"group-proxy","name":"proxy","icon":"https://icons.example/proxy.png",
            "config_revision":"17","policy":{"kind":"urltest","native":"urltest"},
            "members":[{"id":"node-hk-01","name":"hk-01","kind":"node"},{"id":"group-jp","name":"jp","kind":"group"}],
            "config":{"default_member_id":null,"final_outbound":"direct","check_url":null,"check_interval":30,
            "tolerance":50,"idle_timeout":null,"interrupt_connections":false},
            "runtime":{"selection":{"tcp":{"member_id":"node-hk-01","resolved_leaf_node_id":"node-hk-01","source":"health"},
            "udp":null},"health":[]},
            "capabilities":{"can_select":false,"can_override":true,"supports_nested_groups":true,
            "mutable_config":["policy"],"probe_transports":["tcp"]}}"""

        fun operation(status: String, result: String, error: String) = """{"operation_id":"op-provider-a",
            "kind":"provider_refresh","status":"$status","created_at":"2026-08-15T09:59:59Z",
            "started_at":"2026-08-15T10:00:00Z","finished_at":null,"result":$result,"error":$error}"""

        fun envelope(code: String, message: String) =
            """{"error":{"code":"$code","message":"$message","details":null},"request_id":"request-01HZX4K8W6"}"""
    }
}

/** Serves queued replies per method and target, in order, and records every call. */
private class FakeNativeApi : NativeApi {
    data class Call(val method: String, val path: String, val body: String?)

    val calls = mutableListOf<Call>()
    private val replies = mutableMapOf<String, ArrayDeque<NativeResponse>>()

    fun on(method: String, path: String, status: Int, body: String) {
        replies.getOrPut("$method $path") { ArrayDeque() }.addLast(NativeResponse(status, body))
    }

    override fun call(method: String, path: String, body: String?, headers: Map<String, String>): NativeResponse {
        calls += Call(method, path, body)
        return checkNotNull(replies["$method $path"]?.removeFirstOrNull()) { "no reply for $method $path" }
    }
}
