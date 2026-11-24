package dev.doona.android.vpn

import dev.doona.android.engine.CrashFile
import dev.doona.android.engine.EngineException
import dev.doona.android.engine.ErrorCode
import dev.doona.android.engine.Group
import dev.doona.android.engine.GroupKind
import dev.doona.android.engine.LogLevel
import dev.doona.android.engine.Mode
import dev.doona.android.engine.RuleKind
import dev.doona.android.engine.RuleOptions
import dev.doona.android.engine.RuleSkip
import dev.doona.android.engine.RuleTarget
import dev.doona.android.engine.StaticNodeReason
import dev.doona.android.engine.StaticNodeResult
import dev.doona.android.engine.SubscriptionFormat
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Parses every payload in `contracts/doona-core/`, the examples both sides of the JNI contract test against. */
class CoreDtoTest {
    private fun fixture(name: String) = ContractFixtures.read(name)

    /** Each fixture's DTO; a reply's is that of its `ok` value. */
    private val dtos: Map<String, KSerializer<*>> = mapOf(
        "init.json" to InitDto.serializer(),
        "call-config-assets-update-args.json" to ConfigAssetsArgs.serializer(),
        "call-ok-config-assets.json" to ConfigAssetsDto.serializer(),
        "start-config.json" to StartConfigDto.serializer(),
        "snapshot-running.json" to SnapshotDto.serializer(),
        "snapshot-died.json" to SnapshotDto.serializer(),
        "snapshot-stopped-catalog.json" to SnapshotDto.serializer(),
        "call-ok-empty.json" to Unit.serializer(),
        "call-ok-subscription.json" to SubscriptionDto.serializer(),
        "call-add-subscription-args.json" to AddSubscriptionArgs.serializer(),
        "call-set-subscription-user-agent-args.json" to SubscriptionUserAgentArgs.serializer(),
        "call-error.json" to CallErrorReplyDto.serializer(),
        "call-error-state-write.json" to CallErrorReplyDto.serializer(),
        "call-error-static-row-count.json" to CallErrorReplyDto.serializer(),
        "call-error-bootstrap-dns.json" to CallErrorReplyDto.serializer(),
        "call-select-args.json" to SelectArgs.serializer(),
        "call-ok-traffic-series.json" to TrafficSeriesDto.serializer(),
        "call-ok-usage.json" to UsageDto.serializer(),
        "call-ok-nodes.json" to NodesDto.serializer(),
        "call-ok-static-limits.json" to StaticNodeRowsDto.serializer(),
        "call-nodes-args.json" to NodesArgs.serializer(),
        "call-ok-score-state.json" to ScoreStateDto.serializer(),
        "call-score-state-args.json" to Unit.serializer(),
        "call-ok-recent-flows.json" to RecentFlowsDto.serializer(),
        "call-recent-flows-args.json" to Unit.serializer(),
        "call-ok-rules.json" to RulesDto.serializer(),
        "call-rules-args.json" to RulesArgs.serializer(),
        "call-set-rule-source-args.json" to RuleSourceArgs.serializer(),
        "call-set-bootstrap-dns-args.json" to BootstrapDnsArgs.serializer(),
        "call-ok-effective-config.json" to EffectiveConfigDto.serializer(),
        "call-set-mode-args.json" to ModeArgs.serializer(),
        "call-ok-diagnostics.json" to DiagnosticsDto.serializer(),
        "call-diagnostics-args.json" to DiagnosticsArgs.serializer(),
        "call-set-log-level-args.json" to LevelArgs.serializer(),
        "call-clear-crashes-args.json" to ClearCrashesArgs.serializer(),
        "static-nodes.json" to ListSerializer(StaticNodeCallFixture.serializer()),
    )

    @Test
    fun everyFixtureHasAParser() {
        val present = ContractFixtures.dir.listFiles { f -> f.extension == "json" }.orEmpty().map { it.name }.toSet()
        assertEquals(dtos.keys, present)
    }

    /** Decoding and encoding again gives the fixture back: the DTOs carry every field, with the core's names and types. */
    @Test
    fun everyFixtureRoundTrips() {
        val json = Json { encodeDefaults = true }
        for ((name, dto) in dtos) {
            @Suppress("UNCHECKED_CAST")
            val serializer = dto as KSerializer<Any>
            val whole = Json.parseToJsonElement(fixture(name))
            val payload = if (name.startsWith("call-ok-")) whole.jsonObject.getValue("ok") else whole
            assertEquals(name, payload, json.encodeToJsonElement(serializer, json.decodeFromJsonElement(serializer, payload)))
        }
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
        assertEquals(Mode.Global, catalog.mode)
        val groups = catalog.groups.map(GroupDto::toGroup)
        assertEquals(listOf("g-Proxy", "g-Auto", "g-Streaming", "g-AI", "g-HK"), groups.map { it.id })
        val proxy = groups.first()
        assertEquals(Group.Selection("g-Auto", false), proxy.tcp)
        assertEquals(Group.Selection("n-0c41be77", true), proxy.udp)
        assertTrue(proxy.networksDiffer)
        assertEquals("g-Auto", proxy.selectedId)
        assertFalse(proxy.pinned)
        val auto = groups[1]
        assertEquals(GroupKind.Score, auto.kind)
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
        assertNull(subscription.userAgent)
    }

    @Test
    fun legacyCatalogSharesTheSelectionAcrossNetworks() {
        val snapshot = Json.parseToJsonElement(fixture("snapshot-stopped-catalog.json")).jsonObject
        val catalog = snapshot.getValue("catalog").jsonObject
        val networkFields = setOf("tcpSelectedId", "udpSelectedId", "networksDiffer", "tcpPinned", "udpPinned")
        val groups = catalog.getValue("groups").jsonArray.mapIndexed { index, element ->
            val shared = element.jsonObject - networkFields
            JsonObject(if (index == 0) shared + ("pinned" to JsonPrimitive(true)) else shared)
        }
        val legacyCatalog = JsonObject(catalog + ("groups" to JsonArray(groups)))
        val legacySnapshot = JsonObject(snapshot + ("catalog" to legacyCatalog))

        val group = checkNotNull(CoreJson.snapshot(legacySnapshot.toString()).catalog).toCatalog().groups.first()
        val shared = Group.Selection("g-Auto", true)
        assertEquals(shared, group.tcp)
        assertEquals(shared, group.udp)
        assertFalse(group.networksDiffer)
    }

    @Test
    fun dnsFailureIsAProbeErrorOnTheNode() {
        val payload = """{"id":"n-dns","name":"DNS","failed":true,"error":"dns_failed","isGroup":false}"""
        val member = Json.decodeFromString(MemberDto.serializer(), payload).toMember()
        assertTrue(member.failed)
        assertNull(member.latencyMs)
        assertEquals(ErrorCode.DnsFailed, member.error)
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
    fun theRuleSourceMapsToTheEngineType() {
        val source = checkNotNull(CoreJson.snapshot(fixture("snapshot-stopped-catalog.json")).catalog).ruleSource.toRuleSource()
        assertEquals(listOf("cn-direct", 2, null), listOf(source.template, source.version, source.subscriptionId))
        assertEquals(RuleOptions(blockQuic = false), source.options)
        assertEquals(91044300313602L, source.rulesRevision)
        assertNull(source.error)
        val broken = RuleSourceDto("template", "cn-direct", 2, null, RuleOptionsDto(true), 0, 0, 0, 1, "parse_failed").toRuleSource()
        assertEquals(ErrorCode.ParseFailed, broken.error)
        assertTrue(broken.options.blockQuic)
    }

    @Test
    fun everyModeIsTheCoresOwn() {
        for (mode in Mode.entries) {
            val json = CoreJson.encode(ModeArgs.serializer(), ModeArgs(mode))
            assertEquals("""{"mode":"$mode"}""", json)
        }
        val rule = fixture("snapshot-stopped-catalog.json").replace("\"mode\": \"Global\"", "\"mode\": \"Rule\"")
        assertEquals(Mode.Rule, CoreJson.snapshot(rule).catalog?.mode)
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

        val page = rules.toRulePage()
        assertEquals(listOf(5, 5), listOf(page.total, page.rows.size))
        assertEquals(rules.rows.map { it.id }, page.rows.map { it.id })
        assertEquals(
            listOf(RuleTarget.Direct, RuleTarget.Group("g-Streaming"), RuleTarget.Group("g-AI"), RuleTarget.Direct, RuleTarget.Group("g-Proxy")),
            page.rows.map { it.target },
        )
        assertEquals(listOf(RuleKind.GeoIp, RuleKind.DomainSuffix, RuleKind.DomainSuffix, RuleKind.GeoIp, RuleKind.Match), page.rows.map { it.kind })
        assertEquals(listOf(1, 14, 6, 1, 0), page.rows.map { it.count })
        assertTrue(page.rows.all { it.skipped == null && it.skippedLines == 0 })
    }

    @Test
    fun aRowOfAKindNotCompiledKeepsItsNameAndReason() {
        fun row(kind: String, targetKind: String, skipped: String?) = RuleRowDto("r-1", 0, kind, "x", 1, "reject", targetKind, skipped, 1).toRuleRow()
        val geosite = row("GEOSITE", "reject", "geodata_missing")
        assertNull(geosite.kind)
        assertEquals("GEOSITE", geosite.kindName)
        assertEquals(RuleTarget.Reject, geosite.target)
        assertEquals(RuleSkip.GeodataMissing, geosite.skipped)
        assertEquals(RuleSkip.AppRule, row("PROCESS-NAME", "reject", "app_rule").skipped)
        assertEquals(RuleSkip.UnsupportedKind, row("DOMAIN-REGEX", "reject", "unsupported_kind").skipped)
        // A GeoIP row compiles its codes with data and skips the others.
        assertEquals(RuleKind.GeoIp to RuleSkip.GeodataMissing, row("GeoIp", "direct", "geodata_missing").let { it.kind to it.skipped })
        assertEquals(RuleSkip.UnsupportedKind, row("X", "reject", "from_a_newer_core").skipped)
    }

    @Test
    fun effectiveConfig() {
        val config = CoreJson.result(fixture("call-ok-effective-config.json"), EffectiveConfigDto.serializer())
        assertEquals(Mode.Rule, config.mode)
        assertEquals("g-Proxy", config.mainGroupId)
        assertEquals("g-Proxy", config.dnsVia)
        assertEquals(listOf("1.1.1.1:53", "8.8.8.8:53"), config.dnsUpstreams)
        assertEquals(91044300313602L, config.ruleSource.rulesRevision)
        assertTrue(config.text.startsWith("# mode: Rule\n"))
        assertTrue(config.text.endsWith("MATCH,Proxy\n"))
        assertFalse(config.truncated)

        val effective = config.toEffectiveConfig()
        assertEquals(Mode.Rule, effective.mode)
        assertEquals(RuleTarget.Group("g-Proxy"), effective.dnsVia)
        assertEquals(config.text, effective.text)
        assertEquals(config.ruleSource.toRuleSource(), effective.ruleSource)
        assertEquals(RuleTarget.Direct, config.copy(mode = Mode.Direct, dnsVia = "direct").toEffectiveConfig().dnsVia)
    }

    @Test
    fun ruleModeArgsEncodeAsTheFixtures() {
        val source = CoreJson.encode(RuleSourceArgs.serializer(), RuleSourceArgs("template", "cn-direct", RuleOptionsDto(blockQuic = true)))
        assertEquals(Json.parseToJsonElement(fixture("call-set-rule-source-args.json")), Json.parseToJsonElement(source))
        // An option that is off is still sent: the core needs every option.
        val off = CoreJson.encode(RuleSourceArgs.serializer(), RuleSourceArgs("template", "cn-direct", RuleOptionsDto(blockQuic = false)))
        assertEquals("""{"kind":"template","template":"cn-direct","options":{"blockQuic":false}}""", off)

        assertEquals(source, CoreJson.encode(RuleSourceArgs.serializer(), RuleSourceArgs.template("cn-direct", RuleOptions(blockQuic = true))))

        val mode = CoreJson.encode(ModeArgs.serializer(), ModeArgs(Mode.Rule))
        assertEquals(Json.parseToJsonElement(fixture("call-set-mode-args.json")), Json.parseToJsonElement(mode))
        val page = CoreJson.encode(RulesArgs.serializer(), RulesArgs(offset = 0, limit = 50))
        assertEquals(Json.parseToJsonElement(fixture("call-rules-args.json")), Json.parseToJsonElement(page))
    }

    @Test
    fun subscriptionArgsEncodeAsTheFixtures() {
        val add = AddSubscriptionArgs("https://example.invalid/sub?token=REDACTED", name = null, userAgent = "clash-verge/v2.2.3")
        val added = CoreJson.encode(AddSubscriptionArgs.serializer(), add)
        assertEquals(Json.parseToJsonElement(fixture("call-add-subscription-args.json")), Json.parseToJsonElement(added))
        // A null agent is sent as null: the core requires the key, and null means its default.
        val reset = CoreJson.encode(SubscriptionUserAgentArgs.serializer(), SubscriptionUserAgentArgs("s1", userAgent = null))
        assertEquals(Json.parseToJsonElement(fixture("call-set-subscription-user-agent-args.json")), Json.parseToJsonElement(reset))
    }

    /** As in the core: `addSubscription` may leave the agent out, `setSubscriptionUserAgent` may not. */
    @Test
    fun anAddWithoutTheAgentDecodesAndTheSetterNeedsItsKey() {
        val keyless = """{"url":"https://example.invalid/sub","name":null}"""
        val add = Json.decodeFromString(AddSubscriptionArgs.serializer(), keyless)
        assertEquals(AddSubscriptionArgs("https://example.invalid/sub", name = null, userAgent = null), add)
        // A null agent is left out, which the core reads as its default.
        assertEquals(keyless, CoreJson.encode(AddSubscriptionArgs.serializer(), add))
        assertThrows(SerializationException::class.java) {
            Json.decodeFromString(SubscriptionUserAgentArgs.serializer(), """{"id":"s1"}""")
        }
    }

    @Test
    fun callReplies() {
        assertEquals(Unit, CoreJson.result(fixture("call-ok-empty.json"), Unit.serializer()))
        val subscription = CoreJson.result(fixture("call-ok-subscription.json"), SubscriptionDto.serializer())
        assertEquals(2, subscription.nodes)
        val mapped = subscription.toSubscription()
        assertEquals("clash-verge/v2.2.3", mapped.userAgent)
        assertEquals(SubscriptionFormat.Clash, mapped.format)
        try {
            CoreJson.result(fixture("call-error.json"), Unit.serializer())
            fail("an error reply must throw")
        } catch (e: EngineException) {
            assertEquals(ErrorCode.TooLarge, e.code)
        }
    }

    @Test
    fun aFailedStateWriteMapsToInternal() {
        val failure = assertThrows(EngineException::class.java) {
            CoreJson.result(fixture("call-error-state-write.json"), Unit.serializer())
        }
        assertEquals(ErrorCode.Internal, failure.code)
    }

    @Test
    fun staticBatchLimitsAreRowErrors() {
        val reply = CoreJson.result(fixture("call-ok-static-limits.json"), StaticNodeRowsDto.serializer())
        assertEquals(
            listOf(StaticNodeResult.Invalid(StaticNodeReason.LinkLength), StaticNodeResult.Invalid(StaticNodeReason.CatalogFull)),
            reply.toRows(),
        )
    }

    @Test
    fun coreStaticNodeBatchesDecodeFieldsAndPortErrors() {
        val calls = Json.decodeFromString<List<StaticNodeCallFixture>>(fixture("static-nodes.json"))
        val reasons = mapOf("fields" to StaticNodeReason.Fields, "port" to StaticNodeReason.Port)
        val invalid = calls.flatMap { call ->
            val rows = call.reply["ok"]?.jsonObject?.get("rows")?.jsonArray.orEmpty()
            rows.mapNotNull { row ->
                val reason = row.jsonObject["reason"]?.jsonPrimitive?.content ?: return@mapNotNull null
                reasons[reason]?.let { expected -> expected to Json.decodeFromJsonElement(StaticNodeRowDto.serializer(), row).toRow() }
            }
        }.toMap()
        assertEquals(reasons.values.associateWith { StaticNodeResult.Invalid(it) }, invalid)
    }

    @Test
    fun unknownStaticNodeReasonsFailDecoding() {
        for (status in listOf("invalid", "unsupported")) {
            val reply = """{"ok":{"rows":[{"status":"$status","reason":"unknown"}]}}"""
            assertThrows(SerializationException::class.java) {
                CoreJson.result(reply, StaticNodeRowsDto.serializer())
            }
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

        val mapped = usage.toUsage()
        assertEquals(usage.today.byRule.map { listOf(it.id, it.up, it.down, it.flows) }, mapped.today.byRule.map { listOf(it.id, it.up, it.down, it.flows) })
        assertEquals(usage.today.outbounds.map { it.id }, mapped.today.outbounds.map { it.id })
        assertEquals(usage.today.flows, mapped.today.flows)
        assertEquals(checkNotNull(mapped.yesterday).down, yesterday.down)
        assertEquals(usage.days.map { it.date }, mapped.days.map { it.date })
        // A core from before byRule sends none.
        assertTrue(usage.copy(today = usage.today.copy(byRule = emptyList())).toUsage().today.byRule.isEmpty())

        val nodes = CoreJson.result(fixture("call-ok-nodes.json"), NodesDto.serializer()).nodes
        assertEquals("trojan", nodes[0].protocol)
        assertEquals(listOf(33, null, 31), nodes[0].tests.map { it.ms })
        assertEquals("quic", nodes[1].transport)
        assertTrue(nodes[1].tests.isEmpty())
    }

    @Test
    fun scoreStateKeepsSubgroupSelectionAndDecodesNullIds() {
        val reply = fixture("call-ok-score-state.json")
            .replace("\"selectedId\": \"n-7f3a19c2\"", "\"selectedId\": \"g-HK\", \"future\": true")
            .replace("\"nodeId\": \"n-0c41be77\"", "\"nodeId\": null")
            .replace("\"selected\": \"other\", \"selectedId\": \"n-0c41be77\"", "\"selected\": null, \"selectedId\": null")
        val score = CoreJson.result(reply, ScoreStateDto.serializer()).toScoreState()
        val auto = score.groups.single()
        assertEquals("g-HK", auto.tcp.selectedId)
        assertEquals(listOf("n-7f3a19c2", "n-0c41be77"), auto.nodeIds)
        assertNull(auto.tcp.challengers.single().nodeId)
        assertNull(auto.udp.selected)
        assertNull(auto.udp.selectedId)
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
        val encoded = CoreJson.encode(SelectArgs.serializer(), SelectArgs("g-Proxy", "n-0c41be77", SelectionNetwork.Tcp))
        assertEquals(Json.parseToJsonElement(fixture("call-select-args.json")), Json.parseToJsonElement(encoded))
        val auto = CoreJson.encode(SelectArgs.serializer(), SelectArgs("g-Proxy", null))
        assertEquals("""{"group":"g-Proxy","memberId":null}""", auto)
    }

    @Test
    fun diagnosticsReply() {
        val reply = CoreJson.result(fixture("call-ok-diagnostics.json"), DiagnosticsReplyReader)
        val dto = reply.dto
        assertEquals(CoreLevel.Info, dto.level)
        assertEquals(11, dto.events.size)
        val first = dto.events.first()
        assertEquals("init", first.name)
        assertEquals(2L, first.counts["nodes"])
        assertNull(first.code)
        assertEquals("internal", checkNotNull(dto.snapshot).error)
        assertEquals(1073741824L, dto.stats.today.downTotal)
        val diagnostics = dto.toCoreDiagnostics(reply.json)
        assertEquals(LogLevel.Info, diagnostics.level)
        assertEquals(listOf(CrashFile("1795300150000.txt", 5123)), diagnostics.crashes)
        assertEquals(1795300150000L, diagnostics.crashMark)
        // The export keeps the core's whole reply, not only what the app parsed.
        val ok = Json.parseToJsonElement(fixture("call-ok-diagnostics.json")).jsonObject.getValue("ok")
        assertEquals(ok, Json.parseToJsonElement(diagnostics.json))
    }

    @Test
    fun diagnosticsArgsEncodeAsTheFixtures() {
        val filter = CoreJson.encode(DiagnosticsArgs.serializer(), DiagnosticsArgs(CoreLevel.Warn))
        assertEquals(Json.parseToJsonElement(fixture("call-diagnostics-args.json")), Json.parseToJsonElement(filter))
        // Every event: the level is left out.
        assertEquals("{}", CoreJson.encode(DiagnosticsArgs.serializer(), DiagnosticsArgs(null)))
        val level = CoreJson.encode(LevelArgs.serializer(), LevelArgs(CoreLevel.Info))
        assertEquals(Json.parseToJsonElement(fixture("call-set-log-level-args.json")), Json.parseToJsonElement(level))
        val clear = CoreJson.encode(ClearCrashesArgs.serializer(), ClearCrashesArgs(listOf("1795300150000.txt"), 1795300150000L))
        assertEquals(Json.parseToJsonElement(fixture("call-clear-crashes-args.json")), Json.parseToJsonElement(clear))
        assertEquals(LogLevel.entries, LogLevel.entries.map { CoreLevel.of(it).toLogLevel() })
    }

    @Test
    fun unknownErrorCodeIsInternal() {
        assertEquals(ErrorCode.NoNodes, errorCodeOf("no_nodes"))
        assertEquals(ErrorCode.LocalNetwork, errorCodeOf("local_network"))
        assertEquals(ErrorCode.Internal, errorCodeOf("from_a_newer_core"))
    }
}
