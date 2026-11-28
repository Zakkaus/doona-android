package dev.doona.android.honk

import dev.doona.android.engine.EngineException
import dev.doona.android.engine.ErrorCode
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A group's policy, `default` and `final` on honk's contract examples for `/groups/{id}/config`. */
class NativeGroupConfigTest {
    private val honk = FakeHonk().apply {
        on("GET", "/api/v1/groups/group-proxy/config", 200, HonkFixtures.body("group-config/current"))
        on("PATCH", "/api/v1/groups/group-proxy/config", 202, HonkFixtures.body("group-config/patch-queued"))
        on("GET", "/api/v1/operations/op-01HZX4K8WA", 200, HonkFixtures.body("operations/reload-complete"))
    }

    @Test
    fun aChangeTestsEachOldValueBeforeItReplacesItAndWaitsForTheReload() = runTest {
        val groups = NativeGroupConfig(honk)
        val before = groups.read("group-proxy")
        assertEquals(HonkGroupConfig(HonkGroupPolicy("urltest", "urltest"), HonkGroupOptions(null, "direct")), before)
        groups.write("group-proxy", before, before.copy(policy = HonkGroupPolicy("selector", "selector"), config = HonkGroupOptions("node-hk-01", "direct")))
        val patch = honk.calls.single { it.method == "PATCH" }
        val expected = """[{"op":"test","path":"/policy","value":{"kind":"urltest","native":"urltest"}},""" +
            """{"op":"replace","path":"/policy","value":{"kind":"selector","native":"selector"}},""" +
            """{"op":"test","path":"/config/default_member_id","value":null},""" +
            """{"op":"replace","path":"/config/default_member_id","value":"node-hk-01"}]"""
        assertEquals(expected, patch.body)
        assertEquals(mapOf("Content-Type" to "application/json-patch+json", "If-Match" to "*"), patch.headers)
        assertEquals("GET /api/v1/operations/op-01HZX4K8WA", honk.calls.last().let { "${it.method} ${it.path}" })
    }

    @Test
    fun nothingChangedSendsNothingAndAFailedTestIsBusy() = runTest {
        val groups = NativeGroupConfig(honk)
        val before = groups.read("group-proxy")
        groups.write("group-proxy", before, before)
        assertTrue(honk.calls.none { it.method == "PATCH" })
        honk.on("PATCH", "/api/v1/groups/group-proxy/config", 409, HonkFixtures.body("errors/snapshot-unavailable"))
        val failure = runCatching { groups.write("group-proxy", before, before.copy(config = HonkGroupOptions(null, "block"))) }.exceptionOrNull()
        assertEquals(ErrorCode.Busy, (failure as EngineException).code)
    }
}
