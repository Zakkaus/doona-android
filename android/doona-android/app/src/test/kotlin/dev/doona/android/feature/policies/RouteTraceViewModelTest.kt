package dev.doona.android.feature.policies

import dev.doona.android.engine.ErrorCode
import dev.doona.android.honk.HonkConfig
import dev.doona.android.honk.HonkFixtures
import dev.doona.android.honk.HonkRules
import dev.doona.android.honk.HonkTrace
import dev.doona.android.honk.HonkTraceInput
import dev.doona.android.honk.Transport
import dev.doona.android.honk.nativeJson
import dev.doona.android.vpn.tunCarries
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/** 路由測試 on honk's contract examples: `POST /routing/trace` with the `/rules` of the same generation. */
@OptIn(ExperimentalCoroutinesApi::class)
class RouteTraceViewModelTest {
    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun honk() = HonkFixtures.running().apply {
        on("GET", "/api/v1/config", 200, HonkFixtures.body("config/editable"))
        on("POST", "/api/v1/routing/trace", 200, HonkFixtures.body("routing-trace/indeterminate"))
    }

    @Test
    fun `the matched rule answers at its line, after a rule the trace cannot decide`() {
        val honk = honk()
        val vm = RouteTraceViewModel(honk, ::tunCarries, Dispatchers.Unconfined)
        vm.query(RvTraceQuery("198.51.100.20", "443"))
        vm.trace()
        val expected = RvTraceResult("routing.dae", 13, "dip(198.51.100.0/24) -> proxy", "proxy", "hk-01", skipped = "routing.dae" to 12)
        assertEquals(expected, vm.uiState.value.result)
        val sent = honk.calls.single { it.path == "/api/v1/routing/trace" }.body
        assertEquals("""{"input":{"network":"tcp","dst_port":443,"dst_ip":"198.51.100.20"}}""", sent)
    }

    @Test
    fun `a reload between the trace and the rules read fails as busy, and a new destination drops the answer`() {
        val honk = honk().apply { on("GET", "/api/v1/rules", 200, HonkFixtures.body("rules/running").replace("generation-42", "generation-43")) }
        val vm = RouteTraceViewModel(honk, ::tunCarries, Dispatchers.Unconfined)
        vm.query(RvTraceQuery("198.51.100.20", "443"))
        vm.trace()
        assertEquals(ErrorCode.Busy, vm.uiState.value.failure)
        vm.query(RvTraceQuery("198.51.100.21", "443"))
        assertNull(vm.uiState.value.failure)
    }

    @Test
    fun `the fallback answers under 其餘流量, and LAN stays outside the tunnel`() {
        val input = HonkTraceInput(Transport.Udp, 53, dstIp = "192.168.1.20")
        val result = rvTraceResult(
            nativeJson.decodeFromString(HonkTrace.serializer(), HonkFixtures.body("routing-trace/lan-fallback")),
            nativeJson.decodeFromString(HonkRules.serializer(), HonkFixtures.body("rules/running")),
            nativeJson.decodeFromString(HonkConfig.serializer(), HonkFixtures.body("config/editable")),
            groups = emptyList(),
            nodeNames = emptyMap(),
            input = input,
            carries = ::tunCarries,
        )
        assertEquals(RvTraceResult("", 0, "fallback: block", "block", fallback = true, captured = false), result)
    }

    @Test
    fun `a domain with no resolved address has no tunnel coverage verdict`() {
        val vm = RouteTraceViewModel(honk(), ::tunCarries, Dispatchers.Unconfined)
        vm.query(RvTraceQuery("nas.example", "443"))
        vm.trace()
        assertNull(requireNotNull(vm.uiState.value.result).captured)
    }

    @Test
    fun `an address goes as dst_ip and anything else as domain`() {
        assertEquals("example.com", traceInput(RvTraceQuery("example.com", "443"), ::tunCarries).domain)
        assertEquals("2001:db8::1", traceInput(RvTraceQuery("[2001:db8::1]", "443"), ::tunCarries).dstIp)
        val udp = traceInput(RvTraceQuery(" 1.1.1.1 ", "53", RvTraceProto.Udp), ::tunCarries)
        assertEquals(Triple(Transport.Udp, 53, "1.1.1.1"), Triple(udp.network, udp.dstPort, udp.dstIp))
    }
}
