package dev.doona.android.feature.settings

import dev.doona.android.engine.ErrorCode
import dev.doona.android.feature.model.RvDnsUpstream
import dev.doona.android.honk.FakeHonk
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
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** DNS reads the server honk's request fallback names and writes a preset or 自訂 as that server's address. */
@OptIn(ExperimentalCoroutinesApi::class)
class DnsViewModelTest {
    private val io = UnconfinedTestDispatcher()
    private val honk: FakeHonk = PolicyHonkFixtures.rvSample().apply {
        on("PUT", "/api/v1/config/sources/source-main", 202, HonkFixtures.body("config-replace/queued"))
        on("GET", "/api/v1/operations/op-config-01", 200, HonkFixtures.body("operations/reload-complete"))
    }

    @Before
    fun setUp() = Dispatchers.setMain(io)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private val DnsViewModel.state: DnsUiState get() = checkNotNull(uiState.value) { "honk has not answered" }

    /** The server line of the file honk was last handed. */
    private fun written(name: String): String = Json.parseToJsonElement(checkNotNull(honk.calls.last { it.method == "PUT" }.body)).jsonObject
        .getValue("content").jsonPrimitive.content.lines().single { it.trim().startsWith("$name:") }.trim()

    @Test
    fun `the page shows the fallback's server, and a preset becomes its address`() = runTest {
        val vm = DnsViewModel(honk, io)
        assertEquals(listOf("1.1.1.1:53"), vm.state.servers)
        assertEquals(RvDnsUpstream.Cloudflare, rvDnsUpstream(vm.state.servers.single()))

        vm.select(RvDnsUpstream.Quad9)

        advanceUntilIdle()
        assertEquals("cloudflare: 'https://9.9.9.9/dns-query?tls_server_name=dns.quad9.net' -> Proxy", written("cloudflare"))
        assertEquals("\"${honkSha()}\"", honk.calls.last { it.method == "PUT" }.headers["If-Match"])
    }

    @Test
    fun `a rejected preset retries against the newly read source`() = runTest {
        val vm = DnsViewModel(honk, io)
        val source = "/api/v1/config/sources/source-main"
        val fresh = "b".repeat(64)
        honk.on("GET", "/api/v1/config", 200, HonkFixtures.body("config/rv-sample").replace(honkSha(), fresh))
        honk.on("PUT", source, 412, HonkFixtures.body("errors/stale-revision"))
        vm.select(RvDnsUpstream.Quad9)
        advanceUntilIdle()
        val failure = checkNotNull(vm.state.presetFailure)
        assertEquals(RvDnsUpstream.Quad9 to ErrorCode.Busy, failure)
        honk.on("PUT", source, 202, HonkFixtures.body("config-replace/queued"))
        vm.select(failure.first)
        advanceUntilIdle()
        assertEquals(listOf("\"${honkSha()}\"", "\"$fresh\""), honk.calls.filter { it.method == "PUT" }.map { it.headers["If-Match"] })
        assertNull(vm.state.presetFailure)
    }

    @Test
    fun `自訂 opens on the server as typed and writes a typed address as plain DNS`() = runTest {
        val vm = DnsViewModel(honk, io)
        vm.openCustom()
        assertTrue(vm.state.customOpen)
        assertEquals("1.1.1.1", vm.state.customText)

        vm.editCustom(" 2606:4700:4700::1111 ")
        vm.saveCustom()
        advanceUntilIdle()
        assertFalse(vm.state.customOpen)
        assertEquals("cloudflare: 'udp://[2606:4700:4700::1111]:53' -> Proxy", written("cloudflare"))

        vm.openCustom()
        vm.editCustom("tls://dns.google:853")
        vm.saveCustom()
        advanceUntilIdle()
        assertEquals("cloudflare: 'tls://dns.google:853' -> Proxy", written("cloudflare"))
    }

    @Test
    fun `what is not one address keeps the sheet open with why, and typing clears it`() = runTest {
        val vm = DnsViewModel(honk, io)
        vm.openCustom()
        for (typed in listOf("dns.example", "fe80::1", "1.1.1.1, 8.8.8.8", "300.1.1.1", "1.1.1.1:70000")) {
            vm.editCustom(typed)
            assertNull(vm.state.customError)
            vm.saveCustom()
            advanceUntilIdle()
            assertTrue(typed, vm.state.customOpen)
            assertEquals(typed, ErrorCode.InvalidArgument, vm.state.customError)
        }
        assertEquals(0, honk.calls.count { it.method == "PUT" })

        vm.dismissCustom()
        assertFalse(vm.state.customOpen)
    }

    @Test
    fun `typed addresses take honk's form`() {
        assertEquals("udp://1.1.1.1:53", DnsViewModel.honkAddress("1.1.1.1"))
        assertEquals("udp://1.1.1.1:5353", DnsViewModel.honkAddress("1.1.1.1:5353"))
        assertEquals("udp://[2001:4860:4860::8888]:5353", DnsViewModel.honkAddress("[2001:4860:4860::8888]:5353"))
        assertEquals("https://dns.example/dns-query", DnsViewModel.honkAddress("https://dns.example/dns-query"))
        assertNull(DnsViewModel.honkAddress("doh://dns.example"))
    }

    private fun honkSha(): String = Json.parseToJsonElement(HonkFixtures.body("config/rv-sample")).jsonObject.getValue("sources").let {
        (it as kotlinx.serialization.json.JsonArray)[0].jsonObject.getValue("content_sha256").jsonPrimitive.content
    }
}
