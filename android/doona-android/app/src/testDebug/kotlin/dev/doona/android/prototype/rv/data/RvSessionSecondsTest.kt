package dev.doona.android.prototype.rv.data

import dev.doona.android.feature.model.fastest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 流量詳情's ranges agree with [RvData.session] (brief rv-gestures G5), on plain JVM: hour 9's seconds and hour 10's add
 * up to the session's bytes in those hours, the fastest second each way is the session's peak and falls in the last hour
 * before the last minute, the last minute is the session's, and every range's chart has the mean of its window and no
 * point above the peak.
 */
class RvSessionSecondsTest {
    private val session = RvData.session
    private val seconds = RvSessionSeconds(session)

    @Test
    fun eachHourAddsUpToTheSessionsBytes() {
        for ((what, all, byHour) in listOf(Triple("down", seconds.down, session.downByHour), Triple("up", seconds.up, session.upByHour))) {
            val hour = byHour.lastIndex
            assertEquals("$what covers hour 9 and hour 10 so far", HOUR + SO_FAR, all.size)
            assertBytes("$what in hour 9", byHour[hour - 1], all.subList(0, HOUR))
            assertBytes("$what in hour 10", byHour[hour], all.subList(HOUR, all.size))
            assertTrue("$what never runs backwards", all.all { it >= 0f })
        }
    }

    @Test
    fun theFastestSecondIsTheSessionsInTheLastHour() {
        for ((what, all, peak) in listOf(Triple("down", seconds.down, session.peakDown), Triple("up", seconds.up, session.peakUp))) {
            val at = all.indices.maxBy { all[it] }
            assertEquals("$what's fastest second", peak, all[at])
            assertTrue("$what's peak at $at is inside the last hour, before the last minute", at >= all.size - HOUR && at < all.size - MINUTE)
            assertTrue("$what has one peak", all.count { it >= peak * NEAR_PEAK } == 1)
        }
    }

    @Test
    fun theLastMinuteIsTheSessions() {
        assertEquals(session.minuteDown, seconds.down.takeLast(MINUTE))
        assertEquals(session.minuteUp, seconds.up.takeLast(MINUTE))
        assertEquals("1 分 is the minute itself", session.minuteDown, seconds.chart(0, upload = false))
    }

    @Test
    fun everyRangeHasItsWindowsMeanAndNoPointAboveThePeak() {
        for (upload in listOf(false, true)) {
            val all = if (upload) seconds.up else seconds.down
            RV_RANGE_SECONDS.forEachIndexed { range, span ->
                val chart = seconds.chart(range, upload)
                val what = "${if (upload) "up" else "down"} over $span s"
                assertEquals("$what has $RV_RANGE_POINTS points", RV_RANGE_POINTS, chart.size)
                val window = all.takeLast(span).sumOf { it.toDouble() } / span
                assertEquals("$what keeps its window's mean", 1.0, chart.sumOf { it.toDouble() } / chart.size / window, RELATIVE)
                assertTrue("$what stays under the peak", chart.max() <= if (upload) session.peakUp else session.peakDown)
            }
        }
    }

    private fun assertBytes(what: String, bytes: Long, seconds: List<Float>) = assertEquals(what, 1.0, seconds.sumOf { it.toDouble() } / bytes, RELATIVE)

    private companion object {
        const val HOUR = 3_600

        /** 10:00:01 to 10:24:00. */
        const val SO_FAR = 1_440
        const val MINUTE = 60
        const val RELATIVE = 1e-5
        const val NEAR_PEAK = 0.95f
    }
}
