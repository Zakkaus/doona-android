package dev.doona.android.prototype.rv.data

import dev.doona.android.feature.model.RvSession
import dev.doona.android.feature.model.fastest
import dev.doona.android.feature.traffic.RV_TRAFFIC_POINTS
import dev.doona.android.feature.traffic.RV_TRAFFIC_RANGE_SECONDS
import kotlin.math.exp
import kotlin.math.min
import kotlin.random.Random

/**
 * [session]'s traffic second by second from the start of the hour before now's (09:00:01) to now (10:24:00), each way,
 * derived from the session's own figures so that every range agrees with them. The last minute is the session's
 * ([RvSession.minuteDown], [RvSession.minuteUp]). The seconds before it are drawn from a fixed seed in that minute's
 * manner (an idle floor that wanders, a page load in each minute, a download of 10 to 30 s in each five, upload's
 * requests and ACKs riding on them) and scaled so that hour 9's seconds, and hour 10's with the last minute, add up to
 * [RvSession.downByHour] and [RvSession.upByHour]. The session's fastest second each way ([RvSession.peakDown],
 * [RvSession.peakUp]) falls in hour 9, inside the last hour and before the last minute; no other second comes near it.
 */
internal class RvSessionSeconds(session: RvSession) {
    val down: List<Float>
    val up: List<Float>

    /** Each range's chart, by [RV_TRAFFIC_RANGE_SECONDS]: download's, then upload's. */
    private val downCharts: List<List<Float>>
    private val upCharts: List<List<Float>>

    init {
        val hour = session.downByHour.lastIndex
        val minute = session.minuteDown.size
        // Hour 10 so far, less its last minute, and before it the whole of hour 9.
        val sinceHour = ((session.nowEpochMs % MS_PER_HOUR) / MS_PER_SECOND).toInt()
        val draw = RvTrafficDraw(Random(SEED))
        val (earlierDown, earlierUp) = draw.seconds(SECONDS_PER_HOUR)
        val (laterDown, laterUp) = draw.seconds(sinceHour - minute)
        down = fit(earlierDown, session.downByHour[hour - 1].toDouble(), session.peakDown, PEAK_DOWN_AT) +
            fit(laterDown, session.downByHour[hour] - session.minuteDown.sumOf { it.toDouble() }) + session.minuteDown
        up = fit(earlierUp, session.upByHour[hour - 1].toDouble(), session.peakUp, PEAK_UP_AT) +
            fit(laterUp, session.upByHour[hour] - session.minuteUp.sumOf { it.toDouble() }) + session.minuteUp
        downCharts = RV_TRAFFIC_RANGE_SECONDS.map { chart(down, it) }
        upCharts = RV_TRAFFIC_RANGE_SECONDS.map { chart(up, it) }
    }

    /** The chart of [range] (an index of [RV_TRAFFIC_RANGE_SECONDS]), download's or [upload]'s. */
    fun chart(range: Int, upload: Boolean): List<Float> = (if (upload) upCharts else downCharts)[range]

    private companion object {
        const val SEED = 20_261_123
        const val SECONDS_PER_HOUR = 3_600
        const val MS_PER_SECOND = 1_000L
        const val MS_PER_HOUR = 3_600_000L

        /** The fastest seconds: download's at 09:47:13, upload's at 09:32:36 (seconds after 09:00:01). */
        const val PEAK_DOWN_AT = 2_832
        const val PEAK_UP_AT = 1_955
    }
}

/**
 * [raw] scaled so its seconds add up to [total] bytes; with a [peak], the second at [at] is that peak and the others
 * share the rest.
 */
private fun fit(raw: FloatArray, total: Double, peak: Float? = null, at: Int = -1): List<Float> {
    val rest = total - (peak?.toDouble() ?: 0.0)
    val others = raw.indices.sumOf { if (it == at) 0.0 else raw[it].toDouble() }
    return raw.indices.map { if (it == at && peak != null) peak else (raw[it] * rest / others).toFloat() }
}

/** [seconds]' last [span] seconds averaged into [RV_TRAFFIC_POINTS] points of equal length, so the chart's mean is the window's. */
private fun chart(seconds: List<Float>, span: Int): List<Float> {
    val window = seconds.subList(seconds.size - span, seconds.size)
    val per = span / RV_TRAFFIC_POINTS
    return List(RV_TRAFFIC_POINTS) { point -> window.subList(point * per, (point + 1) * per).average().toFloat() }
}

/** [RvSessionSeconds]' seconds before scaling, download's and upload's, each term non-negative. */
private class RvTrafficDraw(private val random: Random) {
    fun seconds(count: Int): Pair<FloatArray, FloatArray> {
        val down = FloatArray(count)
        val up = FloatArray(count)
        idle(down, IDLE_LOW, IDLE_SPREAD)
        idle(up, UP_IDLE_LOW, UP_IDLE_SPREAD)
        for (slot in 0 until count step LOAD_EVERY) pageLoad(down, up, slot + random.nextInt(LOAD_EVERY))
        for (slot in 0 until count step DOWNLOAD_EVERY) download(down, up, slot + random.nextInt(DOWNLOAD_EVERY - DOWNLOAD_LONGEST))
        for (t in 0 until count) down[t] = min(down[t], CAP)
        return down to up
    }

    /** A floor from [low] to [low] + [spread] that leans on the second before, so it wanders instead of flickering. */
    private fun idle(into: FloatArray, low: Float, spread: Float) {
        var floor = random.nextFloat()
        for (t in into.indices) {
            floor = MEMORY * floor + (1 - MEMORY) * random.nextFloat()
            into[t] = low + spread * floor
        }
    }

    /** A page load at [start]: a peak that decays to a tenth over its span, and upload's request as it begins. */
    private fun pageLoad(down: FloatArray, up: FloatArray, start: Int) {
        if (start >= down.size) return
        val peak = LOAD_LOW + LOAD_SPREAD * random.nextFloat()
        val span = SPAN_LOW + SPAN_SPREAD * random.nextFloat()
        for (t in start until min(down.size, start + LOAD_TAIL)) down[t] += peak * exp(-(t - start) * DECAY_SPAN / span)
        up[start] += REQUEST_LOW + REQUEST_SPREAD * random.nextFloat()
    }

    /** A download at [start]: a level held, jittering from second to second, with upload's ACKs at a few % of it. */
    private fun download(down: FloatArray, up: FloatArray, start: Int) {
        val level = LEVEL_LOW + LEVEL_SPREAD * random.nextFloat()
        val length = DOWNLOAD_SHORTEST + random.nextInt(DOWNLOAD_LONGEST - DOWNLOAD_SHORTEST)
        for (t in start until min(down.size, start + length)) {
            val rate = level * (1 + JITTER * (2 * random.nextFloat() - 1))
            down[t] += rate
            up[t] += rate * (ACK_LOW + ACK_SPREAD * random.nextFloat())
        }
    }

    private companion object {
        const val MEMORY = 0.5f
        const val IDLE_LOW = 20_000f
        const val IDLE_SPREAD = 60_000f
        const val UP_IDLE_LOW = 3_000f
        const val UP_IDLE_SPREAD = 9_000f
        const val LOAD_EVERY = 60
        const val LOAD_LOW = 1_500_000f
        const val LOAD_SPREAD = 2_000_000f
        const val SPAN_LOW = 3f
        const val SPAN_SPREAD = 2f
        const val LOAD_TAIL = 8
        const val REQUEST_LOW = 120_000f
        const val REQUEST_SPREAD = 100_000f
        const val DOWNLOAD_EVERY = 300
        const val DOWNLOAD_SHORTEST = 10
        const val DOWNLOAD_LONGEST = 30
        const val LEVEL_LOW = 1_500_000f
        const val LEVEL_SPREAD = 2_000_000f
        const val JITTER = 0.15f
        const val ACK_LOW = 0.03f
        const val ACK_SPREAD = 0.02f

        /** Overlapping loads and downloads never pass this, so no scaled second comes near the session's peak. */
        const val CAP = 3_600_000f

        /** e^-2.3 ≈ 0.1: a load is under a tenth of its peak after its span. */
        const val DECAY_SPAN = 2.3f
    }
}
