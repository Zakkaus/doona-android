package dev.doona.android.vpn

import dev.doona.android.engine.EngineException
import dev.doona.android.engine.ErrorCode
import dev.doona.android.engine.GroupKind
import dev.doona.android.engine.Mode
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Parses every payload in `contracts/doona-core/`, the examples both sides of the JNI contract test against. */
class CoreDtoTest {
    private fun fixture(name: String) = ContractFixtures.read(name)

    @Test
    fun everyFixtureHasAParser() {
        val checked = setOf(
            "init.json", "start-config.json", "snapshot-running.json", "snapshot-died.json", "snapshot-stopped-catalog.json",
            "call-ok-empty.json", "call-ok-subscription.json", "call-error.json", "call-select-args.json",
            "call-ok-traffic-series.json", "call-ok-usage.json", "call-ok-nodes.json", "call-nodes-args.json",
            "call-ok-rules.json", "call-rules-args.json", "call-set-rule-source-args.json", "call-ok-effective-config.json",
            "call-set-mode-args.json",
        )
        val present = ContractFixtures.dir.listFiles { f -> f.extension == "json" }.orEmpty().map { it.name }.toSet()
        assertEquals(checked, present)
    }

    @Test
    fun init() {
        assertEquals(CORE_SCHEMA, CoreJson.schema(fixture("init.json")))
    }

    @Test
    fun startConfigEncodesAsTheFixture() {
        val encoded = CoreJson.encode(StartConfigDto.serializer(), StartConfigDto.of(listOf("192.168.8.1")))
        assertEquals(Json.parseToJsonElement(fixture("start-config.json")), Json.parseToJsonElement(encoded))
    }

    @Test
    fun snapshots() {
        val running = CoreJson.snapshot(fixture("snapshot-running.json"))
        // A warning leaves the data path up.
        assertTrue(running.alive)
        assertEquals("local_network", running.warning)
        assertEquals(52428800L, running.traffic.downTotal)
        assertEquals(7836412L, running.traffic.downPeak)
        assertEquals(1288490188L, running.today.downTotal)
        assertEquals(1610612736L, running.today.yesterdayByNow)
        assertNull(running.catalog)

        val died = CoreJson.snapshot(fixture("snapshot-died.json"))
        assertFalse(died.alive)
        assertEquals("internal", died.error)
        assertNull(died.warning)

        // No yesterday was kept: the comparison has nothing to show.
        val stopped = CoreJson.snapshot(fixture("snapshot-stopped-catalog.json"))
        assertNull(stopped.today.yesterdayByNow)
    }

    @Test
    fun catalogMapsToEngineTypes() {
        val catalog = checkNotNull(CoreJson.snapshot(fixture("snapshot-stopped-catalog.json")).catalog)
        assertEquals(CoreMode.Global, catalog.mode)
        val groups = catalog.groups.map(GroupDto::toGroup)
        assertEquals(listOf("g-Proxy", "g-Auto", "g-HK"), groups.map { it.id })
        val auto = groups[1]
        assertEquals(GroupKind.UrlTest, auto.kind)
        assertTrue(auto.testing)
        // Two distinct endpoints may share a display name; their ids differ.
        assertEquals(listOf("hk-01", "hk-01"), auto.members.map { it.name })
        assertEquals(2, auto.members.map { it.id }.toSet().size)
        assertTrue(groups[0].members.first().isGroup)
        // A node on the local network whose probe failed says why; the others carry no code.
        val lan = auto.members[1]
        assertTrue(lan.failed)
        assertEquals(ErrorCode.LocalNetwork, lan.error)
        assertNull(auto.members[0].error)

        val subscription = catalog.subscriptions.single().toSubscription()
        assertEquals("s1", subscription.id)
        assertEquals(107374182400L, subscription.totalBytes)
        assertNull(subscription.error)
    }

    @Test
    fun catalogCarriesTheMainGroupAndTheRuleSource() {
        val catalog = checkNotNull(CoreJson.snapshot(fixture("snapshot-stopped-catalog.json")).catalog)
        assertEquals("g-Proxy", catalog.mainGroupId)
        val source = catalog.ruleSource
        assertEquals("template", source.kind)
        assertEquals("cn-direct", source.template)
        assertEquals(2, source.version)
        assertNull(source.subscriptionId)
        assertFalse(source.options.blockQuic)
        assertEquals(listOf(5, 23, 0), listOf(source.rules, source.lines, source.skipped))
        assertEquals(91044300313602L, source.rulesRevision)
        assertNull(source.error)
    }

    @Test
    fun ruleModeMapsToGlobalUntilTheEngineKnowsIt() {
        assertEquals(Mode.Global, CoreMode.Rule.toMode())
        assertEquals(Mode.Global, CoreMode.Global.toMode())
        assertEquals(Mode.Direct, CoreMode.Direct.toMode())
        assertEquals(Mode.entries, Mode.entries.map { CoreMode.of(it).toMode() })
    }

    @Test
    fun rulesPage() {
        val rules = CoreJson.result(fixture("call-ok-rules.json"), RulesDto.serializer())
        assertEquals(5, rules.total)
        assertEquals(91044300313602L, rules.rulesRevision)
        assertEquals(5, rules.rows.size)
        val first = rules.rows.first()
        assertEquals(listOf("GeoIp", "private", "direct", "direct"), listOf(first.kind, first.sample, first.target, first.targetKind))
        assertNull(first.skipped)
        val last = rules.rows.last()
        assertEquals("Match", last.kind)
        assertNull(last.sample)
        assertEquals("g-Proxy", last.target)
        assertEquals("group", last.targetKind)
        assertEquals(listOf(0, 1, 2, 3, 4), rules.rows.map { it.index })
    }

    @Test
    fun effectiveConfig() {
        val config = CoreJson.result(fixture("call-ok-effective-config.json"), EffectiveConfigDto.serializer())
        assertEquals(CoreMode.Rule, config.mode)
        assertEquals("g-Proxy", config.mainGroupId)
        assertEquals("g-Proxy", config.dnsVia)
        assertEquals(listOf("1.1.1.1:53", "8.8.8.8:53"), config.dnsUpstreams)
        assertEquals(91044300313602L, config.ruleSource.rulesRevision)
        assertTrue(config.text.startsWith("# mode: Rule\n"))
        assertTrue(config.text.endsWith("MATCH,Proxy\n"))
        assertFalse(config.truncated)
    }

    @Test
    fun ruleModeArgsEncodeAsTheFixtures() {
        val source = CoreJson.encode(RuleSourceArgs.serializer(), RuleSourceArgs("template", "cn-direct", RuleOptionsDto(blockQuic = true)))
        assertEquals(Json.parseToJsonElement(fixture("call-set-rule-source-args.json")), Json.parseToJsonElement(source))
        // An option that is off is still sent: the core needs every option.
        val off = CoreJson.encode(RuleSourceArgs.serializer(), RuleSourceArgs("template", "cn-direct", RuleOptionsDto(blockQuic = false)))
        assertEquals("""{"kind":"template","template":"cn-direct","options":{"blockQuic":false}}""", off)

        val mode = CoreJson.encode(ModeArgs.serializer(), ModeArgs(CoreMode.Rule))
        assertEquals(Json.parseToJsonElement(fixture("call-set-mode-args.json")), Json.parseToJsonElement(mode))
        val page = CoreJson.encode(RulesArgs.serializer(), RulesArgs(offset = 0, limit = 50))
        assertEquals(Json.parseToJsonElement(fixture("call-rules-args.json")), Json.parseToJsonElement(page))
    }

    @Test
    fun callReplies() {
        assertEquals(Unit, CoreJson.result(fixture("call-ok-empty.json"), Unit.serializer()))
        val subscription = CoreJson.result(fixture("call-ok-subscription.json"), SubscriptionDto.serializer())
        assertEquals(2, subscription.nodes)
        try {
            CoreJson.result(fixture("call-error.json"), Unit.serializer())
            fail("an error reply must throw")
        } catch (e: EngineException) {
            assertEquals(ErrorCode.FetchFailed, e.code)
        }
    }

    @Test
    fun anOlderSnapshotHasNoPeaksAndNoDay() {
        val old = CoreJson.snapshot(
            """{"revision":1,"running":false,"generation":0,"traffic":{"upTotal":0,"downTotal":0,"activeFlows":0}}""",
        )
        assertEquals(0L, old.traffic.upPeak)
        assertEquals(TodayDto(), old.today)
    }

    @Test
    fun statsReplies() {
        val series = CoreJson.result(fixture("call-ok-traffic-series.json"), TrafficSeriesDto.serializer())
        assertEquals(1L, series.stepSeconds)
        assertEquals(series.down.max(), series.downPeak)

        val usage = CoreJson.result(fixture("call-ok-usage.json"), UsageDto.serializer())
        assertEquals("2026-11-22", usage.today.date)
        assertEquals(24, usage.today.down.size)
        assertEquals(listOf("direct", "g-Proxy"), usage.today.outbounds.map { it.id })
        val yesterday = checkNotNull(usage.yesterday)
        assertEquals(yesterday.down.sum(), usage.days.last().down)
        assertTrue(yesterday.byRule.isEmpty())
        // Rule mode routed every flow today: its rules carry the same bytes as its outbounds.
        assertEquals(listOf("r-6e59b550be7e592b", "r-8d066c6f0b6552cd"), usage.today.byRule.map { it.id })
        assertEquals(listOf(276L, 398L), usage.today.byRule.map { it.flows })
        assertEquals(usage.today.outbounds.sumOf { it.up }, usage.today.byRule.sumOf { it.up })
        assertEquals(usage.today.outbounds.sumOf { it.down }, usage.today.byRule.sumOf { it.down })

        val nodes = CoreJson.result(fixture("call-ok-nodes.json"), NodesDto.serializer()).nodes
        assertEquals("trojan", nodes[0].protocol)
        assertEquals(listOf(33, null, 31), nodes[0].tests.map { it.ms })
        assertEquals("quic", nodes[1].transport)
        assertTrue(nodes[1].tests.isEmpty())
    }

    @Test
    fun statsArgsEncode() {
        val encoded = CoreJson.encode(NodesArgs.serializer(), NodesArgs(listOf("n-0c41be77", "n-7f3a19c2")))
        assertEquals(Json.parseToJsonElement(fixture("call-nodes-args.json")), Json.parseToJsonElement(encoded))
        assertEquals("""{"ids":null}""", CoreJson.encode(NodesArgs.serializer(), NodesArgs(null)))
        assertEquals("""{"seconds":60}""", CoreJson.encode(SeriesArgs.serializer(), SeriesArgs(60)))
    }

    @Test
    fun selectArgsEncodeAsTheFixture() {
        val encoded = CoreJson.encode(SelectArgs.serializer(), SelectArgs("g-Proxy", "n-0c41be77"))
        assertEquals(Json.parseToJsonElement(fixture("call-select-args.json")), Json.parseToJsonElement(encoded))
        val auto = CoreJson.encode(SelectArgs.serializer(), SelectArgs("g-Proxy", null))
        assertEquals("""{"group":"g-Proxy","memberId":null}""", auto)
    }

    @Test
    fun unknownErrorCodeIsInternal() {
        assertEquals(ErrorCode.NoNodes, errorCodeOf("no_nodes"))
        assertEquals(ErrorCode.LocalNetwork, errorCodeOf("local_network"))
        assertEquals(ErrorCode.Internal, errorCodeOf("from_a_newer_core"))
    }
}
