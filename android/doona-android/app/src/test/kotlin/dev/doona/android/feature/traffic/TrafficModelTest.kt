package dev.doona.android.feature.traffic

import dev.doona.android.honk.RuntimeTraffic
import dev.doona.android.honk.TrafficHistory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/** [trafficChart], [trafficSession] and [trafficState]: 流量詳情 from honk's traffic and its samples. */
class TrafficModelTest {
    /** [count] one-second samples ending at [END], down `from, from+1, …`, up ten times that. */
    private fun history(count: Int, from: Long = 0) = TrafficHistory(
        endEpochMs = END,
        stepMs = 1_000,
        up = List(count) { (from + it) * 10 },
        down = List(count) { from + it },
    )

    private val traffic = RuntimeTraffic(sinceEpochMs = END - 100_000, upRate = 7, downRate = 42, upTotal = 1_000, downTotal = 5_000, connections = 3)

    @Test
    fun theLiveMinuteIsTheLastSixtySamples() {
        val chart = trafficChart(RV_TRAFFIC_LIVE, history(600))!!
        assertEquals((540..599).map(Int::toFloat), chart.down)
        assertEquals(END, chart.endEpochMs)
        assertEquals(1_000L, chart.stepMs)
    }

    @Test
    fun fiveMinutesAveragesFiveSamplesAPointAndKeepsTheMean() {
        val chart = trafficChart(1, history(600))!!
        assertEquals(RV_TRAFFIC_POINTS, chart.down.size)
        assertEquals("the first point is samples 300..304", 302f, chart.down.first())
        assertEquals(5_000L, chart.stepMs)
        assertEquals((300..599).average(), chart.down.average(), 1e-3)
    }

    @Test
    fun aRangeLongerThanHonkKeepsShowsTheSixHundredSecondsItKeeps() {
        val chart = trafficChart(3, history(600))!!
        assertEquals(RV_TRAFFIC_POINTS, chart.down.size)
        assertEquals(10_000L, chart.stepMs)
        assertEquals(4.5f, chart.down.first())
    }

    @Test
    fun downloadAndUploadEachKeepTheirOwnValues() {
        val chart = trafficChart(RV_TRAFFIC_LIVE, history(60))!!
        assertEquals(chart.down.map { it * 10 }, chart.up)
        assertEquals(chart.up, chart.values(upload = true))
    }

    @Test
    fun aSampleHonkCouldNotRateIsDrawnAsZero() {
        val chart = trafficChart(RV_TRAFFIC_LIVE, TrafficHistory(END, 1_000, listOf(2, null, 4), listOf(16, null, 32)))!!
        assertEquals(listOf(16f, 0f, 32f), chart.down)
    }

    @Test
    fun noSampleIsNoChart() {
        assertNull(trafficChart(RV_TRAFFIC_LIVE, TrafficHistory(null, 1_000, emptyList(), emptyList())))
    }

    @Test
    fun theSessionRunsFromHonksCountersWithItsFastestKeptSample() {
        val session = trafficSession(traffic, history(600), END)!!
        assertEquals(100L, session.seconds)
        assertEquals(50.0, session.averageDown, 0.0)
        assertEquals(599.0, session.peakDown, 0.0)
        assertEquals(5_990.0, session.peakUp, 0.0)
        assertEquals(5_000L, session.downTotal)
    }

    @Test
    fun aSessionThatHasJustStartedHasNoInfiniteAverage() {
        val session = trafficSession(traffic.copy(sinceEpochMs = END), null, END)!!
        assertEquals(0L, session.seconds)
        assertEquals(5_000.0, session.averageDown, 0.0)
    }

    @Test
    fun beforeHonkCountsThereIsNoSession() {
        assertNull(trafficSession(traffic.copy(sinceEpochMs = null), null, END))
        assertNull(trafficState(RV_TRAFFIC_LIVE, TrafficRead(null, null), null, END).session)
    }

    @Test
    fun aFailedReadKeepsTheLastChart() {
        val previous = trafficChart(RV_TRAFFIC_LIVE, history(60))
        val state = trafficState(RV_TRAFFIC_LIVE, TrafficRead(traffic, null), previous, END)
        assertSame(previous, state.chart)
        assertEquals(42L, state.downRate)
        assertEquals(7L, state.upRate)
    }

    private companion object {
        const val END = 1_800_000_000_000L
    }
}
