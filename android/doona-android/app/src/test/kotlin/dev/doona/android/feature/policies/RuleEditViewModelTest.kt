package dev.doona.android.feature.policies

import dev.doona.android.engine.ErrorCode
import dev.doona.android.feature.model.RV_DIRECT
import dev.doona.android.feature.model.RvMatchKind
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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * A rule's page on the hand-written sample (config/rv-sample-hand, its rules rules/rv-sample-hand): it reads the rule
 * into the form, and 套用, 刪除規則 and 上移 write the rule's line of config.dae.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RuleEditViewModelTest {
    private val io = UnconfinedTestDispatcher()
    private val honk: FakeHonk = PolicyHonkFixtures.handWritten().apply {
        on("GET", "/api/v1/rules", 200, HonkFixtures.body("rules/rv-sample-hand"))
        on("PUT", "/api/v1/config/sources/source-main", 202, HonkFixtures.body("config-replace/queued"))
        on("GET", "/api/v1/operations/op-config-01", 200, HonkFixtures.body("operations/reload-complete"))
    }
    private val before = Json.parseToJsonElement(HonkFixtures.body("config/rv-sample-hand")).jsonObject.getValue("sources").jsonArray[0]
        .jsonObject.getValue("content").jsonPrimitive.content.lines()

    @Before
    fun setUp() = Dispatchers.setMain(io)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `retrying delete cannot apply the form`() = runTest {
        val vm = RuleEditViewModel(honk, "traffic:4", io = io)
        honk.on("PUT", "/api/v1/config/sources/source-main", 503, HonkFixtures.body("errors/snapshot-unavailable"))
        vm.delete()
        assertNotNull(vm.uiState.value.writeFailure)
        honk.calls.clear()
        honk.on("PUT", "/api/v1/config/sources/source-main", 202, HonkFixtures.body("config-replace/queued"))
        vm.retryWrite()
        assertEquals(before.filterIndexed { i, _ -> i != 66 }, written())
    }

    @Test
    fun `retry after deleting the first of two identical rules leaves the second in place`() = runTest {
        val config = HonkFixtures.body("config/rv-sample-hand")
        val row = before[66] + "\\n"
        honk.on("GET", "/api/v1/config", 200, config.replace(row, row + row))
        honk.on("GET", "/api/v1/operations/op-config-01", 503, HonkFixtures.body("errors/snapshot-unavailable"))
        val vm = RuleEditViewModel(honk, "traffic:4", io = io)
        vm.delete()
        advanceUntilIdle()
        assertNotNull(vm.uiState.value.writeFailure)
        assertEquals(before, written())
        honk.on("GET", "/api/v1/config", 200, config)
        vm.retryWrite()
        assertTrue(vm.uiState.value.done)
        assertEquals(1, honk.calls.count { it.method == "PUT" })
    }

    @Test
    fun connectionDestinationPrefillsTheNewRule() {
        for ((destination, kind) in listOf("example.org" to RvMatchKind.Full, "192.0.2.1/32" to RvMatchKind.Cidr, "2001:db8::1/128" to RvMatchKind.Cidr)) {
            val draft = RuleEditViewModel(honk, null, io = io, destination = destination).uiState.value.draft
            assertEquals(kind, draft?.kind)
            assertEquals(destination, draft?.value)
            assertNull(checkNotNull(draft).problem)
            assertEquals(kind to destination, formMatch(matchText(kind, destination)))
        }
    }

    @Test
    fun `a rule the form holds opens as its match, target and place, and 套用 rewrites its line`() = runTest {
        val vm = RuleEditViewModel(honk, "traffic:4", io = io)
        val ui = vm.uiState.value
        assertEquals(RvRuleDraft(RvMatchKind.Suffix, "corp.example", RV_DIRECT), ui.draft)
        assertEquals(RvNativeRuleAt(5, 5), ui.at)
        vm.change(checkNotNull(ui.draft).copy(kind = RvMatchKind.Full, value = "vpn.corp.example", targetId = "g-Streaming", must = true))
        vm.apply()
        advanceUntilIdle()
        assertEquals(before.toMutableList().also { it[66] = "    domain(full: vpn.corp.example) -> Streaming(must)" }, written())
        assertTrue(vm.uiState.value.done)
    }

    @Test
    fun `a match the form cannot hold shows its line and keeps its condition`() = runTest {
        val vm = RuleEditViewModel(honk, "traffic:1", io = io)
        val at = checkNotNull(vm.uiState.value.at)
        assertEquals(2, at.index)
        assertEquals(63, checkNotNull(at.statement).hunks.single().single().line)
        vm.change(checkNotNull(vm.uiState.value.draft).copy(targetId = RV_DIRECT))
        vm.apply()
        advanceUntilIdle()
        assertEquals(before.toMutableList().also { it[62] = "    domain(geosite:openai, geosite:anthropic) -> direct" }, written())
    }

    @Test
    fun `a new rule goes before the fallback, and one turned off stays as a comment`() = runTest {
        val vm = RuleEditViewModel(honk, null, io = io)
        assertNull(vm.uiState.value.at)
        vm.change(checkNotNull(vm.uiState.value.draft).copy(value = "example.org", enabled = false))
        vm.apply()
        advanceUntilIdle()
        assertEquals(before.take(67) + "    # domain(suffix: example.org) -> direct" + before.drop(67), written())
    }

    @Test
    fun `刪除規則 deletes the line, 上移 swaps it with the rule above`() = runTest {
        RuleEditViewModel(honk, "traffic:4", io = io).delete()
        advanceUntilIdle()
        assertEquals(before.filterIndexed { i, _ -> i != 66 }, written())

        honk.calls.clear()
        val vm = RuleEditViewModel(honk, "traffic:4", io = io)
        vm.move(4)
        advanceUntilIdle()
        assertEquals(
            before.toMutableList().also {
                it[65] = before[66]
                it[66] = before[65]
            },
            written(),
        )
    }

    @Test
    fun `a rule written over several lines opens as its lines and writes nothing`() = runTest {
        val config = HonkFixtures.body("config/rv-sample-hand")
            .replace("    # AI services.\\n    domain(geosite:openai, geosite:anthropic) -> ai", "    domain(geosite:openai,\\n        geosite:anthropic) -> ai")
        honk.on("GET", "/api/v1/config", 200, config)
        honk.on("GET", "/api/v1/rules", 200, HonkFixtures.body("rules/rv-sample-hand").replace("\"line\": 63", "\"line\": 62"))
        val vm = RuleEditViewModel(honk, "traffic:1", io = io)
        val at = checkNotNull(vm.uiState.value.at)
        assertTrue(at.sourceOnly)
        assertEquals(listOf(62 to "domain(geosite:openai,", 63 to "    geosite:anthropic) -> ai"), checkNotNull(at.statement).hunks.single().map { it.line to it.text })
        vm.apply()
        vm.delete()
        vm.move(1)
        advanceUntilIdle()
        assertTrue(honk.calls.none { it.method == "PUT" })
    }

    @Test
    fun `a line that no longer holds the rule is busy and nothing is written`() = runTest {
        honk.on("GET", "/api/v1/rules", 200, HonkFixtures.body("rules/rv-sample-hand").replace("\"line\": 67", "\"line\": 66"))
        val vm = RuleEditViewModel(honk, "traffic:4", io = io)
        vm.apply()
        advanceUntilIdle()
        assertEquals(ErrorCode.Busy, vm.uiState.value.writeFailure)
        assertTrue(honk.calls.none { it.method == "PUT" })
        assertNotNull(vm.uiState.value.draft)
    }

    /** The text of config.dae the one PUT wrote, by line. */
    private fun written(): List<String> = Json.parseToJsonElement(checkNotNull(honk.calls.single { it.method == "PUT" }.body)).jsonObject
        .getValue("content").jsonPrimitive.content.lines()
}
