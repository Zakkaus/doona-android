package dev.doona.android.feature.activity

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import dev.doona.android.R
import dev.doona.android.designsystem.rv.Rv
import dev.doona.android.designsystem.rv.RvFrames
import dev.doona.android.designsystem.rv.RvMotion
import dev.doona.android.designsystem.rv.RvMotionScale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * 活動's hero turned between its views by a horizontal swipe (brief rv-gestures H1), as the pull-down moves (rule 1-3).
 *
 * The views lie side by side, a hero's width apart; [at] is where they are, 0 on the first, 1 on the second. The finger
 * moves them 1:1, and past the first or the last view with the pull's rubber band ([RvMotion.pullGive] at most). Letting
 * go settles on the nearest view, so half the width commits, or on the next one in the fling's direction when it is
 * faster than [RvMotion.pullFling]; the spring takes the finger's velocity, landing in [RvMotion.SLIDE_MS] on a new view
 * or [RvMotion.SNAP_MS] back on the same one, with no overshoot. A touch while it settles catches it where it is. No
 * haptic: a view change commits nothing (rule 6). Which view is shown survives a pushed page and the activity's recreation.
 */
@Stable
internal class RvHeroPager(view: Int, private val count: Int, private val scope: CoroutineScope, private val motion: RvMotionScale, private val give: Float, private val fling: Float) {
    val at = Animatable(view.toFloat())

    /** The hero's width in pixels: one view's travel. */
    var width by mutableFloatStateOf(0f)

    /** The view nearest to where they are: the dots mark it and TalkBack reads it. */
    val view by derivedStateOf { at.value.roundToInt().coerceIn(0, count - 1) }

    /** Settling: a touch catches it rather than waiting for the slop. */
    val settling: Boolean get() = at.isRunning

    /** The view it settles on, or rests on. */
    var target = view
        private set

    /** The finger's travel in pixels from the first view, before the rubber band. */
    private var travel = 0f
    private var from = view

    /** A finger takes the views: a settle under way stops, and the finger carries on from where they are. */
    fun grab() {
        RvFrames.begin(GESTURE)
        scope.launch(start = CoroutineStart.UNDISPATCHED) { at.stop() }
        from = target
        travel = unband(at.value * width)
    }

    /** The finger moved [dx] pixels, rightward positive: the views follow it. */
    fun drag(dx: Float) {
        if (width <= 0f) return
        travel -= dx
        val to = band(travel) / width
        scope.launch(start = CoroutineStart.UNDISPATCHED) { at.snapTo(to) }
    }

    /** Lets the views go at [velocity] pixels per second, rightward positive: they settle as the class says. */
    fun release(velocity: Float) {
        RvFrames.end(GESTURE)
        val toward = -velocity
        val position = at.value
        val nearest = when {
            abs(toward) < fling -> position.roundToInt()
            toward > 0f -> ceil(position).toInt()
            else -> floor(position).toInt()
        }.coerceIn(0, count - 1)
        val ms = if (nearest == from) RvMotion.SNAP_MS else RvMotion.SLIDE_MS
        settle(nearest, toward * slope(travel) / width.coerceAtLeast(1f), ms)
    }

    /** Shows [view] (a dot's tap, a TalkBack action), from wherever the views are. */
    fun show(view: Int) = settle(view, 0f, RvMotion.SLIDE_MS, DOTS)

    /** Springs to [view], starting at [velocity] in views per second; [RvFrames] times it as [gesture]. */
    private fun settle(view: Int, velocity: Float, ms: Int, gesture: String = SETTLE) {
        target = view
        travel = 0f
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            RvFrames.begin(gesture)
            try {
                at.animateTo(view.toFloat(), motion.settle(ms, FRACTION), velocity)
            } finally {
                RvFrames.end(gesture)
            }
        }
    }

    /** Finger travel to position, in pixels: 1:1 between the first and the last view, a rubber band past either. */
    private fun band(travel: Float): Float {
        val last = (count - 1) * width
        return when {
            travel < 0f -> -stretch(-travel)
            travel > last -> last + stretch(travel - last)
            else -> travel
        }
    }

    private fun stretch(over: Float): Float = give * (1f - 1f / (1f + over / give))

    /** How far the views move per pixel of the finger at [travel]: 1 inside, less and less past the ends. */
    private fun slope(travel: Float): Float {
        val over = maxOf(-travel, travel - (count - 1) * width, 0f)
        val stretch = 1f + over / give
        return 1f / (stretch * stretch)
    }

    private fun unband(position: Float): Float {
        val last = (count - 1) * width
        return when {
            position < 0f -> -unstretch(-position)
            position > last -> last + unstretch(position - last)
            else -> position
        }
    }

    private fun unstretch(over: Float): Float = give * (1f / (1f - (over / give).coerceAtMost(MAX_SHARE)) - 1f)

    private companion object {
        const val FRACTION = 0.001f
        const val MAX_SHARE = 0.99f
        const val GESTURE = "hero-swipe"
        const val SETTLE = "hero-settle"
        const val DOTS = "hero-dots"
    }
}

/** The hero's views over [count] views, on the first at first; afterwards on whichever it was when the activity went. */
@Composable
internal fun rememberRvHeroPager(count: Int): RvHeroPager {
    val motion = Rv.motion
    val scope = rememberCoroutineScope()
    val give = with(LocalDensity.current) { RvMotion.pullGive.toPx() }
    val fling = with(LocalDensity.current) { RvMotion.pullFling.toPx() }
    val saver = Saver<RvHeroPager, Int>({ it.target }, { RvHeroPager(it, count, scope, motion, give, fling) })
    return rememberSaveable(motion, count, give, fling, saver = saver) { RvHeroPager(0, count, scope, motion, give, fling) }
}

/**
 * The hero's views side by side, each centred across the hero's width, moved by [pager] under a horizontal drag. A
 * vertical drag is left to the page (its scroll, 活動's pull-down): whichever way the finger first passes the touch slop
 * decides. TalkBack reads the view shown only, and offers to show each other one, by its name in [names].
 */
@Composable
internal fun RvHeroPages(pager: RvHeroPager, names: List<String>, modifier: Modifier = Modifier, view: @Composable (Int) -> Unit) {
    val drag = rememberDraggableState { pager.drag(it) }
    val shown = pager.view
    val actions = names.mapIndexed { i, name ->
        CustomAccessibilityAction(stringResource(R.string.rv_hero_show, name)) {
            pager.show(i)
            true
        }
    }.filterIndexed { i, _ -> i != shown }
    Box(
        modifier
            .fillMaxWidth()
            .onSizeChanged { pager.width = it.width.toFloat() }
            .draggable(drag, Orientation.Horizontal, startDragImmediately = pager.settling, onDragStarted = { pager.grab() }, onDragStopped = { pager.release(it) })
            .semantics { customActions = actions },
    ) {
        names.indices.forEach { i ->
            val hidden = if (i == shown) Modifier else Modifier.clearAndSetSemantics {}
            Box(Modifier.fillMaxWidth().graphicsLayer { translationX = (i - pager.at.value) * size.width }.then(hidden), contentAlignment = Alignment.TopCenter) { view(i) }
        }
    }
}
