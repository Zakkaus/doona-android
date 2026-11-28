package dev.doona.android.feature.settings

import dev.doona.android.feature.model.RvMatchKind
import dev.doona.android.feature.policies.RvRuleDraft
import dev.doona.android.honk.FakeHonk
import dev.doona.android.honk.HonkFixtures
import dev.doona.android.honk.PolicyHonkFixtures
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
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

/** A DNS rule's page on the sample (config/rv-sample, dns-rules/rv-sample): it reads a statement and writes its line. */
@OptIn(ExperimentalCoroutinesApi::class)
class DnsRuleEditViewModelTest {
    private val io = UnconfinedTestDispatcher()
    private val honk: FakeHonk = PolicyHonkFixtures.rvSample().apply {
        on("PUT", "/api/v1/config/sources/source-main", 202, HonkFixtures.body("config-replace/queued"))
        on("GET", "/api/v1/operations/op-config-01", 200, HonkFixtures.body("operations/reload-complete"))
    }
    private val before = Json.parseToJsonElement(HonkFixtures.body("config/rv-sample")).jsonObject.getValue("sources").jsonArray[0]
        .jsonObject.getValue("content").jsonPrimitive.content.lines()

    @Before
    fun setUp() = Dispatchers.setMain(io)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `a statement opens with its action, server and line, and 套用 rewrites the line`() = runTest {
        val vm = DnsRuleEditViewModel(honk, RvDnsList.Query, "dns_request:1", io)
        val ui = vm.uiState.value
        val draft = checkNotNull(ui.draft)
        assertEquals(RvDnsAction.Upstream to "alidns", draft.action to draft.upstream)
        assertEquals(listOf("cloudflare", "alidns"), ui.upstreams.map { it.name })
        assertEquals(80, checkNotNull(checkNotNull(ui.at).statement).hunks.single().single().line)
        vm.change(draft.copy(action = RvDnsAction.Reject))
        vm.apply()
        advanceUntilIdle()
        assertEquals(before.toMutableList().also { it[79] = "            qname(geosite:cn) -> reject" }, written())
        assertTrue(vm.uiState.value.done)
    }

    @Test
    fun `a new query goes before the fallback, a paused statement stays as a comment`() = runTest {
        val vm = DnsRuleEditViewModel(honk, RvDnsList.Query, null, io)
        vm.change(RvDnsRuleDraft(RvDnsList.Query, RvRuleDraft(RvMatchKind.Suffix, "corp.example", ""), RvDnsAction.Upstream, "cloudflare"))
        vm.apply()
        advanceUntilIdle()
        assertEquals(before.take(80) + "            qname(suffix: corp.example) -> cloudflare" + before.drop(80), written())

        honk.calls.clear()
        val paused = DnsRuleEditViewModel(honk, RvDnsList.Query, "dns_request:0", io)
        paused.change(checkNotNull(paused.uiState.value.draft).let { it.copy(match = it.match.copy(enabled = false)) })
        paused.apply()
        advanceUntilIdle()
        assertEquals(before.toMutableList().also { it[77] = "            # qname(geosite:category-ads-all) -> reject" }, written())
    }

    @Test
    fun `a new response, with no response list written, opens one in dns routing`() = runTest {
        val vm = DnsRuleEditViewModel(honk, RvDnsList.Response, null, io)
        vm.change(RvDnsRuleDraft(RvDnsList.Response, RvRuleDraft(RvMatchKind.Private, "", ""), RvDnsAction.Reject))
        vm.apply()
        advanceUntilIdle()
        val text = written()
        val at = text.indexOf("        response {")
        assertTrue(at > 74)
        assertEquals(listOf("        response {", "            ip(geoip: private) -> reject", "        }"), text.subList(at, at + 3))
        assertEquals(before.size + 3, text.size)
    }

    @Test
    fun `retry applies edits made before the first refusal arrived`() = runTest {
        val path = "/api/v1/config/sources/source-main"
        val vm = DnsRuleEditViewModel(honk, RvDnsList.Query, "dns_request:1", StandardTestDispatcher(testScheduler))
        advanceUntilIdle()
        honk.on("PUT", path, 503, HonkFixtures.body("errors/snapshot-unavailable"))
        vm.apply()
        vm.change(checkNotNull(vm.uiState.value.draft).copy(action = RvDnsAction.Reject))
        advanceUntilIdle()
        honk.calls.clear()
        honk.on("PUT", path, 202, HonkFixtures.body("config-replace/queued"))
        checkNotNull(vm.uiState.value.writeFailure).second()
        advanceUntilIdle()
        assertEquals(before.toMutableList().also { it[79] = "            qname(geosite:cn) -> reject" }, written())
    }

    @Test
    fun `retry a refused delete removes the statement instead of saving the draft`() = runTest {
        val path = "/api/v1/config/sources/source-main"
        val vm = DnsRuleEditViewModel(honk, RvDnsList.Query, "dns_request:1", io)
        vm.change(checkNotNull(vm.uiState.value.draft).copy(action = RvDnsAction.Reject))
        honk.on("PUT", path, 503, HonkFixtures.body("errors/snapshot-unavailable"))
        vm.delete()
        advanceUntilIdle()
        val retry = checkNotNull(vm.uiState.value.writeFailure).second
        honk.calls.clear()
        honk.on("PUT", path, 202, HonkFixtures.body("config-replace/queued"))
        retry()
        advanceUntilIdle()
        assertEquals(before.toMutableList().also { it.removeAt(79) }, written())
        assertTrue(vm.uiState.value.done)
    }

    @Test
    fun reloadKeepsANewRuleDraft() = runTest {
        val vm = DnsRuleEditViewModel(honk, RvDnsList.Query, null, io)
        val draft = RvDnsRuleDraft(RvDnsList.Query, RvRuleDraft(RvMatchKind.Suffix, "corp.example", ""), RvDnsAction.Reject)
        vm.change(draft)
        honk.on("GET", "/api/v1/config", 200, HonkFixtures.body("config/rv-sample").replace("generation-42", "generation-43"))
        vm.apply()
        advanceUntilIdle()
        honk.on("GET", "/api/v1/dns/rules", 200, HonkFixtures.body("dns-rules/rv-sample").replace("generation-42", "generation-43"))
        checkNotNull(vm.uiState.value.writeFailure).second()
        advanceUntilIdle()
        assertEquals(draft, vm.uiState.value.draft)
        vm.apply()
        advanceUntilIdle()
        assertTrue(written().any { "corp.example" in it })
    }

    private fun written(): List<String> = Json.parseToJsonElement(checkNotNull(honk.calls.single { it.method == "PUT" }.body)).jsonObject
        .getValue("content").jsonPrimitive.content.lines()
}
