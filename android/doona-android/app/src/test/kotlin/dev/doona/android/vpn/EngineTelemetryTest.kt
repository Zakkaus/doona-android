package dev.doona.android.vpn

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.doona.android.data.settings.LogLevelController
import dev.doona.android.data.settings.PerAppSettings
import dev.doona.android.engine.LatencyTest
import dev.doona.android.engine.LogLevel
import dev.doona.android.engine.Mode
import dev.doona.android.engine.NodeInfo
import dev.doona.android.engine.PerApp
import dev.doona.android.engine.Status
import dev.doona.android.engine.TodayTraffic
import dev.doona.android.engine.Traffic
import dev.doona.android.engine.TrafficSeries
import dev.doona.android.report.AppEvents
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

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

    private fun TestScope.start(engine: ServiceEngine) {
        engine.connect()
        assertTrue(engine.controller.start(Host, engine.controller.session.value.generation))
        runCurrent()
        assertTrue(engine.status.value is Status.Running)
    }

    private fun TestScope.engine(endDebugCapture: () -> Unit = {}): ServiceEngine {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val opener = CoreOpener(core, { "/data" }, dispatcher)
        val settings = PerAppSettings(PerAppSettings.store(backgroundScope) { folder.root.resolve("per_app.preferences_pb") })
        val controller = TunnelController(core, opener, { PerApp() }, backgroundScope, killProcess = { error("Unexpected timeout") })
        return ServiceEngine(core, opener, controller, settings, { true }, dispatcher, backgroundScope, endDebugCapture).also { it.launch(dispatcher) }
    }

    private object Host : TunHost {
        override val selfPackage = "dev.doona.android"

        override fun installed(packages: Set<String>) = packages

        override fun bootstrapDns() = emptyList<String>()

        override fun establish(routing: AppRouting, mtu: Int) = object : Tun {
            override val fd = 3

            override fun close() = Unit
        }
    }
}
