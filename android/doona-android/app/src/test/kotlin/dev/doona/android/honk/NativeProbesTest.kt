package dev.doona.android.honk

import dev.doona.android.engine.EngineException
import dev.doona.android.engine.ErrorCode
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

class NativeProbesTest {
    private val api = HonkFixtures.rvSample().apply {
        on("POST", "/api/v1/probes", 202, HonkFixtures.body("probes/queued"))
        on("GET", "/api/v1/operations/op-01HZX4K8W9", 200, HonkFixtures.body("operations/probe-complete"))
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
        val error = runCatching { NativeProbes(limited).groups(listOf("g1", "g2")) }.exceptionOrNull()
        assertEquals(ErrorCode.Busy, (error as? EngineException)?.code)
        assertEquals(1, api.calls.count { it.path == "/api/v1/operations/op-01HZX4K8W9" })
    }

    @Test
    fun aFailedOperationDoesNotSkipTheFifthGroup() = runTest {
        val groups = (1..5).map { "g$it" }
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
        val error = runCatching { NativeProbes(routed).groups(groups) }.exceptionOrNull()
        assertEquals(ErrorCode.FetchFailed, (error as? EngineException)?.code)
        assertEquals(groups, posted)
        assertEquals(5, api.calls.count { it.method == "GET" })
    }

    @Test
    fun anAlreadyAdmittedTargetIsBusy() = runTest {
        api.on("POST", "/api/v1/probes", 429, """{"error":{"code":"rate_limited","message":"A probe for this target is already admitted."},"request_id":"r1"}""")
        val error = runCatching { NativeProbes(api).groups(listOf("g1")) }.exceptionOrNull()
        assertEquals(ErrorCode.Busy, (error as? EngineException)?.code)
    }

    private companion object {
        const val ERROR = """"error": {"code": "route_unavailable", "message": "No route to the target.", "details": null}"""
    }
}
