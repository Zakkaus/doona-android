package dev.doona.android.feature.policies

import dev.doona.android.feature.model.RvMatchKind
import dev.doona.android.feature.model.RvRuleMatch
import dev.doona.android.feature.model.RvRuleState
import dev.doona.android.vpn.FakeHonk
import dev.doona.android.vpn.HonkFixtures
import dev.doona.android.vpn.PolicyHonkFixtures
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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

/**
 * 全部規則 on the hand-written sample with line 62 of config.dae paused (`# domain(suffix: paused.example) -> direct`);
 * the same text as a comment in the group block and a paused DNS rule are not traffic rules.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RulesPausedViewModelTest {
    private val io = UnconfinedTestDispatcher()
    private val config = HonkFixtures.body("config/rv-sample-hand")
        .replace("# AI services.", "# $PAUSED")
        .replace("# Block ads: answer ad domains with an empty reply.", "# qname(geosite:category-ads-all) -> reject")
    private val honk: FakeHonk = PolicyHonkFixtures.handWritten().apply {
        on("GET", "/api/v1/config", 200, config)
        on("GET", "/api/v1/rules", 200, HonkFixtures.body("rules/rv-sample-hand"))
        on("PUT", "/api/v1/config/sources/source-main", 202, HonkFixtures.body("config-replace/queued"))
        on("GET", "/api/v1/operations/op-config-01", 200, HonkFixtures.body("operations/reload-complete"))
    }
    private val before = Json.parseToJsonElement(config).jsonObject.getValue("sources").jsonArray[0]
        .jsonObject.getValue("content").jsonPrimitive.content.lines()

    @Before
    fun setUp() = Dispatchers.setMain(io)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `a paused rule is listed 已停用 where it is written, with no honk id`() = runTest {
        val state = RulesViewModel(honk, io = io).uiState.value

        assertEquals(7, state.rules.size)
        val paused = state.rules[1]
        assertEquals(RvRuleState.Off, paused.state)
        assertEquals(RvMatchKind.Suffix, (paused.match as RvRuleMatch.Value).kind)
        assertEquals("paused.example", (paused.match as RvRuleMatch.Value).value)
        assertEquals(listOf("traffic:0", null, "traffic:1", "traffic:2", "traffic:3", "traffic:4", "traffic:5"), state.ids)
        assertEquals(RvRuleState.On, state.rules[2].state)
    }

    @Test
    fun `啟用 writes a paused rule's line back`() = runTest {
        RulesViewModel(honk, io = io).toggle(1)

        assertEquals(before.toMutableList().also { it[61] = "    $PAUSED" }, written())
    }

    @Test
    fun `刪除 deletes a paused rule's line`() = runTest {
        RulesViewModel(honk, io = io).remove(1)

        assertEquals(before.filterIndexed { i, _ -> i != 61 }, written())
    }

    @Test
    fun `停用 keeps a rule as a comment`() = runTest {
        RulesViewModel(honk, io = io).toggle(2)

        assertEquals(before.toMutableList().also { it[62] = "    # domain(geosite:openai, geosite:anthropic) -> ai" }, written())
    }

    /** The text of config.dae the one PUT wrote, by line. */
    private fun written(): List<String> = Json.parseToJsonElement(checkNotNull(honk.calls.single { it.method == "PUT" }.body)).jsonObject
        .getValue("content").jsonPrimitive.content.lines()

    private companion object {
        const val PAUSED = "domain(suffix: paused.example) -> direct"
    }
}
