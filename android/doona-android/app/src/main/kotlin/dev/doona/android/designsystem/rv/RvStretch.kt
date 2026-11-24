package dev.doona.android.prototype.rv

import androidx.compose.animation.core.animate
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Velocity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** A pull's distances in pixels: [commit] (the threshold), [dead] (before anything shows), [give] (the rubber band's reach), [fling] (per second). */
internal class RvPullPx(val commit: Float, val dead: Float, val give: Float, val fling: Float)

/**
 * A downward pull at the top of a scrolling column, which is either let go (it springs back in [RvMotion.SNAP_MS]) or
 * committed ([onCommit] with the release velocity, then it springs back in [RvMotion.RETURN_MS] while the caller runs
 * its transition). [pulled] follows the finger 1:1 up to [RvPullPx.commit], then with rising resistance to at most
 * [RvPullPx.give] further. It commits on release past the threshold, or on a downward fling faster than
 * [RvPullPx.fling] once past [RvPullPx.dead]. A spring back can be caught: a touch stops it where it is and the finger
 * carries on from there. It only starts while [enabled]. [RvFrames] times the finger's part as [gesture].
 */
@Stable
internal class RvStretch(
    private val scope: CoroutineScope,
    private val motion: RvMotionScale,
    private val px: RvPullPx,
    private val enabled: () -> Boolean,
    private val onCommit: (velocity: Float) -> Unit,
    private val gesture: String,
) {
    var pulled by mutableFloatStateOf(0f)
        private set

    /** Past the threshold: letting go now commits. The threshold's haptic tick watches it. */
    val armed by derivedStateOf { pulled >= px.commit }

    /** 0 until [RvPullPx.dead], 1 at the threshold: Revolut's continuous progress. */
    val progress: Float get() = ((pulled - px.dead) / (px.commit - px.dead)).coerceIn(0f, 1f)

    private var travel = 0f
    private var dragged = false
    private var back: Job? = null

    val connection = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            if (source != NestedScrollSource.UserInput || pulled <= 0f || available.y >= 0f) return Offset.Zero
            begin()
            val take = maxOf(available.y, -travel)
            follow(take)
            return Offset(0f, take)
        }

        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
            if (source != NestedScrollSource.UserInput || available.y <= 0f || !(enabled() || pulled > 0f)) return Offset.Zero
            begin()
            follow(available.y)
            return Offset(0f, available.y)
        }

        override suspend fun onPreFling(available: Velocity): Velocity {
            if (pulled <= 0f) return Velocity.Zero
            release(available.y)
            return available
        }
    }

    /** Lets the finger go: commits or springs back. */
    fun release(velocity: Float) {
        RvFrames.end(gesture)
        val commit = pulled >= px.commit || (velocity >= px.fling && pulled >= px.dead)
        if (commit) onCommit(velocity)
        springBack(if (commit) RvMotion.RETURN_MS else RvMotion.SNAP_MS, velocity)
    }

    /** Drops the pull at once, as when a commit takes over its distance. */
    fun clear() {
        back?.cancel()
        pulled = 0f
        travel = 0f
    }

    /** A drag's first move: a spring back under way stops, and the band carries on from where it is. */
    private fun begin() {
        if (dragged) return
        back?.cancel()
        travel = unband(pulled)
        dragged = true
        RvFrames.begin(gesture)
    }

    private fun follow(dy: Float) {
        travel = (travel + dy).coerceAtLeast(0f)
        pulled = band(travel)
    }

    /** Springs [pulled] back to 0, starting at the finger's [velocity] (through the band, so it carries on smoothly). */
    private fun springBack(ms: Int, velocity: Float) {
        back?.cancel()
        val slope = bandSlope(travel)
        travel = 0f
        if (pulled <= 0f) return
        back = scope.launch { animate(pulled, 0f, velocity * slope, motion.settle(ms, PIXEL)) { v, _ -> pulled = v } }
    }

    /**
     * Every touch starts a new drag. A touch on a spring back stops it where it is; one that lets go without dragging
     * sends it on again.
     */
    val catching: Modifier = Modifier.pointerInput(this) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            dragged = false
            if (back?.isActive != true) return@awaitEachGesture
            back?.cancel()
            waitForUpOrCancellation(PointerEventPass.Initial)
            if (!dragged && pulled > 0f) springBack(RvMotion.SNAP_MS, 0f)
        }
    }

    val nested: Modifier = Modifier.nestedScroll(connection)

    /** Finger travel to pull: 1:1 to the threshold, then rubber band up to [RvPullPx.give] beyond it. */
    private fun band(travel: Float): Float {
        if (travel <= px.commit) return travel
        val over = travel - px.commit
        return px.commit + px.give * (1f - 1f / (1f + over / px.give))
    }

    private fun bandSlope(travel: Float): Float {
        if (travel <= px.commit) return 1f
        val stretch = 1f + (travel - px.commit) / px.give
        return 1f / (stretch * stretch)
    }

    private fun unband(pulled: Float): Float {
        if (pulled <= px.commit) return pulled
        val share = ((pulled - px.commit) / px.give).coerceAtMost(MAX_SHARE)
        return px.commit + px.give * (1f / (1f - share) - 1f)
    }

    private companion object {
        const val PIXEL = 0.5f
        const val MAX_SHARE = 0.99f
    }
}
