package dev.doona.android.prototype.rv

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.animate
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Velocity
import kotlinx.coroutines.launch

/**
 * 活動's pull-down to the node picker (ARCH D30). While 活動 is at its top, a downward drag pulls [pulled] pixels with
 * the finger; letting go past [RvMotion.pullCommit] (or flinging down faster than [RvMotion.pullFling]) commits and
 * [shown] runs to 1 in [RvMotion.REVEAL_MS], anything less runs [pulled] back to 0 in [RvMotion.RETURN_MS]; dragging up
 * again first takes the pull back. [close] runs [shown] back to 0 in the return's time. Both curves decelerate, without
 * overshoot. Whether it is open survives a pushed page and the activity's recreation.
 */
@Stable
internal class RvPullState(open: Boolean, private val motion: RvMotionScale, private val commitPx: Float, private val flingPx: Float) {
    var pulled by mutableFloatStateOf(0f)
        private set
    var shown by mutableFloatStateOf(if (open) 1f else 0f)
        private set

    /** From the commit until a close starts: the picker owns the screen. */
    var open by mutableStateOf(open)
        private set

    /** The screen's height in pixels: a full reveal. */
    var height by mutableFloatStateOf(0f)

    /** How far the picker has come down, in pixels from the top: the finger's pull, then the commit's run to [height]. */
    val reveal: Float get() = pulled + (height - pulled) * shown

    private var settling = false

    val connection = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            if (source != NestedScrollSource.UserInput || pulled <= 0f || available.y >= 0f) return Offset.Zero
            val take = maxOf(available.y, -pulled)
            pulled += take
            return Offset(0f, take)
        }

        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
            if (source != NestedScrollSource.UserInput || available.y <= 0f || open || settling) return Offset.Zero
            pulled = (pulled + available.y).coerceAtMost(height)
            return Offset(0f, available.y)
        }

        override suspend fun onPreFling(available: Velocity): Velocity {
            if (pulled <= 0f || open || settling) return Velocity.Zero
            if (pulled >= commitPx || available.y >= flingPx) commit() else settle { animate(pulled, 0f, animationSpec = it(RvMotion.RETURN_MS)) { v, _ -> pulled = v } }
            return available
        }
    }

    private suspend fun commit() {
        open = true
        settle { animate(shown, 1f, animationSpec = it(RvMotion.REVEAL_MS)) { v, _ -> shown = v } }
        pulled = 0f
    }

    /** Back to 活動: the picker goes the way it came, in the return's time. */
    suspend fun close() {
        if (!open) return
        open = false
        settle { animate(shown, 0f, animationSpec = it(RvMotion.RETURN_MS)) { v, _ -> shown = v } }
    }

    private suspend fun settle(run: suspend ((Int) -> FiniteAnimationSpec<Float>) -> Unit) {
        settling = true
        try {
            run { ms -> motion.tween(ms, RvMotion.Decelerate) }
        } finally {
            settling = false
        }
    }
}

@Composable
internal fun rememberRvPullState(): RvPullState {
    val motion = Rv.motion
    val (commit, fling) = with(LocalDensity.current) { RvMotion.pullCommit.toPx() to RvMotion.pullFling.toPx() }
    val saver = Saver<RvPullState, Boolean>({ it.open }, { RvPullState(it, motion, commit, fling) })
    return rememberSaveable(motion, saver = saver) { RvPullState(false, motion, commit, fling) }
}

/**
 * 活動 with the node picker it pulls down, moving as [variant] says. [activity] draws 活動, given the hero's stretch in
 * pixels for P2 (null otherwise); [picker] draws the picker, given its close and, for P3, the modifier of its n-th
 * section (each drops in [RvMotion.STAGGER_MS] after the one before).
 */
@Composable
internal fun RvPullDown(
    variant: RvPullMotion,
    state: RvPullState,
    activity: @Composable (stretch: (() -> Float)?) -> Unit,
    picker: @Composable (close: () -> Unit, section: (Int) -> Modifier) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val close: () -> Unit = { scope.launch { state.close() } }
    BackHandler(enabled = state.open, onBack = close)
    val dropPx = with(LocalDensity.current) { RvMotion.drop.toPx() }
    val drawPicker = state.pulled > 0f || state.shown > 0f
    Box(Modifier.fillMaxSize().onSizeChanged { state.height = it.height.toFloat() }.nestedScroll(state.connection)) {
        if (drawPicker && variant == RvPullMotion.Drop) picker(close) { i -> Modifier.graphicsLayer { dropIn(state.shown, i, dropPx) } }
        Box(Modifier.fillMaxSize().graphicsLayer { if (variant == RvPullMotion.Drop) translationY = state.reveal }) {
            activity(if (variant == RvPullMotion.Stretch) ({ state.pulled }) else null)
            if (variant == RvPullMotion.Sheet && drawPicker) {
                Canvas(Modifier.fillMaxSize()) { drawRect(Color.Black, alpha = RvMotion.DIM * (state.reveal / size.height).coerceIn(0f, 1f)) }
            }
        }
        if (!drawPicker) return@Box
        when (variant) {
            RvPullMotion.Sheet -> Box(Modifier.graphicsLayer { translationY = state.reveal - state.height }) { picker(close) { Modifier } }
            RvPullMotion.Stretch -> Box(Modifier.graphicsLayer { alpha = state.shown }) { picker(close) { Modifier } }
            RvPullMotion.Drop -> Unit
        }
    }
}

/** P3: section [index] drops in from [RvMotion.drop] above as [shown] runs, [RvMotion.STAGGER_MS] after the one before. */
private fun GraphicsLayerScope.dropIn(shown: Float, index: Int, dropPx: Float) {
    val at = index.coerceAtMost(STAGGERED)
    val p = ((shown * RvMotion.REVEAL_MS - at * RvMotion.STAGGER_MS) / (RvMotion.REVEAL_MS - STAGGERED * RvMotion.STAGGER_MS)).coerceIn(0f, 1f)
    alpha = p
    translationY = -(1 - p) * dropPx
}

/** Sections past this one drop in with it. */
private const val STAGGERED = 8
