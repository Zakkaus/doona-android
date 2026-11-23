package dev.doona.android.engine.fake

import dev.doona.android.engine.EngineException
import dev.doona.android.engine.ErrorCode
import dev.doona.android.engine.Mode
import dev.doona.android.engine.NodeCodes
import dev.doona.android.engine.RuleBytes
import dev.doona.android.engine.RuleOptions
import dev.doona.android.engine.RuleRow
import dev.doona.android.engine.RuleTarget
import dev.doona.android.vpn.ContractFixtures
import dev.doona.android.vpn.CoreJson
import dev.doona.android.vpn.EffectiveConfigDto
import dev.doona.android.vpn.RulesDto
import dev.doona.android.vpn.UsageDto
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.DeserializationStrategy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The fake serves rule mode as the core does, from the contract's payloads, so the RV pages can be built without a core. */
class FakeEngineRuleModeTest {
    private fun <T> reply(name: String, dto: DeserializationStrategy<T>): T = CoreJson.result(ContractFixtures.read(name), dto)

    private fun Result<*>.code() = (exceptionOrNull() as EngineException).code

    @Test
    fun theRowsTheSourceAndTheGroupsAreTheTemplatesAsTheFixturesHaveThem() = runTest {
        val engine = FakeEngine(mode = Mode.Rule)
        assertEquals(reply("call-ok-rules.json", RulesDto.serializer()).toRulePage(), engine.rules(offset = 0, limit = 50).getOrThrow())
        val catalog = checkNotNull(CoreJson.snapshot(ContractFixtures.read("snapshot-stopped-catalog.json")).catalog)
        val published = checkNotNull(engine.catalog.value)
        assertEquals(catalog.ruleSource.toRuleSource(), published.ruleSource)
        assertEquals(catalog.mainGroupId, published.mainGroupId)
        assertEquals(catalog.groups.map { it.id }, FakeEngine.TEMPLATE_GROUPS.map { it.id })
        // Every group a row sends to exists.
        val targets = FakeRuleMode.ROWS.mapNotNull { (it.target as? RuleTarget.Group)?.id }
        assertTrue(FakeEngine.TEMPLATE_GROUPS.map { it.id }.containsAll(targets))
    }

    @Test
    fun theDefaultGroupsAreTheTemplatesWithEveryGroupTheRulesSendTo() = runTest {
        val engine = FakeEngine()
        assertEquals(listOf("g-Proxy", "g-Auto", "g-Streaming", "g-AI", "g-HK"), engine.groups.value.map { it.id })
    }

    @Test
    fun theCatalogFollowsEveryChangeAtOnce() = runTest {
        val engine = FakeEngine()
        engine.setMode(Mode.Rule)
        engine.groups.value = FakeEngine.SAMPLE_GROUPS
        engine.subscriptions.value = emptyList()
        val catalog = checkNotNull(engine.catalog.value)
        assertEquals(Mode.Rule, catalog.mode)
        assertEquals(FakeEngine.SAMPLE_GROUPS to emptyList<Any>(), catalog.groups to catalog.subscriptions)
    }

    @Test
    fun theFakesNodesHaveIdsAndCodesOfTheCoresShape() = runTest {
        val engine = FakeEngine()
        val nodes = engine.groups.value.flatMap { it.members }.filterNot { it.isGroup }
        assertTrue(nodes.isNotEmpty())
        assertTrue(nodes.map { it.id }.toString(), nodes.all { Regex("n-[0-9a-f]{16}").matches(it.id) })
        val listed = engine.effectiveConfig().getOrThrow().text.lines().filter { it.startsWith("# group ") }
        val codes = listed.flatMap { Regex("""(?<=[ ,(])n-[^,)\s]*""").findAll(it).map { m -> m.value }.toList() }
        assertTrue(codes.isNotEmpty())
        assertTrue(codes.toString(), codes.all { Regex("n-[0-9a-f]{8}").matches(it) })
    }

    @Test
    fun rulesArePagedAndBoundedAsTheCoreDoes() = runTest {
        val engine = FakeEngine()
        val all = engine.rules(offset = 0, limit = 500).getOrThrow().rows
        assertEquals(all.drop(2).take(2), engine.rules(offset = 2, limit = 2).getOrThrow().rows)
        val past = engine.rules(offset = 9, limit = 50).getOrThrow()
        assertEquals(emptyList<RuleRow>() to 5, past.rows to past.total)
        for ((offset, limit) in listOf(0 to 0, 0 to 501, -1 to 50)) {
            assertEquals(ErrorCode.InvalidArgument, engine.rules(offset, limit).code())
        }
    }

    @Test
    fun theTemplatesOptionsChangeAndAnUnknownTemplateIsNotFound() = runTest {
        val engine = FakeEngine()
        assertTrue(engine.setRuleSource(FakeRuleMode.TEMPLATE, RuleOptions(blockQuic = true)).isSuccess)
        assertEquals(FakeRuleMode.SOURCE.copy(options = RuleOptions(blockQuic = true)), engine.catalog.value?.ruleSource)
        assertEquals(ErrorCode.NotFound, engine.setRuleSource("later", RuleOptions()).code())
        assertEquals(true, engine.catalog.value?.ruleSource?.options?.blockQuic)
    }

    @Test
    fun theEffectiveConfigIsTheCoresWithNodesByCode() = runTest {
        val engine = FakeEngine(mode = Mode.Rule)
        val config = engine.effectiveConfig().getOrThrow()
        val fixture = reply("call-ok-effective-config.json", EffectiveConfigDto.serializer()).toEffectiveConfig()
        assertEquals(fixture.copy(text = config.text), config)
        fun rules(text: String) = text.lines().filterNot { it.startsWith("#") }
        assertEquals(rules(fixture.text), rules(config.text))
        assertEquals(fixture.text.lines().take(4), config.text.lines().take(4))
        assertTrue("# group Auto (UrlTest, now n-369a8a63): n-369a8a63, n-4b1e07d9, n-85d011c7, n-c2f5a8e1" in config.text.lines())
        val names = FakeEngine.TEMPLATE_GROUPS.flatMap { it.members }.filterNot { it.isGroup }.map { it.name }.toSet()
        assertFalse(names.any { it in config.text })
        assertTrue("# group Auto (UrlTest, now hk-01): hk-01, hk-02, jp-01, us-01" in NodeCodes(engine.groups.value).onScreen(config.text).lines())

        engine.setMode(Mode.Direct)
        val direct = engine.effectiveConfig().getOrThrow()
        assertEquals(RuleTarget.Direct, direct.dnsVia)
        assertTrue(direct.text.startsWith("# mode: Direct\n"))
        assertTrue("# dns: via DIRECT, upstreams 1.1.1.1:53, 8.8.8.8:53" in direct.text.lines())
    }

    @Test
    fun usageIsTheFixturesWithTheProxiedBytesSpreadOverTheRules() = runTest {
        val usage = FakeEngine().usage().getOrThrow()
        val fixture = reply("call-ok-usage.json", UsageDto.serializer()).toUsage()
        assertEquals(fixture.yesterday, usage.yesterday)
        assertEquals(fixture.days, usage.days)
        val today = usage.today
        assertEquals(fixture.today.copy(outbounds = today.outbounds, byRule = today.byRule), today)
        // The rules carry the fixture's bytes and flows, and each sends its bytes to its own outbound.
        fun List<RuleBytes>.sums() = listOf(sumOf { it.up }, sumOf { it.down }, sumOf { it.flows })
        assertEquals(fixture.today.byRule.sums(), today.byRule.sums())
        assertEquals(listOf(today.outbounds.sumOf { it.up }, today.outbounds.sumOf { it.down }), today.byRule.sums().take(2))
        val targets = FakeRuleMode.ROWS.associate { it.id to it.target }
        for (rule in today.byRule) {
            val outbound = when (val target = targets.getValue(rule.id)) {
                RuleTarget.Direct -> "direct"
                RuleTarget.Reject -> "reject"
                is RuleTarget.Group -> target.id
            }
            assertEquals(rule.id, today.outbounds.single { it.id == outbound }.let { it.up to it.down }, rule.up to rule.down)
        }
        // In id order, as the core keeps them.
        assertEquals(today.byRule.sortedBy { it.id }, today.byRule)
        assertEquals(today.outbounds.sortedBy { it.id }, today.outbounds)
    }
}
