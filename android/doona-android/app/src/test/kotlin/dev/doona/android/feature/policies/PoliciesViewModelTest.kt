package dev.doona.android.feature.policies

import dev.doona.android.engine.Engine
import dev.doona.android.engine.EngineException
import dev.doona.android.engine.ErrorCode
import dev.doona.android.engine.Mode
import dev.doona.android.engine.fake.FakeEngine
import dev.doona.android.engine.fake.rvSampleEngine
import dev.doona.android.feature.model.RvNodeSort
import dev.doona.android.feature.model.RvRouteMode
import dev.doona.android.feature.model.RvRuleSource
import dev.doona.android.vpn.FakeHonk
import dev.doona.android.vpn.HonkFixtures
import dev.doona.android.vpn.PolicyHonkFixtures
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** 策略 on honk serving the RV sample ([PolicyHonkFixtures.rvSample]); the mode alone is the engine's. */
class PoliciesViewModelTest {
    private val honk = PolicyHonkFixtures.rvSample().apply {
        on("PUT", "/api/v1/config/sources/source-main", 202, HonkFixtures.body("config-replace/queued"))
        on("GET", "/api/v1/operations/op-config-01", 200, HonkFixtures.body("operations/reload-complete"))
        on("PUT", "/api/v1/groups/g-Proxy/selection", 200, "{}")
        on("POST", "/api/v1/probes", 202, HonkFixtures.body("probes/queued"))
        on("GET", "/api/v1/operations/op-01HZX4K8W9", 200, HonkFixtures.body("operations/probe-complete"))
    }

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    /** `WhileSubscribed` only follows the engine's mode while someone collects, as the screen does. */
    private fun TestScope.collected(engine: Engine = Modes()) =
        PoliciesViewModel(engine, honk, clock = { NOW }, io = UnconfinedTestDispatcher(testScheduler)).also { vm ->
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
            advanceUntilIdle()
        }

    /** The sample engine, recording the modes set, which fail with [result]'s failure while it has one. */
    private class Modes(val base: FakeEngine = rvSampleEngine()) : Engine by base {
        val calls = mutableListOf<String>()
        var result: Result<Unit> = Result.success(Unit)

        override suspend fun setMode(mode: Mode): Result<Unit> {
            calls += "setMode $mode"
            return result.onSuccess { base.setMode(mode) }
        }
    }

    private fun writes() = honk.calls.filter { it.method != "GET" }.map { "${it.method} ${it.path}" }

    private fun replaced(): String = Json.parseToJsonElement(honk.calls.last { it.method == "PUT" }.body!!).jsonObject.getValue("content").jsonPrimitive.content

    @Test
    fun `the profile is honk's groups, subscriptions, template and switches`() = runTest {
        val state = collected().uiState.value

        assertFalse(state.loading)
        assertNull(state.failure)
        assertEquals("g-Proxy", state.profile!!.main!!.id)
        assertEquals(7, state.profile.groups.size)
        assertEquals(listOf("Harbor Cloud", "Lantern Net"), state.profile.subscriptions.map { it.name })
        assertEquals("cn-direct", (state.profile.source as RvRuleSource.Template).template.id)
        assertEquals(RV_POLICY_CARD_RULES, state.profile.rules.size)
        assertTrue(state.state.blockAds)
        assertFalse(state.state.blockQuic)
    }

    @Test
    fun `a tap on the selected mode calls nothing, and another sets it on the engine`() = runTest {
        val engine = Modes()
        val vm = collected(engine)
        val state = vm.uiState.value.state
        assertEquals(RvRouteMode.Rule, state.mode)

        vm.onChange(state.copy(mode = RvRouteMode.Rule))
        assertEquals(emptyList<String>(), engine.calls)

        vm.onChange(state.copy(mode = RvRouteMode.Global))
        assertEquals(listOf("setMode Global"), engine.calls)
        assertEquals(RvRouteMode.Global, vm.uiState.value.state.mode)
        assertEquals(emptyList<String>(), writes())
    }

    @Test
    fun `blocking QUIC puts its rule first in the routing file honk read, then reads again`() = runTest {
        val vm = collected()
        val reads = honk.calls.count { it.path == "/api/v1/config" }

        vm.onChange(vm.uiState.value.state.copy(blockQuic = true))
        advanceUntilIdle()

        assertEquals(listOf("PUT /api/v1/config/sources/source-main"), writes())
        val put = honk.calls.last { it.method == "PUT" }
        assertTrue(put.headers.getValue("If-Match").startsWith("\""))
        val routing = replaced().lines().dropWhile { it.trim() != "routing {" }
        assertEquals("l4proto(udp) && dport(443) -> block", routing[1].trim())
        assertEquals(reads + 1, honk.calls.count { it.path == "/api/v1/config" })
        assertNull(vm.uiState.value.failure)
    }

    @Test
    fun `封鎖廣告 off takes its DNS rule out of the file, and a failed write shows in the banner`() = runTest {
        val vm = collected()
        honk.on("PUT", "/api/v1/config/sources/source-main", 412, HonkFixtures.body("errors/stale-revision"))

        vm.onChange(vm.uiState.value.state.copy(blockAds = false))
        advanceUntilIdle()

        assertEquals(PolicyFailure(PolicyAction.Ads, ErrorCode.Busy), vm.uiState.value.failure)
        assertTrue("honk still runs the rule", vm.uiState.value.state.blockAds)
        assertFalse(replaced().contains("qname(geosite:category-ads-all) -> reject"))

        honk.on("PUT", "/api/v1/config/sources/source-main", 202, HonkFixtures.body("config-replace/queued"))
        vm.retry()
        advanceUntilIdle()

        assertNull(vm.uiState.value.failure)
        assertEquals(2, writes().size)
    }

    @Test
    fun `the order stays on the page`() = runTest {
        val vm = collected()

        vm.onChange(vm.uiState.value.state.copy(nodeSort = RvNodeSort.Name))

        assertEquals(emptyList<String>(), writes())
        assertEquals(RvNodeSort.Name, vm.uiState.value.state.nodeSort)
    }

    @Test
    fun `choosing the main node selects it in the main group on both networks`() = runTest {
        val vm = collected()
        val main = vm.uiState.value.profile!!.main!!
        val other = main.members.first { it.id != main.selectedId }.id

        vm.onChange(vm.uiState.value.state.choose(main.id, main.selectedId))
        assertEquals(emptyList<String>(), writes())

        vm.onChange(vm.uiState.value.state.choose(main.id, other))
        advanceUntilIdle()

        assertEquals(listOf("PUT /api/v1/groups/g-Proxy/selection"), writes())
        val body = Json.parseToJsonElement(honk.calls.last { it.method == "PUT" }.body!!).jsonObject
        assertEquals(other, body.getValue("member_id").jsonPrimitive.content)
        assertEquals("both", body.getValue("network").jsonPrimitive.content)
    }

    @Test
    fun `testing probes every group and reads again`() = runTest {
        val vm = collected()

        vm.testAll()
        advanceUntilIdle()

        assertEquals(8, writes().count { it == "POST /api/v1/probes" })
        val first = Json.parseToJsonElement(honk.calls.first { it.method == "POST" }.body!!).jsonObject
        assertEquals("g-Proxy", first.getValue("target").jsonObject.getValue("group_id").jsonPrimitive.content)
        assertNull(vm.uiState.value.failure)
    }

    @Test
    fun `updating refreshes every subscription in order and shows the first failure`() = runTest {
        val vm = collected()
        honk.on("POST", "/api/v1/providers/s1/refresh", 503, HonkFixtures.body("errors/snapshot-unavailable"))
        honk.on("POST", "/api/v1/providers/s2/refresh", 503, HonkFixtures.body("errors/snapshot-unavailable"))

        vm.updateSubscriptions()
        advanceUntilIdle()

        assertEquals(listOf("POST /api/v1/providers/s1/refresh", "POST /api/v1/providers/s2/refresh"), writes())
        assertEquals(PolicyFailure(PolicyAction.Update, ErrorCode.Busy), vm.uiState.value.failure)
    }

    @Test
    fun `a failed first read shows its failure, and 重試 reads again`() = runTest {
        honk.on("GET", "/api/v1/rules", 503, HonkFixtures.body("errors/snapshot-unavailable"))
        val vm = collected()

        assertFalse(vm.uiState.value.loading)
        assertNull(vm.uiState.value.profile)
        assertEquals(PolicyFailure(PolicyAction.Read, ErrorCode.Busy), vm.uiState.value.failure)

        honk.on("GET", "/api/v1/rules", 200, HonkFixtures.body("rules/rv-sample"))
        vm.retry()
        advanceUntilIdle()

        assertNull(vm.uiState.value.failure)
        assertEquals("g-Proxy", vm.uiState.value.profile!!.main!!.id)
    }

    @Test
    fun `no subscription when honk has none`() = runTest {
        honk.on("GET", "/api/v1/providers", 200, """{"providers": [], "next_cursor": null}""")

        assertTrue(collected().uiState.value.noSubscription)
    }

    private companion object {
        const val NOW = 1_780_000_000_000L
    }
}
