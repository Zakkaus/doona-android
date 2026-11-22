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
    }

    @Test
    fun catalogMapsToEngineTypes() {
        val catalog = checkNotNull(CoreJson.snapshot(fixture("snapshot-stopped-catalog.json")).catalog)
        assertEquals(Mode.Global, catalog.mode)
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
