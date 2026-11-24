package dev.doona.android.report

import android.os.Process
import dev.doona.android.data.settings.LogLevelController
import dev.doona.android.engine.Engine
import dev.doona.android.engine.LogLevel
import dev.doona.android.engine.RecentFlow
import dev.doona.android.engine.Status
import dev.doona.android.engine.fake.FakeEngine
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FlowEventsTest : ExportFixture() {
    @After
    fun clearEvents() = AppEvents.clear()

    @Test
    fun `overlapping reads record each socket and start time once per tunnel`() = runTest {
        val rows = listOf(
            FLOW.copy(name = "other-protocol.example", protocol = "udp"),
            FLOW.copy(name = "other-local.example", local = "172.19.0.2:41002"),
            FLOW.copy(name = "other-remote.example", remote = "192.0.2.3:443"),
            FLOW.copy(name = "later-start.example", startedMs = FLOW.startedMs + 1),
            FLOW,
        )
        val engine = fixtureEngine().apply { recentFlowRows = rows }
        val producer = producer(engine)
        producer.recordRecent()
        engine.recentFlowRows = rows.map { it.copy(open = false) }
        producer.recordRecent()
        val names = rows.asReversed().map { it.name }
        assertEquals(names, AppEvents.snapshot().map { it.visitedDomain })

        engine.disconnect()
        engine.connect()
        engine.recentFlowRows = listOf(FLOW)
        producer.recordRecent()
        assertEquals(names + FLOW.name, AppEvents.snapshot().map { it.visitedDomain })
    }

    @Test
    fun `only open sockets have an owner and the first sorted package wins`() = runTest {
        val closed = FLOW.copy(name = "closed.example", open = false, startedMs = 0)
        val engine = fixtureEngine().apply { recentFlowRows = listOf(FLOW, closed) }
        val lookups = mutableListOf<Pair<String, String>>()
        val producer = producer(engine, { _, local, remote ->
            lookups.add(local to remote)
            10123
        }, { arrayOf("com.zulu.app", PACKAGE) })
        producer.recordRecent()
        assertEquals(listOf(FLOW.local to FLOW.remote), lookups)
        assertEquals(listOf(closed.name to null, FLOW.name to PACKAGE), AppEvents.snapshot().map { it.visitedDomain to it.appPackage })
    }

    @Test
    fun `a denied lookup still records the flow without a package`() = runTest {
        val engine = fixtureEngine().apply { recentFlowRows = listOf(FLOW) }
        producer(engine, { _, _, _ -> throw SecurityException("VPN lost") }, { arrayOf(PACKAGE) }).recordRecent()
        assertEquals(FLOW.name to null, AppEvents.snapshot().single().let { it.visitedDomain to it.appPackage })
    }

    @Test
    fun `an invalid UID has no package`() = runTest {
        val engine = fixtureEngine().apply { recentFlowRows = listOf(FLOW) }
        producer(engine, packages = { arrayOf(PACKAGE) }).recordRecent()
        assertEquals(FLOW.name to null, AppEvents.snapshot().single().let { it.visitedDomain to it.appPackage })
    }

    @Test
    fun `capture samples every two seconds only while Debug and the tunnel are running`() = runTest {
        val fake = FakeEngine()
        val reads = mutableListOf<Long>()
        val engine = object : Engine by fake {
            override suspend fun recentFlows() = fake.recentFlows().also { reads.add(testScheduler.currentTime) }
        }
        val levels = LogLevelController(fake::setLogLevel, backgroundScope) { testScheduler.currentTime }
        backgroundScope.launch { producer(engine).followCapture(levels) }
        levels.set(LogLevel.Debug).getOrThrow()
        advanceTimeBy(4_000)
        assertTrue(reads.isEmpty())

        fake.connect()
        runCurrent()
        advanceTimeBy(1_999)
        runCurrent()
        assertEquals(listOf(4_000L), reads)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf(4_000L, 6_000L), reads)

        levels.set(LogLevel.Info).getOrThrow()
        advanceTimeBy(4_000)
        assertEquals(listOf(4_000L, 6_000L), reads)
        levels.set(LogLevel.Debug).getOrThrow()
        runCurrent()
        fake.disconnect()
        advanceTimeBy(4_000)
        assertEquals(listOf(4_000L, 6_000L, 10_000L), reads)
    }

    @Test
    fun `capture stops at the elapsed deadline even if the core refuses to reset`() = runTest {
        val fake = fixtureEngine().apply { recentFlowRows = listOf(FLOW) }
        var lastReadMs = -1L
        val engine = object : Engine by fake {
            override suspend fun recentFlows() = fake.recentFlows().also { lastReadMs = testScheduler.currentTime }
        }
        val levels = LogLevelController({ level ->
            if (level == LogLevel.Debug) fake.setLogLevel(level) else Result.failure(IllegalStateException("refused"))
        }, backgroundScope) { testScheduler.currentTime }
        backgroundScope.launch { producer(engine).followCapture(levels) }
        levels.set(LogLevel.Debug).getOrThrow()
        advanceTimeBy(3_605_000)
        assertEquals(3_598_000L, lastReadMs)
    }

    @Test
    fun `the ZIP discloses produced domains and packages only under their switches and never exposes sockets`() = runTest {
        val engine = fixtureEngine().apply { recentFlowRows = listOf(FLOW) }
        val report = export(engine, flowEvents = producer(engine, { _, _, _ -> 10123 }, { arrayOf(PACKAGE) }))
        for (showDomain in listOf(false, true)) {
            for (showApp in listOf(false, true)) {
                val zip = report.build(RedactionOptions(showVisitedDomains = showDomain, showAppNames = showApp))!!
                val text = entries(zip.file).values.joinToString("\n")
                assertTrue("flow event missing", "flow tcp" in text)
                assertEquals("visited domain", showDomain, FLOW.name!! in text)
                assertEquals("app package", showApp, PACKAGE in text)
                for (secret in listOf(FLOW.local, FLOW.remote, FLOW.outbound)) assertFalse(secret, secret in text)
            }
        }
    }

    private fun TestScope.producer(
        engine: Engine,
        owner: (Int, String, String) -> Int = { _, _, _ -> Process.INVALID_UID },
        packages: (Int) -> Array<String>? = { null },
    ) = FlowEvents(engine, StandardTestDispatcher(testScheduler), owner, packages)

    companion object {
        private const val PACKAGE = "com.alice.hunter2"
        private val FLOW = RecentFlow("tcp", "172.19.0.1:41002", "192.0.2.2:443", "alice.hunter2.example", "alice.outbound.secret", 12, true)
    }
}
