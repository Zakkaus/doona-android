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
 * the same minute. Download idles on a noisy 20 to 80 kB/s floor; three page loads rise to 3 to 6 MB/s within a second
 * and decay over 3 to 5 (the last one is still decaying now), two of them with a smaller second bump; and one download
 * holds about 3.5 MB/s for eleven seconds, jittering 15 % from second to second, with a 2 s ramp up and a 3 s ramp down.
 * Upload has its own events and never follows download's curve: a short request burst as each download starts, an
 * 8 s upload of 600 to 900 kB/s while download is quiet, and an ACK-like trickle of 3 to 5 % under the sustained
 * download, each with its own noise from a second seed, over a 3 to 12 kB/s floor. Every term is non-negative.
 */
internal object RvTrafficSample {
    // Declared before init, which reads them.
    private val PAGE_LOADS = listOf(
        Burst(start = 7, peak = 4_200_000f, seconds = 4f, echo = 0.35f),
        Burst(start = 42, peak = 5_600_000f, seconds = 5f, echo = 0.3f),
        // Still loading at the window's end, so the live point sits clear of the low's pill at the bottom right.
        Burst(start = 58, peak = 3_400_000f, seconds = 4f),
    )

    /** The sustained download's share of its level, second by second from its start: a 2 s ramp, the hold, a 3 s ramp. */
    private val PLATEAU_SHAPE = listOf(0.4f, 0.75f) + List(11) { 1f } + listOf(0.7f, 0.4f, 0.15f)

    val down: List<Float>
    val up: List<Float>

    init {
        val random = Random(SEED)
        var floor = random.nextFloat()
        val sustained = FloatArray(SECONDS)
        down = List(SECONDS) { t ->
            // Each second's floor leans on the last, so the idle line wanders instead of flickering.
            floor = FLOOR_MEMORY * floor + (1 - FLOOR_MEMORY) * random.nextFloat()
            sustained[t] = plateau(t, random)
            IDLE_LOW + IDLE_SPREAD * floor + PAGE_LOADS.sumOf(t) + sustained[t]
        }
        up = RvUpload(Random(SEED + 1), PAGE_LOADS.map { it.start } + PLATEAU_START, sustained).seconds(SECONDS)
    }

    /** The sustained download at second [t]: its shape times [PLATEAU], each second jittered by up to [JITTER]. */
    private fun plateau(t: Int, random: Random): Float {
        val share = PLATEAU_SHAPE.getOrNull(t - PLATEAU_START) ?: return 0f
        return PLATEAU * share * (1 + JITTER * (2 * random.nextFloat() - 1))
    }

    private fun List<Burst>.sumOf(t: Int): Float = fold(0f) { sum, burst -> sum + burst.at(t) }

    private const val SEED = 20_261_122
    private const val SECONDS = 60
    private const val IDLE_LOW = 20_000f
    private const val IDLE_SPREAD = 60_000f
    private const val FLOOR_MEMORY = 0.5f
    private const val PLATEAU = 3_500_000f
    private const val PLATEAU_START = 19
    private const val JITTER = 0.15f
}

/**
 * A page load at second [start]: [peak] bytes a second within that second, decaying to under a tenth of it [seconds]
 * later. With an [echo], a second bump of that share of [peak] rises two seconds in and decays the same way.
 */
private class Burst(val start: Int, val peak: Float, val seconds: Float, val echo: Float = 0f) {
    fun at(t: Int): Float = decay(t - start) + echo * decay(t - start - ECHO_DELAY)

    private fun decay(elapsed: Int): Float = if (elapsed < 0) 0f else peak * exp(-elapsed * DECAY_SPAN / seconds)
}

/**
 * Upload's own minute: a request burst of 120 to 220 kB/s at each second in [starts] that a download begins, a third of
 * it the second after; an 8 s upload of 600 to 900 kB/s from second [UPLOAD_START], when download is quiet; and ACKs
 * at 3 to 5 % of the [sustained] download, drawn second by second. The noise is [random]'s alone.
 */
private class RvUpload(private val random: Random, private val starts: List<Int>, private val sustained: FloatArray) {
    private val requests = starts.associateWith { REQUEST_LOW + REQUEST_SPREAD * random.nextFloat() }
    private var floor = random.nextFloat()
    private var level = random.nextFloat()

    fun seconds(count: Int): List<Float> = List(count) { t ->
        floor = MEMORY * floor + (1 - MEMORY) * random.nextFloat()
        UP_IDLE_LOW + UP_IDLE_SPREAD * floor + request(t) + upload(t) + sustained[t] * (ACK_LOW + ACK_SPREAD * random.nextFloat())
    }

    private fun request(t: Int): Float = (requests[t] ?: 0f) + REQUEST_TAIL * (requests[t - 1] ?: 0f)

    private fun upload(t: Int): Float {
        val share = UPLOAD_SHAPE.getOrNull(t - UPLOAD_START) ?: return 0f
        level = MEMORY * level + (1 - MEMORY) * random.nextFloat()
        return share * (UPLOAD_LOW + UPLOAD_SPREAD * level)
    }

    private companion object {
        const val MEMORY = 0.6f
        const val UP_IDLE_LOW = 3_000f
        const val UP_IDLE_SPREAD = 9_000f
        const val REQUEST_LOW = 120_000f
        const val REQUEST_SPREAD = 100_000f
        const val REQUEST_TAIL = 0.33f
        const val UPLOAD_START = 49
        const val UPLOAD_LOW = 600_000f
        const val UPLOAD_SPREAD = 300_000f
        const val ACK_LOW = 0.03f
        const val ACK_SPREAD = 0.02f

        /** A second's ramp up, six seconds' hold and a second's ramp down. */
        val UPLOAD_SHAPE = listOf(0.5f, 1f, 1f, 1f, 1f, 1f, 1f, 0.45f)
    }
}

/** e^-2.3 ≈ 0.1: a burst is under a tenth of its peak after its [Burst.seconds]. */
private const val DECAY_SPAN = 2.3f
private const val ECHO_DELAY = 2
