package dev.doona.android.feature.settings

import dev.doona.android.engine.ErrorCode
import dev.doona.android.feature.model.RvDnsServer
import dev.doona.android.vpn.FakeHonk
import dev.doona.android.vpn.HonkFixtures
import dev.doona.android.vpn.PolicyHonkFixtures
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import org.junit.Before
import org.junit.Test

/** DNS 伺服器 lists honk's declared servers, adds a line for a new one and deletes the line of one no rule names. */
@OptIn(ExperimentalCoroutinesApi::class)
class DnsServersViewModelTest {
    private val io = UnconfinedTestDispatcher()
    private val honk: FakeHonk = PolicyHonkFixtures.rvSample().apply {
        on("PUT", "/api/v1/config/sources/source-main", 202, HonkFixtures.body("config-replace/queued"))
        on("GET", "/api/v1/operations/op-config-01", 200, HonkFixtures.body("operations/reload-complete"))
    }

    @Before
    fun setUp() = Dispatchers.setMain(io)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun upstreamBlock(): List<String> {
        val text = Json.parseToJsonElement(checkNotNull(honk.calls.last { it.method == "PUT" }.body)).jsonObject.getValue("content").jsonPrimitive.content
        return text.lines().dropWhile { it.trim() != "upstream {" }.drop(1).takeWhile { it.trim() != "}" }
    }

    @Test
    fun `the servers are honk's, in file order`() = runTest {
        val vm = DnsServersViewModel(honk, io)
        assertEquals(listOf(RvDnsServer("cloudflare", "udp://1.1.1.1:53"), RvDnsServer("alidns", "udp://8.8.8.8:53")), vm.uiState.value.servers)
    }

    @Test
    fun `a new server is the block's last line, going direct`() = runTest {
        val vm = DnsServersViewModel(honk, io)
        vm.create(RvDnsServer("google", "tls://dns.google:853"))
        advanceUntilIdle()
        assertEquals(
            listOf("        cloudflare: 'udp://1.1.1.1:53' -> Proxy", "        alidns: 'udp://8.8.8.8:53' -> Proxy", "        google: 'tls://dns.google:853'"),
            upstreamBlock(),
        )
        assertEquals(null, vm.uiState.value.failure)
    }

    @Test
    fun `a server a DNS rule names stays, and one no rule names goes`() = runTest {
        honk.on("GET", "/api/v1/dns/rules", 200, HonkFixtures.body("dns-rules/rv-sample").replace("-> alidns", "-> cloudflare").replace("\"upstream\": \"alidns\"", "\"upstream\": \"cloudflare\""))
        val vm = DnsServersViewModel(honk, io)
        vm.remove("cloudflare")
        advanceUntilIdle()
        assertEquals(DnsServersFailure.Refused("cloudflare", 2), vm.uiState.value.failure)
        assertEquals(0, honk.calls.count { it.method == "PUT" })

        vm.remove("alidns")

        advanceUntilIdle()
        assertEquals(listOf("        cloudflare: 'udp://1.1.1.1:53' -> Proxy"), upstreamBlock())
    }

    @Test
    fun `a change honk refuses says why and keeps the list`() = runTest {
        honk.on("GET", "/api/v1/operations/op-config-01", 200, HonkFixtures.body("operations/reload-complete").replace("\"succeeded\"", "\"failed\""))
        val vm = DnsServersViewModel(honk, io)
        vm.create(RvDnsServer("google", "tls://dns.google:853"))
        advanceUntilIdle()
        assertEquals(DnsServersFailure.Create(RvDnsServer("google", "tls://dns.google:853"), ErrorCode.InvalidArgument), vm.uiState.value.failure)
        assertEquals(2, vm.uiState.value.servers?.size)
    }
}
