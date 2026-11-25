package dev.doona.android.vpn

import android.app.Application
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.NetworkCapabilities
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.doona.android.data.settings.LogLevelController
import dev.doona.android.data.settings.PerAppSettings
import dev.doona.android.engine.AppPolicy
import dev.doona.android.engine.ErrorCode
import dev.doona.android.engine.FailureReason
import dev.doona.android.engine.LatencyTest
import dev.doona.android.engine.LogLevel
import dev.doona.android.engine.Mode
import dev.doona.android.engine.NodeInfo
import dev.doona.android.engine.PerApp
import dev.doona.android.engine.RecentFlow
import dev.doona.android.engine.Status
import dev.doona.android.engine.TodayTraffic
import dev.doona.android.engine.Traffic
import dev.doona.android.engine.TrafficSeries
import dev.doona.android.report.AppEvents
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowNetwork
import org.robolectric.shadows.ShadowNetworkCapabilities
import java.io.IOException
import java.net.InetAddress

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class EngineTelemetryTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val core = FakeCore()

    @Test
    fun readsTrafficSeriesWhileStopped() = runTest {
        val engine = engine()
        core.reply = ContractFixtures.read("call-ok-traffic-series.json").replace("\"stepSeconds\"", "\"future\":true,\"stepSeconds\"")
        val series = engine.trafficSeries(300).getOrThrow()
        assertEquals("trafficSeries" to """{"seconds":300}""", core.calls.single())
        assertEquals(
            TrafficSeries(1, 1_795_343_040_000, listOf(3120, 2988, 141203, 46512, 9310), listOf(41208, 38250, 4200310, 1546977, 568120), 141203, 4200310),
            series,
        )
    }

    @Test
    fun readsRecentConnectionsFromTheCore() = runTest {
        val engine = engine()
        core.reply = """{"ok":{"flows":[]}}"""
        assertEquals(emptyList<RecentFlow>(), engine.recentFlows().getOrThrow())
        assertEquals("recentFlows" to "{}", core.calls.single())

        start(engine)
        core.reply = ContractFixtures.read("call-ok-recent-flows.json")
        assertEquals(
            listOf(
                RecentFlow("udp", "[2001:db8::1]:41002", "[2001:db8::2]:443", null, "g-Proxy", 371, true),
                RecentFlow("tcp", "172.19.0.1:41001", "192.0.2.1:443", "example.test", "direct", 12, false),
            ),
            engine.recentFlows().getOrThrow(),
        )
        assertEquals(listOf("recentFlows" to "{}", "recentFlows" to "{}"), core.calls)
    }

    @Test
    fun readsNodeEndpointsAndProbeHistory() = runTest {
        val engine = engine()
        core.reply = ContractFixtures.read("call-ok-nodes.json").replace("\"ms\": 33", "\"future\":true,\"ms\":33")
        val ids = listOf("n-0c41be77", "n-7f3a19c2")
        val nodes = engine.nodes(ids).getOrThrow()
        assertEquals("nodes", core.calls.single().first)
        assertEquals(Json.parseToJsonElement(ContractFixtures.read("call-nodes-args.json")), Json.parseToJsonElement(core.calls.single().second))
        assertEquals(
            listOf(
                NodeInfo(
                    ids[0],
                    "trojan",
                    "hk2.example.net",
                    443,
                    "tcp",
                    true,
                    listOf(LatencyTest(1_795_338_000_000, 33), LatencyTest(1_795_339_800_000, null), LatencyTest(1_795_341_600_000, 31)),
                ),
                NodeInfo(ids[1], "hysteria2", "192.168.8.20", 443, "quic", true, emptyList()),
            ),
            nodes,
        )
        assertEquals(nodes, engine.nodes().getOrThrow())
        assertEquals("nodes" to """{"ids":null}""", core.calls.last())
    }

    @Test
    fun readsSessionTrafficFromTheRunningSnapshot() = runTest {
        val engine = engine()
        backgroundScope.launch { engine.traffic.collect {} }
        runCurrent()
        val running = ContractFixtures.read("snapshot-running.json").replace("\"activeFlows\"", "\"future\":true,\"activeFlows\"")
        core.snapshotReplies += { running }
        start(engine)
        assertEquals(Traffic(upTotal = 1_048_576, downTotal = 52_428_800, activeFlows = 12, upPeak = 262_144, downPeak = 7_836_412), engine.traffic.value)
        engine.disconnect()
        runCurrent()
        assertEquals(Traffic(), engine.traffic.value)
    }

    @Test
    fun readsDayTotalsIndependentlyOfTunnelLifetime() = runTest {
        val engine = engine()
        runCurrent()
        assertEquals(TodayTraffic(2_097_152, 104_857_600, null), engine.today.value)

        start(engine)
        core.snapshotReplies += { ContractFixtures.read("snapshot-running.json") }
        engine.setMode(Mode.Rule).getOrThrow()
        val today = TodayTraffic(9_437_184, 1_288_490_188, 1_610_612_736)
        assertEquals(today, engine.today.value)
        engine.disconnect()
        runCurrent()
        assertEquals(today, engine.today.value)
    }

    @Test
    fun recoveryGrowsAcrossFailedStartsAndRepeatedDeathsAndCapsTheDelay() = runTest {
        val engine = engine()
        var starts = 0
        core.onStart = {
            if (++starts == 2) throw IllegalStateException("internal")
        }
        val service = ServiceSession(engine.controller)
        backgroundScope.launch { service.follow { engine.controller.release(host) } }
        assertTrue(service.startSystem(host) { error("Unexpected cancellation") })
        runCurrent()
        assertTrue(engine.status.value is Status.Running)

        reportDeadCore()
        assertEquals(Status.Failed(FailureReason.CoreStopped), engine.status.value)

        awaitRetry(engine, 1_000)
        assertEquals(2, starts)
        assertEquals(Status.Failed(FailureReason.StartFailed, ErrorCode.Internal), engine.status.value)
        awaitRetry(engine, 2_000)
        assertEquals(3, starts)
        assertTrue(engine.status.value is Status.Running)

        listOf(4_000L, 8_000L, 16_000L, 32_000L, 60_000L, 60_000L).forEachIndexed { index, wait ->
            reportDeadCore()
            assertEquals(Status.Failed(FailureReason.CoreStopped), engine.status.value)
            awaitRetry(engine, wait)
            assertEquals(4 + index, starts)
            assertTrue(engine.status.value is Status.Running)
        }
    }

    @Test
    fun sixtySecondsRunningResetsTheNextRecoveryDelay() = runTest {
        val engine = engine()
        assertTrue(engine.controller.startSystem(host) { error("Unexpected cancellation") } != null)
        runCurrent()
        reportDeadCore()
        awaitRetry(engine, 1_000)
        assertTrue(engine.status.value is Status.Running)

        advanceTimeBy(55_000)
        runCurrent()
        reportDeadCore()
        assertEquals(Status.Failed(FailureReason.CoreStopped), engine.status.value)
        awaitRetry(engine, 1_000)
        assertTrue(engine.status.value is Status.Running)
    }

    @Test
    fun anIncompatibleCoreDoesNotRecover() = runTest {
        core.schema = CORE_SCHEMA + 1
        val engine = engine()
        assertPermanentSystemFailure(engine, FailureReason.IncompatibleCore)
    }

    @Test
    fun anEmptyAllowListDoesNotRecover() = runTest {
        val engine = engine(perApp = PerApp(AppPolicy.OnlyListed))
        assertPermanentSystemFailure(engine, FailureReason.NoAllowedApps)
    }

    private fun TestScope.assertPermanentSystemFailure(engine: ServiceEngine, reason: FailureReason) {
        assertTrue(engine.controller.startSystem(host) { error("The service stops after reporting the failure") } != null)
        runCurrent()
        val failed = engine.controller.session.value
        assertEquals(Status.Failed(reason), failed.status)
        assertFalse(failed.recovering)
        advanceTimeBy(60_001)
        runCurrent()
        assertEquals(failed, engine.controller.session.value)
    }

    private fun TestScope.awaitRetry(engine: ServiceEngine, wait: Long) {
        val failure = engine.controller.session.value
        advanceTimeBy(wait - 1)
        runCurrent()
        assertEquals(failure, engine.controller.session.value)
        advanceTimeBy(1)
        runCurrent()
    }

    @Test
    fun manualStopCancelsPendingSystemRecoveryAndRedelivery() = runTest {
        val engine = engine()
        var starts = 0
        var redeliveryCancelled = false
        core.onStart = { starts++ }
        assertTrue(engine.controller.startSystem(host) { redeliveryCancelled = true } != null)
        runCurrent()
        reportDeadCore()
        assertEquals(Status.Failed(FailureReason.CoreStopped), engine.status.value)

        engine.disconnect()
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(Status.Stopped, engine.status.value)
        assertEquals(1, starts)
        assertTrue(redeliveryCancelled)
    }

    @Test
    fun revocationDuringBackoffPreventsAnotherStart() = runTest {
        val engine = engine()
        var starts = 0
        var cancelled = false
        core.onStart = { starts++ }
        assertTrue(engine.controller.startSystem(host) { cancelled = true } != null)
        runCurrent()
        reportDeadCore()

        engine.controller.revoke(host)
        advanceTimeBy(60_001)
        runCurrent()
        assertEquals(Status.Failed(FailureReason.Revoked), engine.status.value)
        assertEquals(1, starts)
        assertTrue(cancelled)
    }

    @Test
    fun connectDuringBackoffQueuesOneStartOnTheExistingSystemService() = runTest {
        val engine = engine()
        var starts = 0
        core.onStart = { starts++ }
        assertTrue(engine.controller.startSystem(host) { error("Unexpected cancellation") } != null)
        runCurrent()
        reportDeadCore()

        engine.connect()
        runCurrent()
        assertEquals(2, starts)
        assertTrue(engine.status.value is Status.Running)
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(2, starts)
    }

    @Test
    fun aFailedManualSessionDoesNotRestart() = runTest {
        val engine = engine()
        var starts = 0
        core.onStart = { starts++ }
        start(engine)
        reportDeadCore()
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(Status.Failed(FailureReason.CoreStopped), engine.status.value)
        assertEquals(1, starts)
    }

    private fun TestScope.reportDeadCore() {
        core.dataPathAlive = false
        advanceTimeBy(5_000)
        runCurrent()
        core.dataPathAlive = true
    }

    @Test
    fun stoppingARunningTunnelEndsDebugCapture() = runTest {
        lateinit var logLevels: LogLevelController
        val engine = engine { logLevels.endCapture() }
        logLevels = LogLevelController(engine::setLogLevel, backgroundScope) { testScheduler.currentTime }
        start(engine)
        logLevels.set(LogLevel.Debug).getOrThrow()

        engine.disconnect()
        runCurrent()

        assertEquals(Status.Stopped, engine.status.value)
        assertEquals(LogLevel.Info, logLevels.level.value)
        assertEquals("setLogLevel" to """{"level":"info"}""", core.calls.last())
    }

    @Test
    fun revokingTheTunnelEndsDebugCapture() = runTest {
        lateinit var levels: LogLevelController
        val engine = engine { levels.endCapture() }
        levels = LogLevelController(engine::setLogLevel, backgroundScope) { testScheduler.currentTime }
        start(engine)
        levels.set(LogLevel.Debug).getOrThrow()

        engine.controller.revoke(host)
        runCurrent()

        assertEquals(Status.Failed(FailureReason.Revoked), engine.status.value)
        assertEquals(LogLevel.Info, levels.level.value)
        assertEquals("setLogLevel" to """{"level":"info"}""", core.calls.last())
    }

    @Test
    fun aFailedStartEndsDebugCapture() = runTest {
        lateinit var levels: LogLevelController
        val engine = engine { levels.endCapture() }
        levels = LogLevelController(engine::setLogLevel, backgroundScope) { testScheduler.currentTime }
        levels.set(LogLevel.Debug).getOrThrow()
        core.onStart = { throw IllegalStateException("no_nodes") }

        engine.connect()
        assertTrue(engine.controller.start(host, engine.controller.session.value.generation))
        runCurrent()

        assertTrue(engine.status.value is Status.Failed)
        assertEquals(LogLevel.Info, levels.level.value)
        assertEquals("setLogLevel" to """{"level":"info"}""", core.calls.last())
    }

    @Test
    fun commandsAndReadsCheckTheElapsedDeadlineBeforeReachingCore() = runTest {
        var elapsed = 0L
        lateinit var levels: LogLevelController
        val engine = engine(expireDebugCapture = { levels.expireIfDue() })
        levels = LogLevelController(engine::setLogLevel, backgroundScope) { elapsed }
        levels.set(LogLevel.Debug).getOrThrow()
        runCurrent()
        elapsed += 3_600_000

        engine.setMode(Mode.Rule).getOrThrow()
        assertEquals(listOf("setLogLevel", "setLogLevel", "setMode"), core.calls.map { it.first })

        levels.set(LogLevel.Debug).getOrThrow()
        runCurrent()
        elapsed += 3_600_000
        core.reply = ContractFixtures.read("call-ok-traffic-series.json")
        assertTrue(engine.trafficSeries(300).isSuccess)
        assertEquals(listOf("setLogLevel" to """{"level":"info"}""", "trafficSeries" to """{"seconds":300}"""), core.calls.takeLast(2))
    }

    @Test
    fun commandBreadcrumbsCarryOutcomesWithoutArguments() = runTest {
        val engine = engine()
        AppEvents.clear()
        start(engine)
        engine.select("private-group", "private-node").getOrThrow()
        engine.testLatency("private-group").getOrThrow()
        core.reply = """{"error":{"code":"not_found"}}"""
        assertTrue(engine.updateSubscription("private-subscription").isFailure)
        engine.disconnect()
        runCurrent()

        val events = AppEvents.snapshot().map { it.text }.filter { it.startsWith("command=") }
        assertEquals(
            listOf(
                "command=start phase=complete outcome=ok code=ok",
                "command=select phase=complete outcome=ok code=ok",
                "command=testLatency phase=complete outcome=ok code=ok",
                "command=updateSubscription phase=complete outcome=failed code=NotFound",
                "command=stop phase=complete outcome=ok code=ok",
            ),
            events.map { it.substringBefore(" elapsedMs=") },
        )
        assertTrue(events.none { "private-" in it })
    }

    @Test
    fun resolverUpdatesLogARejectedListByCode() = runTest {
        val engine = engine()
        start(engine)
        AppEvents.clear()
        val session = engine.controller.session.value
        core.reply = ContractFixtures.read("call-error-bootstrap-dns.json")
        engine.controller.refreshBootstrapDns(listOf("192.0.2.54"))
        runCurrent()
        assertEquals(listOf("command=setBootstrapDns phase=complete outcome=failed code=InvalidArgument"), AppEvents.snapshot().map { it.text }.filter { it.startsWith("command=") })
        assertEquals(session, engine.controller.session.value)
    }

    @Test
    fun aResolverUpdateDuringOpenAppliesAfterStart() = runTest {
        val engine = engine()
        core.onStart = {
            engine.controller.refreshBootstrapDns(listOf("192.0.2.54"))
            assertTrue(core.calls.isEmpty())
        }
        start(engine)
        assertEquals(listOf("setBootstrapDns" to """{"servers":["192.0.2.54"]}"""), core.calls)
    }

    @Test
    fun aCallbackBeforeFailedInitIsAppliedOnSuccessfulInitWhileStopped() = runTest {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val connectivity = app.getSystemService(ConnectivityManager::class.java)
        val manager = shadowOf(connectivity)
        val existing = manager.networkCallbacks.toSet()
        core.initError = IOException("init failed")
        val engine = engine(connectivity = connectivity)
        val link = LinkProperties().apply { setDnsServers(listOf(InetAddress.getByName("192.0.2.54"))) }
        val wifi = ShadowNetwork.newInstance(100)
        val caps = ShadowNetworkCapabilities.newInstance()
        shadowOf(caps).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        val callback = (manager.networkCallbacks - existing).single()
        callback.onCapabilitiesChanged(wifi, caps)
        callback.onLinkPropertiesChanged(wifi, link)
        runCurrent()
        assertEquals(Status.Failed(FailureReason.StartFailed), engine.status.value)
        assertTrue(core.calls.isEmpty())

        core.initError = null
        engine.setMode(Mode.Direct).getOrThrow()
        runCurrent()
        assertEquals(listOf("setBootstrapDns" to """{"servers":["192.0.2.54"]}"""), core.calls.filter { it.first == "setBootstrapDns" })
    }

    private fun TestScope.start(engine: ServiceEngine) {
        engine.connect()
        assertTrue(engine.controller.start(host, engine.controller.session.value.generation))
        runCurrent()
        assertTrue(engine.status.value is Status.Running)
    }

    private fun TestScope.engine(
        connectivity: ConnectivityManager? = null,
        perApp: PerApp = PerApp(),
        expireDebugCapture: () -> Job? = { null },
        endDebugCapture: () -> Unit = {},
    ): ServiceEngine {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val opener = CoreOpener(core, { "/data" }, dispatcher)
        val settings = PerAppSettings(PerAppSettings.store(backgroundScope) { folder.root.resolve("per_app.preferences_pb") })
        val controller = TunnelController(
            core,
            opener,
            { perApp },
            backgroundScope,
            killProcess = { error("Unexpected timeout") },
            endDebugCapture = endDebugCapture,
            expireDebugCapture = expireDebugCapture,
            clock = { testScheduler.currentTime },
        )
        return ServiceEngine(core, opener, controller, settings, { true }, dispatcher, backgroundScope, expireDebugCapture)
            .also { it.launch(dispatcher, connectivity) }
    }

    private val host = TestHost()

    private class TestHost : TunHost {
        override val selfPackage = "dev.doona.android"

        override fun installed(packages: Set<String>) = packages

        override fun bootstrapDns() = emptyList<String>()

        override fun establish(routing: AppRouting, mtu: Int) = object : Tun {
            override val fd = 3

            override fun close() = Unit
        }
    }
}
