package dev.doona.android.report

import android.os.Process
import dev.doona.android.data.settings.LogLevelController
import dev.doona.android.feature.settings.RvLogLevel
import dev.doona.android.vpn.FakeHonk
import dev.doona.android.vpn.HonkFixtures
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** honk's flows (`GET /flows`, `detail=full`) as the report's events; bodies are `flows/full.json` with other values. */
@OptIn(ExperimentalCoroutinesApi::class)
class FlowEventsTest : ExportFixture() {
    @After
    fun clearEvents() = AppEvents.clear()

    @Test
    fun `repeated reads record each flow once per honk instance, oldest first`() = runTest {
        val flows = listOf(
            Flow("flow-3", domain = "later.example", startedAt = "2026-08-15T09:59:53Z"),
            Flow("flow-1", domain = "first.example", network = "udp", startedAt = "2026-08-15T09:59:51Z"),
            Flow("flow-2", domain = "second.example", startedAt = "2026-08-15T09:59:52Z"),
        )
        val honk = honk(flows)
        val producer = producer(honk)
        producer.recordRecent()
        honk.on("GET", ReportHonk.FLOWS, 200, list(flows.map { it.copy(state = "closed") }))
        producer.recordRecent()
        val names = listOf("first.example", "second.example", "later.example")
        assertEquals(names, producer.snapshot().map { it.visitedDomain })
        assertEquals(listOf(TCP_FLOW_EVENT, TCP_FLOW_EVENT, UDP_FLOW_EVENT).sorted(), producer.snapshot().map { it.text }.sorted())

        // honk started again: its flow ids start again too.
        honk.on("GET", ReportHonk.FLOWS, 200, list(listOf(flows[1]), instance = "instance-8"))
        producer.recordRecent()
        assertEquals(names + "first.example", producer.snapshot().map { it.visitedDomain })
    }

    @Test
    fun `only active flows have an owner and the first sorted package wins`() = runTest {
        val closed = Flow("flow-1", domain = "closed.example", state = "closed", startedAt = "2026-08-15T09:59:40Z")
        val lookups = mutableListOf<Pair<String, String>>()
        val producer = producer(honk(listOf(FLOW, closed)), { _, local, remote ->
            lookups.add(local to remote)
            10123
        }, { arrayOf("com.zulu.app", PACKAGE) })
        producer.recordRecent()
        assertEquals(listOf(FLOW.src to FLOW.dst), lookups)
        assertEquals(listOf("closed.example" to null, FLOW.domain to PACKAGE), producer.snapshot().map { it.visitedDomain to it.appPackage })
    }

    @Test
    fun `a denied lookup still records the flow without a package`() = runTest {
        val producer = producer(honk(listOf(FLOW)), { _, _, _ -> throw SecurityException("VPN lost") }, { arrayOf(PACKAGE) })
        producer.recordRecent()
        assertEquals(FLOW.domain to null, producer.snapshot().single().let { it.visitedDomain to it.appPackage })
    }

    @Test
    fun `an invalid UID has no package`() = runTest {
        val producer = producer(honk(listOf(FLOW)), packages = { arrayOf(PACKAGE) })
        producer.recordRecent()
        assertEquals(FLOW.domain to null, producer.snapshot().single().let { it.visitedDomain to it.appPackage })
    }

    @Test
    fun `without honk a read records nothing`() = runTest {
        val producer = producer(ReportHonk.down())
        producer.recordRecent()
        assertTrue(producer.snapshot().isEmpty())
    }

    @Test
    fun `capture reads every two seconds only while Debug lasts`() = runTest {
        val honk = honk(listOf(FLOW))
        val levels = LogLevelController({ Result.success(Unit) }, backgroundScope) { testScheduler.currentTime }
        backgroundScope.launch { producer(honk).followCapture(levels) }
        advanceTimeBy(4_000)
        assertTrue(honk.reads().isEmpty())

        levels.set(RvLogLevel.Debug).getOrThrow()
        runCurrent()
        advanceTimeBy(1_999)
        runCurrent()
        assertEquals(1, honk.reads().size)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(2, honk.reads().size)

        levels.set(RvLogLevel.Info).getOrThrow()
        advanceTimeBy(4_000)
        assertEquals(2, honk.reads().size)
    }

    @Test
    fun `capture stops at the elapsed deadline even if honk refuses to reset`() = runTest {
        val honk = honk(listOf(FLOW))
        val levels = LogLevelController({ level ->
            if (level == RvLogLevel.Debug) Result.success(Unit) else Result.failure(IllegalStateException("refused"))
        }, backgroundScope) { testScheduler.currentTime }
        backgroundScope.launch { producer(honk).followCapture(levels) }
        levels.set(RvLogLevel.Debug).getOrThrow()
        advanceTimeBy(3_605_000)
        assertEquals(1_800, honk.reads().size)
    }

    @Test
    fun `the ZIP discloses domains and packages only under their switches and never exposes addresses`() = runTest {
        val honk = honk(listOf(FLOW))
        val report = export(fixtureEngine(), flowEvents = producer(honk, { _, _, _ -> 10123 }, { arrayOf(PACKAGE) }))
        for (showDomain in listOf(false, true)) {
            for (showApp in listOf(false, true)) {
                val zip = report.build(RedactionOptions(showVisitedDomains = showDomain, showAppNames = showApp))!!
                val text = entries(zip.file).values.joinToString("\n")
                assertEquals("flow event presence", showDomain || showApp, "flow tcp" in text)
                assertEquals("visited domain", showDomain, FLOW.domain in text)
                assertEquals("app package", showApp, PACKAGE in text)
                for (secret in listOf(FLOW.src, FLOW.dst, "flow-23", "tcp-01HZX4K8W5")) assertFalse(secret, secret in text)
            }
        }
    }

    @Test
    fun `exporting 128 flows preserves an earlier app warning`() = runTest {
        val honk = honk(newerFlows())
        val warning = AppEvents.record(AppEventLevel.Warn, "health check: data path down")
        repeat(198) { AppEvents.record(AppEventLevel.Info, "diagnostics: ok after 1 ms") }
        val report = export(fixtureEngine(), flowEvents = producer(honk))
        val zip = report.build(RedactionOptions(showVisitedDomains = true))!!
        val text = entries(zip.file).getValue(ReportExport.REPORT_ENTRY)
        assertTrue(warning.text in text)
        assertTrue(warning in AppEvents.snapshot())
        assertEquals(128, Regex("flow tcp").findAll(text).count())
    }

    @Test
    fun `both switches off skip reading and omit flows read before`() = runTest {
        val honk = honk(listOf(FLOW))
        val producer = producer(honk, { _, _, _ -> 10123 }, { arrayOf(PACKAGE) })
        producer.recordRecent()
        val zip = export(fixtureEngine(), flowEvents = producer).build(RedactionOptions(showVisitedDomains = false, showAppNames = false))!!
        val text = entries(zip.file).getValue(ReportExport.REPORT_ENTRY)
        assertEquals("flow reads", 1, honk.reads().size)
        assertFalse("flow tcp" in text)
        assertFalse(FLOW.domain in text)
        assertFalse(PACKAGE in text)
    }

    @Test
    fun `a flow honk no longer holds no longer suppresses a new one by its id`() = runTest {
        val honk = honk(listOf(FLOW))
        val producer = producer(honk)
        producer.recordRecent()
        val newer = newerFlows()
        honk.on("GET", ReportHonk.FLOWS, 200, list(newer))
        producer.recordRecent()
        assertEquals(newer.map { it.domain }, producer.snapshot().map { it.visitedDomain })

        val reused = FLOW.copy(domain = "reused.example", startedAt = "2026-08-15T10:30:00Z")
        honk.on("GET", ReportHonk.FLOWS, 200, list(newer.drop(1) + reused))
        producer.recordRecent()
        assertEquals(newer.drop(1).map { it.domain } + "reused.example", producer.snapshot().map { it.visitedDomain })
    }

    /** A flow of `flows/full.json` with these values. */
    private data class Flow(
        val id: String,
        val domain: String = "alice.hunter2.example",
        val network: String = "tcp",
        val state: String = "active",
        val startedAt: String = "2026-08-15T09:59:50Z",
        val src: String = "192.168.1.100:12345",
        val dst: String = "1.2.3.4:443",
    )

    private fun list(flows: List<Flow>, instance: String = "instance-7"): String {
        val template = Json.parseToJsonElement(HonkFixtures.body("flows/full")).jsonObject
        val flow = template.getValue("flows").jsonArray.single().jsonObject
        val input = flow.getValue("input").jsonObject
        val rows = flows.map { f ->
            JsonObject(
                flow + mapOf(
                    "id" to JsonPrimitive(f.id),
                    "instance_id" to JsonPrimitive(instance),
                    "network" to JsonPrimitive(f.network),
                    "state" to JsonPrimitive(f.state),
                    "started_at" to JsonPrimitive(f.startedAt),
                    "input" to JsonObject(input + mapOf("src" to JsonPrimitive(f.src), "dst" to JsonPrimitive(f.dst), "domain" to JsonPrimitive(f.domain))),
                ),
            )
        }
        return JsonObject(template + mapOf("instance_id" to JsonPrimitive(instance), "flows" to JsonArray(rows))).toString()
    }

    private fun honk(flows: List<Flow>) = FakeHonk().apply { on("GET", ReportHonk.FLOWS, 200, list(flows)) }

    private fun FakeHonk.reads() = calls.filter { it.path == ReportHonk.FLOWS }

    private fun newerFlows() = List(128) { Flow("flow-n$it", domain = "flow-$it.example", startedAt = "2026-08-15T10:%02d:%02dZ".format(it / 60, it % 60)) }

    private fun TestScope.producer(
        honk: FakeHonk,
        owner: (Int, String, String) -> Int = { _, _, _ -> Process.INVALID_UID },
        packages: (Int) -> Array<String>? = { null },
    ) = FlowEvents(honk, StandardTestDispatcher(testScheduler), owner, packages)

    companion object {
        private const val PACKAGE = "com.alice.hunter2"
        private val FLOW = Flow("flow-23")
    }
}
