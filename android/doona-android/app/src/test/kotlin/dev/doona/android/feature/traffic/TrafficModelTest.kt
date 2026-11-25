package dev.doona.android.feature.traffic

import dev.doona.android.engine.Status
import dev.doona.android.engine.Traffic
import dev.doona.android.engine.TrafficSeries
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** 流量詳情's pure mapping: the core's series to the page's 60-point charts, the live minute and the session's figures. */
class TrafficModelTest {
    /** A series of [points] points [stepSeconds] apart, each way rising from [from], as the core sends it. */
    private fun series(points: Int, stepSeconds: Long, from: Long = 0, upFrom: Long = 0) = TrafficSeries(
        stepSeconds = stepSeconds,
        endEpochMs = END,
        up = List(points) { upFrom + it },
        down = List(points) { from + it * 10L },
        upPeak = 0,
        downPeak = 0,
    )

    @Test
    fun theLiveMinuteKeepsEachOfItsSixtySeconds() {
        val chart = trafficChart(RV_TRAFFIC_LIVE, series(60, 1))
        assertEquals(List(60) { it * 10f }, chart.down)
        assertEquals(List(60) { it.toFloat() }, chart.up)
        assertEquals(1_000L, chart.stepMs)
        assertEquals(END, chart.endEpochMs)
    }

    @Test
    fun eachWindowIsSixtyPointsThatKeepItsMean() {
        // The core's answer for 300, 900 and 3600 seconds: 300 points of 1, 3 and 12 seconds.
        for ((range, step) in listOf(1 to 1L, 2 to 3L, 3 to 12L)) {
            val read = series(300, step)
            val chart = trafficChart(range, read)
            assertEquals("range $range", RV_TRAFFIC_POINTS, chart.down.size)
            assertEquals("range $range: a point spans five of the core's", step * 5 * 1_000, chart.stepMs)
            assertEquals("its first point averages the first five", (0 + 10 + 20 + 30 + 40) / 5f, chart.down.first())
            assertEquals("the mean is the window's", read.down.average(), chart.down.average(), 1e-3)
            assertEquals(RV_TRAFFIC_RANGE_SECONDS[range].toLong() * 1_000, chart.stepMs * chart.down.size)
        }
    }

    @Test
    fun downloadAndUploadEachKeepTheirOwnValues() {
        // Upload far below download: its chart is its own, so its page's axis is its own peak, not download's.
        val chart = trafficChart(1, series(300, 1, from = 1_000_000, upFrom = 5))
        assertEquals((5 + 9) / 2f, chart.up.first())
        assertEquals(chart.up, chart.values(upload = true))
        assertEquals(chart.down, chart.values(upload = false))
        assertTrue(chart.up.max() < chart.down.min())
    }

    @Test
    fun anEmptySeriesIsAnEmptyChart() {
        val chart = trafficChart(2, series(0, 3))
        assertTrue(chart.down.isEmpty() && chart.up.isEmpty())
    }

    @Test
    fun theLiveMinuteIsTheEnginesHistoryOnceItHoldsAMinute() {
        val seed = trafficChart(RV_TRAFFIC_LIVE, series(60, 1, from = 7))
        val traffic = Traffic(downHistory = List(60) { 100L + it }, upHistory = List(60) { 200L + it })
        val chart = liveMinute(seed, historyAtRead = 60, traffic = traffic, nowEpochMs = NOW)!!
        assertEquals(List(60) { 100f + it }, chart.down)
        assertEquals(List(60) { 200f + it }, chart.up)
        assertEquals("its last point ends now", NOW, chart.endEpochMs)
        assertEquals(1_000L, chart.stepMs)
    }

    @Test
    fun aShortHistoryIsLedByTheReadsOlderSeconds() {
        val seed = trafficChart(RV_TRAFFIC_LIVE, series(60, 1))
        // The read saw 3 seconds of history; 2 more came since.
        val traffic = Traffic(downHistory = List(5) { 1_000L + it }, upHistory = List(5) { 2_000L + it })
        val chart = liveMinute(seed, historyAtRead = 3, traffic = traffic, nowEpochMs = NOW)!!
        assertEquals(RV_TRAFFIC_POINTS, chart.down.size)
        // The read's first 57 seconds came before the history; the minute is the last 55 of them, then the 5 sampled.
        assertEquals(List(55) { (it + 2) * 10f } + List(5) { 1_000f + it }, chart.down)
        assertEquals(List(55) { it + 2f } + List(5) { 2_000f + it }, chart.up)
    }

    @Test
    fun withNoHistoryTheMinuteIsTheRead() {
        val seed = trafficChart(RV_TRAFFIC_LIVE, series(60, 1))
        assertSame(seed, liveMinute(seed, 0, Traffic(), NOW))
        assertNull("neither read nor history: nothing to draw", liveMinute(null, 0, Traffic(), NOW))
    }

    @Test
    fun aSessionThatHasJustStartedHasNoInfiniteAverage() {
        val session = trafficSession(Status.Running(NOW), Traffic(downTotal = 5_000), NOW)!!
        assertEquals(0L, session.seconds)
        assertEquals(5_000.0, session.averageDown, 0.0)
    }

    @Test
    fun withNoTunnelThereIsNoSession() {
        for (status in listOf(Status.Stopped, Status.Starting, Status.Stopping)) assertNull("$status", trafficSession(status, Traffic(downTotal = 1), NOW))
    }

    @Test
    fun aFailedReadKeepsTheLastChart() {
        val last = trafficChart(RV_TRAFFIC_LIVE, series(60, 1))
        assertSame("a window that failed", last, trafficState(SeriesRead(3, null, 0), Status.Stopped, Traffic(), last, NOW).chart)
        assertSame("the minute, with no history either", last, trafficState(SeriesRead(RV_TRAFFIC_LIVE, null, 0), Status.Stopped, Traffic(), last, NOW).chart)
        assertNull("before any read nothing is drawn", trafficState(SeriesRead(3, null, 0), Status.Stopped, Traffic(), null, NOW).chart)
    }

    private companion object {
        const val END = 1_795_343_039_000L
        const val NOW = 1_795_343_040_000L
    }
}
