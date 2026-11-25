package dev.doona.android.engine.fake

import dev.doona.android.engine.AppPolicy
import dev.doona.android.engine.LogLevel
import dev.doona.android.engine.Mode
import dev.doona.android.engine.NetworkState
import dev.doona.android.engine.NetworkTransport
import dev.doona.android.engine.PerApp
import dev.doona.android.engine.RuleKind
import dev.doona.android.engine.RuleOptions
import dev.doona.android.engine.Status
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RvSampleEngineTest {
    @Test
    fun allReadsServeTheRvDatasetWithoutTheCore() = runTest {
        val engine = rvSampleEngine()
        val catalog = checkNotNull(engine.catalog.value)
        val main = catalog.groups.first { it.id == catalog.mainGroupId }
        assertTrue(engine.ready.value && engine.perAppLoaded.value)
        assertEquals(Status.Running(1_795_336_920_000), engine.status.value)
        assertEquals(Mode.Rule, engine.settings.value.mode)
        assertNull(engine.warning.value)
        assertEquals(NetworkState(NetworkTransport.WiFi, metered = false, validated = true), engine.network.value)
        assertEquals("Hong Kong 02 IPLC", engine.groups.value.first().members.first { it.id == main.selectedId }.name)
        assertEquals(listOf("Harbor Cloud" to 32, "Lantern Net" to 16), engine.subscriptions.value.map { it.name to it.nodes })
        val nodes = engine.nodes().getOrThrow()
        assertEquals(main.members.map { it.id }, nodes.map { it.id })
        val singapore = nodes.first { it.server == "sg3.example.net" }
        val newYork = nodes.first { it.server == "us5.example.net" }
        assertEquals(listOf(92, 88, 97, 104, 131, 158, 172, 186), singapore.tests.map { it.ms })
        assertNull(newYork.tests.last().ms)
        assertEquals(listOf(newYork, singapore), engine.nodes(listOf(newYork.id, "unknown", singapore.id)).getOrThrow())

        val usage = engine.usage().getOrThrow().today
        assertEquals(2_862_000_000L, engine.today.value.yesterdayByNow)
        assertEquals(engine.today.value.downTotal, usage.down.sum())
        assertEquals(usage.down.sum(), usage.byRule.sumOf { it.down })
        val rules = engine.rules(0, 50).getOrThrow()
        assertEquals(14, rules.total)
        assertEquals(RuleKind.Match, rules.rows.last().kind)
        assertTrue(usage.byRule.all { bytes -> rules.rows.any { it.id == bytes.id } })
        assertTrue(engine.effectiveConfig().getOrThrow().text.contains("DOMAIN,gemini.google.com,AI"))
        assertFalse(engine.effectiveConfig().getOrThrow().text.contains("icloud.com"))

        val minute = engine.trafficSeries(60).getOrThrow()
        assertEquals(engine.traffic.value.downHistory, minute.down)
        assertEquals(1_981_209L, minute.down.last())
        for ((seconds, step) in listOf(300L to 1L, 900L to 3L, 3_600L to 12L)) {
            val series = engine.trafficSeries(seconds).getOrThrow()
            assertEquals(step, series.stepSeconds)
            assertEquals(minute.endEpochMs, series.endEpochMs)
        }
        assertEquals(7_836_412L, engine.trafficSeries(3_600).getOrThrow().downPeak)
    }

    @Test
    fun scoreSamplesKeepDifferentWinnersByNetworkAndAWaitingRegion() = runTest {
        for (engine in listOf(FakeEngine(), rvSampleEngine())) {
            val score = engine.scoreState().getOrThrow()
            assertEquals(listOf("g-Auto", "g-HK"), score.groups.map { it.id })
            val (auto, region) = score.groups
            val nodes = engine.groups.value.first { it.id == auto.id }.members
            assertEquals(nodes.map { it.id }, auto.nodeIds)
            assertEquals(nodes[0].id, auto.tcp.selectedId)
            assertEquals(nodes[1].id, auto.udp.selectedId)
            assertEquals("observedUsable", auto.tcp.state)
            assertEquals("provisional", region.tcp.state)
            assertEquals("comparableTraffic", region.tcp.waitReason)
        }
    }

    @Test
    fun commandsChangePreviewStateWithoutTheCore() = runTest {
        val engine = rvSampleEngine()
        engine.disconnect()
        assertEquals(Status.Stopped, engine.status.value)
        engine.connect()
        assertTrue(engine.status.value is Status.Running)

        val main = engine.groups.value.first()
        val first = main.members.first().id
        engine.select(main.id, first).getOrThrow()
        assertEquals(first, engine.groups.value.first().selectedId)
        engine.testLatency(main.id).getOrThrow()
        engine.setMode(Mode.Direct).getOrThrow()
        assertEquals(Mode.Direct, engine.effectiveConfig().getOrThrow().mode)
        engine.setRuleSource("cn-direct", RuleOptions(blockQuic = true)).getOrThrow()
        assertTrue(engine.effectiveConfig().getOrThrow().ruleSource.options.blockQuic)
        val perApp = PerApp(AppPolicy.ExceptListed, setOf("dev.example"))
        engine.setPerApp(perApp).getOrThrow()
        assertEquals(perApp, engine.settings.value.perApp)

        val added = engine.addSubscription("https://example.invalid/sub", "Sample").getOrThrow()
        assertEquals(added, engine.updateSubscription(added.id).getOrThrow())
        val changed = engine.setSubscriptionUserAgent(added.id, "Sample/1").getOrThrow()
        assertEquals("Sample/1", changed.userAgent)
        engine.removeSubscription(added.id).getOrThrow()
        assertFalse(engine.subscriptions.value.any { it.id == added.id })

        engine.setLogLevel(LogLevel.Warn).getOrThrow()
        val diagnostics = engine.diagnostics(LogLevel.Warn).getOrThrow()
        assertEquals(LogLevel.Warn, diagnostics.level)
        engine.clearCrashes(diagnostics.crashes.map { it.name }, diagnostics.crashMark).getOrThrow()
    }
}
