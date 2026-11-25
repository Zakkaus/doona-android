package dev.doona.android.feature.model

import androidx.compose.runtime.Immutable

/**
 * One day by the hour from midnight, oldest first; the last hour is the current one, still filling. The lists are
 * copied on construction, so a caller's mutable list cannot change the day afterwards, and two days with the same
 * hours are equal.
 */
@ConsistentCopyVisibility
@Immutable
internal data class RvDay private constructor(val down: List<Long>, val up: List<Long>, val connections: List<Int>?) {
    val downTotal = down.sum()
    val upTotal = up.sum()
    val total = downTotal + upTotal

    /** Both directions together, hour by hour. */
    val hours: List<Long> = down.zip(up) { d, u -> d + u }

    val quiet = total == 0L

    companion object {
        operator fun invoke(down: List<Long>, up: List<Long>, connections: List<Int>?) = RvDay(down.toList(), up.toList(), connections?.toList())
    }
}

/**
 * The connection running now: when it started, its bytes each way in each hour of the day it spans (zero before it
 * started), its fastest second each way, and its last minute ([RvMinute]). The lists are copied on
 * construction, so a caller's mutable list cannot change the session afterwards.
 */
@ConsistentCopyVisibility
@Immutable
internal data class RvSession private constructor(
    val startEpochMs: Long,
    val nowEpochMs: Long,
    val downByHour: List<Long>,
    val upByHour: List<Long>,
    val peakDown: Float,
    val peakUp: Float,
    val minuteDown: List<Float>,
    val minuteUp: List<Float>,
) {
    val seconds = (nowEpochMs - startEpochMs) / MS_PER_SECOND

    /** The averages divide by at least one second, so a session that has just started has no infinite rate. */
    private val perSecond = seconds.coerceAtLeast(1L).toDouble()
    val downTotal = downByHour.sum()
    val upTotal = upByHour.sum()
    val averageDown = downTotal / perSecond
    val averageUp = upTotal / perSecond

    companion object {
        operator fun invoke(
            startEpochMs: Long,
            nowEpochMs: Long,
            downByHour: List<Long>,
            upByHour: List<Long>,
            peakDown: Float,
            peakUp: Float,
            minuteDown: List<Float>,
            minuteUp: List<Float>,
        ) = RvSession(startEpochMs, nowEpochMs, downByHour.toList(), upByHour.toList(), peakDown, peakUp, minuteDown.toList(), minuteUp.toList())
    }
}

/** Today's bytes that left through a proxy and directly, for the 分流比例 card; together they are the day's total. */
@Immutable
internal data class RvSplit(val proxyBytes: Long, val directBytes: Long) {
    val proxyShare = if (proxyBytes + directBytes == 0L) 0f else proxyBytes.toFloat() / (proxyBytes + directBytes)
}

private const val MS_PER_SECOND = 1_000L
