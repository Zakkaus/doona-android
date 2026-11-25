package dev.doona.android.feature.traffic

import dev.doona.android.engine.Engine
import dev.doona.android.engine.EngineException
import dev.doona.android.engine.ErrorCode
import dev.doona.android.engine.Status
import dev.doona.android.engine.Traffic
import dev.doona.android.engine.TrafficSeries
import dev.doona.android.engine.fake.FakeEngine
import dev.doona.android.engine.fake.rvSampleEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** [TrafficViewModel.ui]: 流量詳情 from the engine, reading `trafficSeries` per range and nothing else. */
@OptIn(ExperimentalCoroutinesApi::class)
class TrafficViewModelTest {
    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    /** Records each `trafficSeries` read; [fail] makes the next reads fail. */
    private class Recording(private val fake: FakeEngine) : Engine by fake {
        val reads = mutableListOf<Long>()
        var fail = false

        override suspend fun trafficSeries(seconds: Long): Result<TrafficSeries> {
            reads += seconds
            return if (fail) Result.failure(EngineException(ErrorCode.Internal)) else fake.trafficSeries(seconds)
        }
    }

    private fun TestScope.shown(engine: Engine): TrafficViewModel {
        val model = TrafficViewModel(engine, { NOW })
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.ui.collect {} }
        return model
    }

    @Test
    fun theRangesReadSixtyThreeHundredNineHundredAndThirtySixHundredSeconds() = runTest {
        val engine = Recording(rvSampleEngine())
        val model = shown(engine)
        for (range in 1..3) model.onRange(range)
        model.onRange(0)
        assertEquals(listOf(60L, 300L, 900L, 3_600L, 60L), engine.reads)
        assertEquals("the last range's chart", RV_TRAFFIC_LIVE, model.ui.value.chart?.range)
    }

    @Test
    fun theLiveMinuteMovesWithEachSecondWithoutReadingAgain() = runTest {
        val fake = rvSampleEngine()
        val engine = Recording(fake)
        val model = shown(engine)
        val before = model.ui.value.chart!!
        assertEquals("the engine's minute", fake.traffic.value.downHistory.map(Long::toFloat), before.down)
        fake.traffic.value = fake.traffic.value.let { it.copy(downRate = 42, downHistory = it.downHistory.drop(1) + 42L, upHistory = it.upHistory.drop(1) + 7L) }
        val after = model.ui.value.chart!!
        assertEquals("the new second ends it", 42f, after.down.last())
        assertEquals(7f, after.up.last())
        assertEquals("the hero's rate is the second's", 42L, model.ui.value.downRate)
        assertEquals("one read, at the start", listOf(60L), engine.reads)
    }

    @Test
    fun aPastWindowIsReadOnceAndStaysAsTheSecondsGoBy() = runTest {
        val fake = rvSampleEngine()
        val engine = Recording(fake)
        val model = shown(engine)
        model.onRange(3)
        val hour = model.ui.value.chart!!
        assertEquals(3, hour.range)
        assertEquals(RV_TRAFFIC_POINTS, hour.down.size)
        assertEquals("a minute a point", 60_000L, hour.stepMs)
        fake.traffic.value = fake.traffic.value.copy(downRate = 1, downHistory = fake.traffic.value.downHistory + 1L)
        assertSame("the window is not read again", hour, model.ui.value.chart)
        assertEquals(listOf(60L, 3_600L), engine.reads)
    }

    @Test
    fun theTunnelStartingOrStoppingReadsTheRangeAgain() = runTest {
        val fake = rvSampleEngine()
        val engine = Recording(fake)
        val model = shown(engine)
        model.onRange(2)
        fake.status.value = Status.Stopped
        fake.traffic.value = Traffic()
        fake.status.value = Status.Running(NOW)
        assertEquals(listOf(60L, 900L, 900L, 900L), engine.reads)
    }

    @Test
    fun peaksTotalsAndTheHeroRateAreTheEngines() = runTest {
        val fake = rvSampleEngine()
        val model = shown(Recording(fake))
        val traffic = fake.traffic.value
        val state = model.ui.value
        val session = state.session!!
        assertEquals(traffic.downRate, state.downRate)
        assertEquals(traffic.upRate, state.upRate)
        assertEquals(traffic.downPeak.toDouble(), session.peakDown, 0.0)
        assertEquals(traffic.upPeak.toDouble(), session.peakUp, 0.0)
        assertEquals(traffic.downTotal, session.downTotal)
        assertEquals(traffic.upTotal, session.upTotal)
        val since = (fake.status.value as Status.Running).sinceEpochMs
        assertEquals((NOW - since) / 1_000, session.seconds)
        assertEquals(traffic.downTotal / ((NOW - since) / 1_000.0), session.averageDown, 1e-6)
    }

    @Test
    fun aFailedReadKeepsTheLastChart() = runTest {
        val engine = Recording(rvSampleEngine())
        val model = shown(engine)
        val minute = model.ui.value.chart
        engine.fail = true
        model.onRange(2)
        assertEquals(listOf(60L, 900L), engine.reads)
        assertSame("still the minute, nothing invented", minute, model.ui.value.chart)
    }

    @Test
    fun stoppedWithNothingReadDrawsNoChartAndNoSession() = runTest {
        val fake = FakeEngine(status = Status.Stopped)
        val engine = Recording(fake).apply { fail = true }
        val model = shown(engine)
        val state = model.ui.value
        assertNull("no read, no history: no chart", state.chart)
        assertNull("no tunnel: no figures", state.session)
        assertEquals(0L, state.downRate)
    }

    @Test
    fun stoppedTheMinuteIsWhatTheCoreKept() = runTest {
        // The core answers a stopped tunnel with the window as it was: zeros for the seconds since, and no history samples.
        val model = shown(Recording(FakeEngine(status = Status.Stopped)))
        val chart = model.ui.value.chart
        assertNotNull(chart)
        assertTrue("only what the engine sent", chart!!.down.all { it == 0f } && chart.up.all { it == 0f })
        assertNull(model.ui.value.session)
    }

    private companion object {
        const val NOW = 1_795_343_040_000L
    }
}
