package dev.doona.android.feature.policies

import dev.doona.android.engine.ErrorCode
import dev.doona.android.feature.model.RvRuleSource
import dev.doona.android.feature.model.rvTemplate
import dev.doona.android.honk.FakeHonk
import dev.doona.android.honk.HonkFixtures
import dev.doona.android.honk.NativeConfig
import dev.doona.android.honk.PolicyHonkFixtures
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/** 範本 and its review on honk serving the RV sample ([PolicyHonkFixtures]). */
class TemplateViewModelTest {
    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `範本 lists doona's templates, marks the one honk runs, and a failed read says so until 重試`() = runTest {
        val honk = PolicyHonkFixtures.rvSample().apply { on("GET", "/api/v1/config", 503, HonkFixtures.body("errors/snapshot-unavailable")) }
        val vm = TemplateViewModel(honk, io = UnconfinedTestDispatcher(testScheduler))
        advanceUntilIdle()
        assertEquals(ErrorCode.Busy, vm.uiState.value.failure)
        assertNull(vm.uiState.value.templates)

        honk.on("GET", "/api/v1/config", 200, HonkFixtures.body("config/rv-sample"))
        vm.retry()
        advanceUntilIdle()
        assertNull(vm.uiState.value.failure)
        assertEquals(RvRuleSource.Template(rvTemplate("cn-direct")), vm.uiState.value.source)
        assertEquals(HonkTemplates.ids.map { rvTemplate(it) }, vm.uiState.value.templates)
    }

    @Test
    fun `without a GeoSite file honk loaded, every template that reads GeoSite needs one`() = runTest {
        val honk = PolicyHonkFixtures.handWritten().apply { on("GET", "/api/v1/geodata", 404, HonkFixtures.body("errors/snapshot-unavailable")) }
        val vm = TemplateViewModel(honk, io = UnconfinedTestDispatcher(testScheduler))
        advanceUntilIdle()
        assertEquals(RvRuleSource.File, vm.uiState.value.source)
        assertEquals(HonkTemplates.ids.map { rvTemplate(it, needsGeosite = true) }, vm.uiState.value.templates)
    }

    @Test
    fun `套用 writes the template into the routing's file, waits for honk, then leaves, and a stale file stays failed until 套用 succeeds`() = runTest {
        val honk = PolicyHonkFixtures.handWritten().apply {
            on("PUT", "/api/v1/config/sources/source-main", 412, HonkFixtures.body("errors/stale-revision"))
            on("GET", "/api/v1/operations/op-config-01", 200, HonkFixtures.body("operations/reload-complete"))
        }
        val vm = TemplateImpactViewModel(honk, "services", io = UnconfinedTestDispatcher(testScheduler))
        advanceUntilIdle()
        assertEquals(RvRuleSource.File, vm.uiState.value.impact?.from)
        val reads = honk.calls.count { it.path == "/api/v1/config" }
        var left = 0
        vm.apply { left++ }
        advanceUntilIdle()
        assertEquals(ErrorCode.Busy, vm.uiState.value.failure)
        assertEquals(0, left)

        honk.on("PUT", "/api/v1/config/sources/source-main", 202, HonkFixtures.body("config-replace/queued"))
        assertEquals(reads, honk.calls.count { it.path == "/api/v1/config" })
        vm.apply { left++ }
        advanceUntilIdle()
        assertEquals(2, honk.calls.count { it.method == "PUT" })
        assertNull(vm.uiState.value.failure)
        assertEquals(1, left)
        val put = honk.calls.last { it.method == "PUT" }
        assertEquals("\"${config(honk).sources.single().sha256}\"", put.headers["If-Match"])
        val written = Json.parseToJsonElement(put.body!!).jsonObject.getValue("content").jsonPrimitive.content
        assertEquals(checkNotNull(HonkRouting.of(config(honk))).withTemplate("services"), written)
    }

    @Test
    fun `套用 does nothing before the review is read`() = runTest {
        val honk = PolicyHonkFixtures.handWritten()
        val vm = TemplateImpactViewModel(honk, "services", io = StandardTestDispatcher(testScheduler))
        vm.apply {}
        assertEquals(emptyList<String>(), honk.calls.filter { it.method == "PUT" }.map { it.path })
        assertNull(vm.uiState.value.impact)
    }

    private fun config(honk: FakeHonk) = NativeConfig(honk).config()
}
