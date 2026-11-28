package dev.doona.android.feature.policies

import dev.doona.android.engine.ErrorCode
import dev.doona.android.honk.HonkFixtures
import dev.doona.android.honk.HonkTraceInput
import dev.doona.android.honk.Transport
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
    fun `an indeterminate trace reports missing inputs even when a later rule matched`() {
        val honk = honk()
        val vm = RouteTraceViewModel(honk, ::tunCarries, Dispatchers.Unconfined)
        vm.query(RvTraceQuery("198.51.100.20", "443"))
        vm.trace()
        val expected = RvTraceResult("", 0, "", null, captured = true, skipped = "routing.dae" to 12, missingInputs = listOf("pname"))
        assertEquals(expected, vm.uiState.value.result)
        val sent = honk.calls.single { it.path == "/api/v1/routing/trace" }.body
        assertEquals("""{"input":{"network":"tcp","dst_port":443,"dst_ip":"198.51.100.20"}}""", sent)
    }

    @Test
    fun `a known outbound does not identify the matching rule`() {
        val honk = honk().apply {
            on(
                "POST",
                "/api/v1/routing/trace",
                200,
                HonkFixtures.body("routing-trace/indeterminate")
                    .replace("\"decision\": \"indeterminate\"", "\"decision\": \"determinate\"").replace("\"outbound\": null", "\"outbound\": \"direct\"")
                    .replace("\"dst_ip\": \"198.51.100.20\"", "\"dst_ip\": null").replace("dip(198.51.100.0/24)", "domain(example.org)")
                    .replace("pname(curl)", "dip(192.0.2.0/24)").replace("\"pname\"", "\"dst_ip\"").replace(" -> proxy", " -> direct"),
            )
        }
        val vm = RouteTraceViewModel(honk, ::tunCarries, Dispatchers.Unconfined)
        vm.query(RvTraceQuery("example.org", "443"))
        vm.trace()
        val result = checkNotNull(vm.uiState.value.result)
        assertEquals("direct", result.target)
        assertEquals("", result.source)
        assertEquals(listOf("dst_ip"), result.missingInputs)
    }

    @Test
    fun `a config from a later generation cannot identify the source line`() {
        val honk = honk().apply { on("GET", "/api/v1/config", 200, HonkFixtures.body("config/editable").replace("generation-42", "generation-43")) }
        honk.on("POST", "/api/v1/routing/trace", 200, HonkFixtures.body("routing-trace/lan-fallback"))
        honk.on("GET", "/api/v1/rules", 200, HonkFixtures.body("rules/running").replace("\"source\": null", "\"source\": {\"file\":\"config.dae\",\"source_id\":\"source-main\",\"line\":2,\"column\":3}"))
        val vm = RouteTraceViewModel(honk, ::tunCarries, Dispatchers.Unconfined)
        vm.query(RvTraceQuery("198.51.100.20", "443"))
        vm.trace()
        assertEquals(ErrorCode.Busy, vm.uiState.value.failure)
    }

    @Test
    fun `a reload between the trace and the rules read fails as busy, and a new destination drops the answer`() {
        val honk = honk().apply { on("GET", "/api/v1/rules", 200, HonkFixtures.body("rules/running").replace("generation-42", "generation-43")) }
        honk.on("POST", "/api/v1/routing/trace", 200, HonkFixtures.body("routing-trace/lan-fallback"))
        val vm = RouteTraceViewModel(honk, ::tunCarries, Dispatchers.Unconfined)
        vm.query(RvTraceQuery("198.51.100.20", "443"))
        vm.trace()
        assertEquals(ErrorCode.Busy, vm.uiState.value.failure)
        vm.query(RvTraceQuery("198.51.100.21", "443"))
        assertNull(vm.uiState.value.failure)
    }

    @Test
    fun `the fallback answers under 其餘流量, and LAN stays outside the tunnel`() {
        val honk = honk().apply { on("POST", "/api/v1/routing/trace", 200, HonkFixtures.body("routing-trace/lan-fallback")) }
        val vm = RouteTraceViewModel(honk, ::tunCarries, Dispatchers.Unconfined)
        vm.query(RvTraceQuery("192.168.1.20", "53", RvTraceProto.Udp))
        vm.trace()
        val result = vm.uiState.value.result
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
