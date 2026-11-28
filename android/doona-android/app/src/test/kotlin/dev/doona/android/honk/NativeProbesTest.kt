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

    private companion object {
        const val ERROR = """"error": {"code": "route_unavailable", "message": "No route to the target.", "details": null}"""
    }
}
