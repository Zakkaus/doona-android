package dev.doona.android.honk

import dev.doona.android.engine.EngineException
import dev.doona.android.engine.ErrorCode
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class NativeProbesTest {
    private val api = HonkFixtures.rvSample().apply {
        on("POST", "/api/v1/probes", 202, HonkFixtures.body("probes/queued"))
        on("GET", "/api/v1/operations/op-01HZX4K8W9", 200, HonkFixtures.body("operations/probe-complete"))
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun largeGroupBatchesFinishInOrderBeforeTheUngroupedNode() = runTest {
        val source = nativeJson.decodeFromString(Group.serializer(), HonkFixtures.body("groups/group-proxy"))
        val members = (1..65).map { GroupMember("m$it", "member $it", if (it == 65) MemberKind.Group else MemberKind.Node) }
        val group = Group(source.id, source.name, source.icon, source.policy, members, source.runtime, source.capabilities)
        fun node(id: String, provider: String?) = Node(id, id, null, subscriptionTag = null, providerId = provider, groupIds = emptyList(), health = emptyList())
        val targets = members.map { it.id }.chunked(64) + listOf(listOf("unlisted"))
        val job = launch { NativeProbes(api).groups(listOf(group), listOf(node("unlisted", "inline"), node("builtin", null))) }
        for ((i, ids) in targets.withIndex()) {
            val href = "/api/v1/operations/op-$i"
            api.on("POST", "/api/v1/probes", 202, HonkFixtures.body("probes/queued").replace("op-01HZX4K8W9", "op-$i"))
            val target = if (i < 2) """{"type":"group","group_id":"${group.id}"}""" else """{"type":"node","node_id":"unlisted"}"""
            val results = ids.joinToString { id ->
                """{"member_id":"$id","resolved_leaf_node_id":"${if (id == "m65") "nested-leaf" else id}","kind":"http","purpose":"shared","transport":"tcp","ip_version":"ipv4","warmth":"cold","state":"healthy","latency_ms":45,"health_updated":true,"error":null,"observed_at":"2026-08-15T10:00:00Z"}"""
            }
            val selections = """{"tcp":null,"udp":null}"""
            val result = """{"target":$target,"selection_changed":{"tcp":false,"udp":false},"selection_before":$selections,"selection_after":$selections,"results":[$results]}"""
            api.on("GET", href, 200, """{"operation_id":"op-$i","kind":"probe","status":"succeeded","created_at":"2026-08-15T09:59:59Z","started_at":"2026-08-15T10:00:00Z","finished_at":"2026-08-15T10:00:01Z","result":$result,"error":null}""")
            testScheduler.runCurrent()
            testScheduler.advanceTimeBy(1_000)
        }
        job.join()
        assertEquals(targets.indices.flatMap { listOf("/api/v1/probes", "/api/v1/operations/op-$it") }, api.calls.map { it.path })
        val bodies = api.calls.filter { it.method == "POST" }.map { Json.parseToJsonElement(it.body!!).jsonObject }
        assertEquals(members.map { it.id }.chunked(64), bodies.take(2).map { it.getValue("members").jsonArray.map { id -> id.jsonPrimitive.content } })
        assertEquals("unlisted", bodies.last().getValue("target").jsonObject.getValue("node_id").jsonPrimitive.content)
    }

    @Test
    fun aMissingUngroupedNodeDoesNotPreventOtherNodeProbes() = runTest {
        val nodes = listOf("gone", "remaining").map { Node(it, it, "trojan", subscriptionTag = null, providerId = "inline", groupIds = emptyList(), health = emptyList()) }
        api.on("POST", "/api/v1/probes", 404, HonkFixtures.body("errors/group-not-found"))
        val error = runCatching { NativeProbes(api).groups(emptyList(), nodes) }.exceptionOrNull()
        assertEquals(ErrorCode.NotFound, (error as? EngineException)?.code)
        assertEquals(nodes.map { it.id }, api.calls.map { Json.parseToJsonElement(it.body!!).jsonObject.getValue("target").jsonObject.getValue("node_id").jsonPrimitive.content })
    }

    @Test
    fun aNodeProbeAsksForOneHttpRoundTripAndWaitsForItsOperation() = runTest {
        NativeProbes(api).node("n-1")

        val body = Json.parseToJsonElement(checkNotNull(api.calls.single { it.method == "POST" }.body)).jsonObject
        assertEquals("""{"target":{"type":"node","node_id":"n-1"},"kind":"http","transport":["tcp"],"ip_version":"any","warmth":"cold"}""", body.toString())
        assertEquals(1, api.calls.count { it.path == "/api/v1/operations/op-01HZX4K8W9" })
    }

    @Test
    fun aNodeProbeHonkCouldNotRunFailsWithItsError() = runTest {
        api.on("GET", "/api/v1/operations/op-01HZX4K8W9", 200, HonkFixtures.body("operations/probe-complete").replace("\"succeeded\"", "\"failed\"").replace("\"error\": null", ERROR))

        val error = runCatching { NativeProbes(api).node("n-1") }.exceptionOrNull()

        assertEquals(ErrorCode.FetchFailed, (error as? EngineException)?.code)
    }

    @Test
    fun aRefusedGroupDoesNotAbandonAnAcceptedProbe() = runTest {
        val limited = object : NativeApi by api {
            override fun call(method: String, path: String, body: String?, headers: Map<String, String>): NativeResponse =
                if (method == "POST" && body.orEmpty().contains("\"g2\"")) {
                    NativeResponse(503, HonkFixtures.body("errors/temporarily-unavailable"))
                } else {
                    api.call(method, path, body, headers)
                }
        }
        val error = runCatching { NativeProbes(limited).groups(groups("g1", "g2")) }.exceptionOrNull()
        assertEquals(ErrorCode.Busy, (error as? EngineException)?.code)
        assertEquals(1, api.calls.count { it.path == "/api/v1/operations/op-01HZX4K8W9" })
    }

    @Test
    fun aFailedOperationDoesNotSkipTheFifthGroup() = runTest {
        val ids = (1..5).map { "g$it" }
        val posted = mutableListOf<String>()
        val routed = object : NativeApi by api {
            override fun call(method: String, path: String, body: String?, headers: Map<String, String>): NativeResponse {
                if (method != "POST") return api.call(method, path, body, headers)
                val id = Json.parseToJsonElement(checkNotNull(body)).jsonObject.getValue("target").jsonObject.getValue("group_id").toString().trim('"')
                posted += id
                val operation = if (id == "g1") {
                    HonkFixtures.body("operations/provider-refresh-failed").replace("provider_refresh", "probe").replace("op-refresh", id).replace("fetch_failed", "route_unavailable")
                } else {
                    HonkFixtures.body("operations/probe-complete").replace("op-01HZX4K8W9", id).replace("group-proxy", id)
                }
                api.on("GET", "/api/v1/operations/$id", 200, operation)
                return NativeResponse(202, HonkFixtures.body("probes/queued").replace("op-01HZX4K8W9", id))
            }
        }
        val error = runCatching { NativeProbes(routed).groups(groups(*ids.toTypedArray())) }.exceptionOrNull()
        assertEquals(ErrorCode.FetchFailed, (error as? EngineException)?.code)
        assertEquals(ids, posted)
        assertEquals(5, api.calls.count { it.method == "GET" })
    }

    @Test
    fun anAlreadyAdmittedTargetIsBusy() = runTest {
        api.on("POST", "/api/v1/probes", 429, """{"error":{"code":"rate_limited","message":"A probe for this target is already admitted."},"request_id":"r1"}""")
        val error = runCatching { NativeProbes(api).groups(groups("g1")) }.exceptionOrNull()
        assertEquals(ErrorCode.Busy, (error as? EngineException)?.code)
    }

    private companion object {
        fun groups(vararg ids: String): List<Group> {
            val source = nativeJson.decodeFromString(Group.serializer(), HonkFixtures.body("groups/group-proxy"))
            return ids.map { Group(it, source.name, source.icon, source.policy, source.members, source.runtime, source.capabilities) }
        }

        const val ERROR = """"error": {"code": "route_unavailable", "message": "No route to the target.", "details": null}"""
    }
}
