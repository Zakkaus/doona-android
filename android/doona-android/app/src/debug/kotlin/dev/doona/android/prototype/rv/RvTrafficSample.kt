package dev.doona.android.prototype.rv

import dev.doona.android.engine.Status
import dev.doona.android.feature.activity.ActivityUiState
import dev.doona.android.feature.activity.sampleActivityState
import kotlin.math.exp
import kotlin.random.Random

/** What the traffic page's card says about the connection as a whole: bytes each way and how long it has run. */
internal class RvConnection(val downTotal: Long, val upTotal: Long, val seconds: Long)

/** The sample's connection: invented totals for an hour and three quarters of use. */
internal val RV_SAMPLE_CONNECTION = RvConnection(downTotal = 1_840_000_000, upTotal = 126_000_000, seconds = 6_135)

/** The second the scrubbed golden holds a finger on: inside the sustained download. */
internal const val RV_SAMPLE_SCRUB = 30

/** The connected sample with [RvTrafficSample]'s minute; the last second is the current rate. */
internal fun rvSampleActivity(): ActivityUiState {
    val down = RvTrafficSample.down
    val up = RvTrafficSample.up
    return sampleActivityState().copy(downRate = down.last().toLong(), upRate = up.last().toLong(), downHistory = down, upHistory = up)
}

/** The same screen disconnected: no rates and no history. */
internal fun rvIdleActivity(): ActivityUiState =
    sampleActivityState().copy(status = Status.Stopped, downRate = 0, upRate = 0, downHistory = emptyList(), upHistory = emptyList())

/**
 * An invented minute of traffic, one sample a second, the last one now, drawn from a fixed seed so every render shows
 * the same minute. Download idles at tens of kB/s between three page loads that spike to 3 to 6 MB/s and decay over 2
 * to 4 seconds, and a ten-second download held near 3.5 MB/s. Upload runs at 5 to 10 % of download, with small spikes
 * of its own. Every value is a sum of non-negative terms, so none is below zero.
 */
internal object RvTrafficSample {
    // Declared before init, which reads them.
    private val PAGE_LOADS = listOf(Burst(8, 4_400_000f, 3f), Burst(46, 5_800_000f, 4f), Burst(58, 3_100_000f, 3f))
    private val UP_SPIKES = listOf(Burst(3, 160_000f, 2f), Burst(40, 420_000f, 3f), Burst(52, 240_000f, 2f))

    val down: List<Float>
    val up: List<Float>

    init {
        val random = Random(SEED)
        val downs = List(SECONDS) { t -> IDLE_DOWN + random.nextFloat() * IDLE_DOWN_SPREAD + PAGE_LOADS.sumOf(t) + plateau(t, random) }
        down = downs
        up = List(SECONDS) { t -> downs[t] * (UP_SHARE + random.nextFloat() * UP_SHARE) + IDLE_UP * (1 + random.nextFloat()) + UP_SPIKES.sumOf(t) }
    }

    /** The sustained download: half speed in its first second, [PLATEAU] wandering by 5 % while it lasts, then a fast fall. */
    private fun plateau(t: Int, random: Random): Float = when {
        t < PLATEAU_START -> 0f
        t == PLATEAU_START -> PLATEAU / 2
        t <= PLATEAU_END -> PLATEAU * (1 + PLATEAU_WANDER * (2 * random.nextFloat() - 1))
        else -> PLATEAU * exp(-(t - PLATEAU_END) / PLATEAU_FALL)
    }

    private fun List<Burst>.sumOf(t: Int): Float = fold(0f) { sum, burst -> sum + burst.at(t) }

    private const val SEED = 20_261_122
    private const val SECONDS = 60
    private const val IDLE_DOWN = 18_000f
    private const val IDLE_DOWN_SPREAD = 52_000f
    private const val IDLE_UP = 5_000f
    private const val UP_SHARE = 0.05f
    private const val PLATEAU = 3_500_000f
    private const val PLATEAU_START = 24
    private const val PLATEAU_END = 34
    private const val PLATEAU_WANDER = 0.05f
    private const val PLATEAU_FALL = 1.2f
}

/** A spike at second [start] of [peak] bytes a second that has mostly died down [seconds] later. */
private class Burst(val start: Int, val peak: Float, val seconds: Float) {
    fun at(t: Int): Float = if (t < start) 0f else peak * exp(-(t - start) / (seconds / DECAY_SPAN))
}

/** A burst falls to under a tenth of its peak in its [Burst.seconds]. */
private const val DECAY_SPAN = 2.5f
