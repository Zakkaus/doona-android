package dev.doona.android.prototype.rv

import androidx.compose.animation.core.withInfiniteAnimationFrameNanos
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.MotionDurationScale
import dev.doona.android.designsystem.rv.Rv
import dev.doona.android.designsystem.rv.RvMotion
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.collectLatest

/**
 * The marker's pulse while [on] (connected, with a place to mark): 0 to 1 each [RvMotion.PULSE_MS]; 0 off, without
 * motion, or while [resting] (read where it is drawn, so resting redraws nothing but the ring, once).
 */
@Composable
internal fun rvPulse(on: Boolean, resting: () -> Boolean): () -> Float {
    if (!on || Rv.motion.instant) return { 0f }
    var phase by remember { mutableFloatStateOf(0f) }
    // The loop runs only while the ring is not resting: a resting pulse asks for no frame (it used to keep an infinite
    // transition going and only stop reading it). It starts again from the ring's first frame. Each frame is an infinite
    // animation's frame, so a test that waits for idle stops the loop, as it stops an infinite transition.
    LaunchedEffect(resting) {
        snapshotFlow { resting() }.collectLatest { paused ->
            phase = 0f
            if (paused) return@collectLatest
            val scale = currentCoroutineContext()[MotionDurationScale]?.scaleFactor ?: 1f
            val period = RvMotion.PULSE_MS * 1_000_000L * scale
            var start = -1L
            while (true) {
                withInfiniteAnimationFrameNanos { now ->
                    if (start < 0) start = now
                    phase = RvMotion.Decelerate.transform(((now - start) % period.toLong().coerceAtLeast(1L)) / period)
                }
            }
        }
    }
    return { if (resting()) 0f else phase }
}
