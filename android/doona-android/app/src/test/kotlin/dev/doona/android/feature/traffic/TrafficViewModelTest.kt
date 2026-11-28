package dev.doona.android.feature.traffic

import dev.doona.android.honk.HonkExamples
import dev.doona.android.honk.HonkFake
import dev.doona.android.honk.NativeRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.time.Instant

/** [TrafficViewModel.ui]: 流量詳情 from honk's `/runtime` and `/runtime/traffic/history`, read once a second. */
@OptIn(ExperimentalCoroutinesApi::class)
class TrafficViewModelTest {
    private val honk = HonkFake()

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.shown(): TrafficViewModel {
        val model = TrafficViewModel(NativeRuntime(honk), { NOW }, io = StandardTestDispatcher(testScheduler))
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.ui.collect {} }
        runCurrent()
        return model
    }

    @Test
    fun thePageShowsHonksRatesSessionAndSamples() = runTest {
        honk.get(HonkExamples.RUNTIME_PATH, HonkExamples.RUNTIME)
        honk.get(HonkExamples.HISTORY_PATH, HonkExamples.HISTORY)
        val state = shown().ui.value
        assertEquals(32_768L, state.downRate)
        assertEquals(4_096L, state.upRate)
        assertEquals(listOf(16_384f, 0f, 32_768f), state.chart!!.down)
        assertEquals(Instant.parse("2026-08-15T10:00:00Z").toEpochMilli(), state.chart!!.endEpochMs)
        val session = state.session!!
        assertEquals(987_654_321L, session.downTotal)
        assertEquals("since counter_since", (NOW - Instant.parse("2026-08-15T08:00:00Z").toEpochMilli()) / 1_000, session.seconds)
        assertEquals(32_768.0, session.peakDown, 0.0)
    }

    @Test
    fun itReadsAgainEachSecondAndNotAfterThePageLeaves() = runTest {
        honk.get(HonkExamples.RUNTIME_PATH, HonkExamples.RUNTIME)
        honk.get(HonkExamples.HISTORY_PATH, HonkExamples.HISTORY)
        shown()
        val first = honk.calls.size
        advanceTimeBy(TrafficViewModel.TICK_MS)
        runCurrent()
        assertEquals("one more load", first * 2, honk.calls.size)
    }

    @Test
    fun theRangeCutsTheChartFromTheLastLoadWithoutAnotherRead() = runTest {
        honk.get(HonkExamples.RUNTIME_PATH, HonkExamples.RUNTIME)
        honk.get(HonkExamples.HISTORY_PATH, HonkExamples.HISTORY)
        val model = shown()
        val reads = honk.calls.size
        model.onRange(2)
        assertEquals(2, model.ui.value.chart?.range)
        assertEquals(reads, honk.calls.size)
    }

    @Test
    fun aFailedSampleReadKeepsTheLastChart() = runTest {
        honk.get(HonkExamples.RUNTIME_PATH, HonkExamples.RUNTIME)
        honk.get(HonkExamples.HISTORY_PATH, HonkExamples.HISTORY)
        honk.on("GET", HonkExamples.HISTORY_PATH, 503, HonkExamples.UNAVAILABLE)
        val model = shown()
        val chart = model.ui.value.chart
        advanceTimeBy(TrafficViewModel.TICK_MS)
        runCurrent()
        assertEquals(chart, model.ui.value.chart)
    }

    @Test
    fun withHonkUnreachableThePageHasNoFigures() = runTest {
        honk.on("GET", HonkExamples.RUNTIME_PATH, 503, HonkExamples.UNAVAILABLE)
        honk.on("GET", HonkExamples.HISTORY_PATH, 503, HonkExamples.UNAVAILABLE)
        val state = shown().ui.value
        assertNull(state.chart)
        assertNull(state.session)
        assertEquals(0L, state.downRate)
    }

    private companion object {
        val NOW = Instant.parse("2026-08-15T10:00:05Z").toEpochMilli()
    }
}
