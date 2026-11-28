package dev.doona.android.feature.policies

import dev.doona.android.engine.ErrorCode
import dev.doona.android.feature.model.RvLabel
import dev.doona.android.feature.model.RvRuleMatch
import dev.doona.android.honk.HonkFixtures
import dev.doona.android.honk.PolicyHonkFixtures
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** 新增服務規則 on honk serving the RV sample ([PolicyHonkFixtures.rvSample]). */
@OptIn(ExperimentalCoroutinesApi::class)
class ServiceRulesViewModelTest {
    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `the new rules go to the main group, and a service with a GeoSite rule already has it`() {
        val vm = ServiceRulesViewModel(PolicyHonkFixtures.rvSample(), io = Dispatchers.Unconfined)
        val ui = vm.uiState.value
        assertEquals(RvLabel.Text("Proxy"), ui.main?.name)
        assertEquals(setOf("google"), ui.rules.keys)
        assertEquals("google", (ui.rules.getValue("google").match as RvRuleMatch.GeoSite).category)
    }

    @Test
    fun `ticks are the page's own, and a failed read says why`() {
        val vm = ServiceRulesViewModel(PolicyHonkFixtures.rvSample().apply { on("GET", "/api/v1/rules", 503, HonkFixtures.body("errors/snapshot-unavailable")) }, io = Dispatchers.Unconfined)
        vm.toggle("steam")
        vm.toggle("youtube")
        vm.toggle("steam")
        assertEquals(setOf("youtube"), vm.uiState.value.selected)
        assertEquals(ErrorCode.Busy, vm.uiState.value.failure)
    }

    @Test
    fun `加入並套用 writes a GeoSite rule to the main group for each ticked service, before the fallback`() = runTest {
        val io = UnconfinedTestDispatcher(testScheduler)
        Dispatchers.setMain(io)
        val honk = PolicyHonkFixtures.handWritten().apply {
            on("GET", "/api/v1/rules", 200, HonkFixtures.body("rules/rv-sample-hand").replace("\"outbound\": \"proxy\"", "\"outbound\": \"Proxy\""))
            on("PUT", "/api/v1/config/sources/source-main", 202, HonkFixtures.body("config-replace/queued"))
            on("GET", "/api/v1/operations/op-config-01", 200, HonkFixtures.body("operations/reload-complete"))
        }
        val before = content(HonkFixtures.body("config/rv-sample-hand"))
        val vm = ServiceRulesViewModel(honk, io = io)
        vm.toggle("steam")
        vm.toggle("youtube")
        vm.apply()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.done)
        val written = content(checkNotNull(honk.calls.single { it.method == "PUT" }.body))
        assertEquals(before.take(67), written.take(67))
        assertEquals(setOf("    domain(geosite:steam) -> Proxy", "    domain(geosite:youtube) -> Proxy"), written.subList(67, 69).toSet())
        assertEquals(before.drop(67), written.drop(69))
    }

    private fun content(json: String): List<String> {
        val root = Json.parseToJsonElement(json).jsonObject
        val source = root["sources"]?.jsonArray?.get(0)?.jsonObject ?: root
        return source.getValue("content").jsonPrimitive.content.lines()
    }
}
