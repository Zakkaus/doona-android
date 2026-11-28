package dev.doona.android.feature.settings

import dev.doona.android.engine.ErrorCode
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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/** DNS 規則 lists honk's two DNS lists and removes a statement by deleting the line honk names. */
@OptIn(ExperimentalCoroutinesApi::class)
class DnsRulesViewModelTest {
    private val io = UnconfinedTestDispatcher()
    private val honk: FakeHonk = PolicyHonkFixtures.rvSample().apply {
        on("PUT", "/api/v1/config/sources/source-main", 202, HonkFixtures.body("config-replace/queued"))
        on("GET", "/api/v1/operations/op-config-01", 200, HonkFixtures.body("operations/reload-complete"))
    }

    @Before
    fun setUp() = Dispatchers.setMain(io)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `each list is honk's in order, its fallback last`() = runTest {
        val vm = DnsRulesViewModel(honk, io)
        val query = listOf(
            RvDnsRow("qname(geosite:category-ads-all)", RvDnsAction.Reject),
            RvDnsRow("qname(geosite:cn)", RvDnsAction.Upstream, "alidns"),
            RvDnsRow(null, RvDnsAction.Upstream, "cloudflare"),
        )
        assertEquals(RvDnsLists(query, listOf(RvDnsRow(null, RvDnsAction.Accept))), vm.uiState.value.lists)
    }

    @Test
    fun `a removal deletes the statement's line, and a line that moved is busy`() = runTest {
        val vm = DnsRulesViewModel(honk, io)
        vm.remove("Query:1")
        advanceUntilIdle()
        val text = Json.parseToJsonElement(checkNotNull(honk.calls.single { it.method == "PUT" }.body)).jsonObject.getValue("content").jsonPrimitive.content
        val sources = Json.parseToJsonElement(HonkFixtures.body("config/rv-sample")).jsonObject.getValue("sources").jsonArray
        val before = sources[0].jsonObject.getValue("content").jsonPrimitive.content
        assertEquals(before.lines().filterIndexed { i, _ -> i != 79 }, text.lines())

        honk.on("GET", "/api/v1/dns/rules", 200, HonkFixtures.body("dns-rules/rv-sample").replace("\"line\": 80", "\"line\": 79"))
        vm.retry()
        advanceUntilIdle()
        vm.remove("Query:1")
        advanceUntilIdle()
        assertEquals("Query:1" to ErrorCode.Busy, vm.uiState.value.removeFailure)
        assertEquals(1, honk.calls.count { it.method == "PUT" })
    }
}
