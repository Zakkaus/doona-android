package dev.doona.android.designsystem.rv

import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameMillis
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.abs
import kotlin.math.exp

/**
 * A live rate as it is shown: it glides to each new value, with no swap and no overlap. The value it heads for is an
 * exponential moving average of [rate] (time constant [RvMotion.GLIDE_TAU_MS], taken each second until it reaches
 * [rate]), so one busy second does not throw the number; the number itself tweens to each new average
 * in [RvMotion.GLIDE_MS], decelerating, and is written on every frame as one text. Its unit holds near a step
 * ([RvFormat.rateUnit]). A new [key] (another direction) starts again at [rate], without gliding across.
 */
@Composable
internal fun rvGlidingRate(rate: Double, key: Any? = null): RvAmount {
    val format = rememberRvFormat()
    val motion = Rv.motion
    val glide = remember(format, key) { RvRateGlide(format, rate) }
    val latest by rememberUpdatedState(rate)
    LaunchedEffect(glide, motion) { glide.follow({ latest }, motion) }
    return glide.shown
}

/** The state of a [rvGlidingRate]: the number on screen, the average it heads for, and the unit it is written in. */
private class RvRateGlide(private val format: RvFormat, start: Double) {
    private val number = Animatable(start.toFloat())
    private var average = start
    private var at = NONE
    private var unit = format.rateUnit(start, held = -1)
    var shown by mutableStateOf(format.rate(start, unit))
        private set

    suspend fun follow(rate: () -> Double, motion: RvMotionScale) {
        snapshotFlow(rate).collectLatest { target ->
            do {
                val now = withFrameMillis { it }
                // A step's share of the way to the rate; after a quiet spell (nothing to follow) one step, not the spell.
                val step = if (at == NONE) STEP_MS else minOf(now - at, STEP_MS)
                average = target + (average - target) * exp(-step.toDouble() / RvMotion.GLIDE_TAU_MS)
                at = now
                val reached = abs(average - target) <= maxOf(abs(target) * REACHED_SHARE, REACHED_BYTES)
                if (reached) average = target
                number.animateTo(average.toFloat(), motion.tween(RvMotion.GLIDE_MS, RvMotion.Decelerate)) { write(value.toDouble()) }
                if (!reached) delay((at + STEP_MS - withFrameMillis { it }).coerceAtLeast(0L))
            } while (!reached)
        }
    }

    private fun write(value: Double) {
        unit = format.rateUnit(value, unit)
        shown = format.rate(value, unit)
    }

    private companion object {
        const val NONE = Long.MIN_VALUE
        const val STEP_MS = 1_000L

        /** Within a thousandth of the rate (under what three significant digits show), or half a byte, it has arrived. */
        const val REACHED_SHARE = 0.001
        const val REACHED_BYTES = 0.5
    }
}
