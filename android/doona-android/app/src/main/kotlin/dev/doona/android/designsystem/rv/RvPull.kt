package dev.doona.android.designsystem.rv

import android.os.Build
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.clearAndSetSemantics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * 活動's pull-down to the node picker (ARCH D30), as Revolut's (pass 2), but never deaf to the finger.
 *
 * While 活動 is at its top a downward drag is [opening]: the hero stretches with the finger, and Revolut's continuous
 * progress ([look]) blurs and fades 活動 and grows the search pill. Past the threshold a haptic tick says letting go now
 * opens; letting go, or a fast enough fling, commits: [shown] springs to 1 carrying the finger's velocity, in about
 * [RvMotion.REVEAL_MS], with no overshoot. Back (or the system's predictive back, which the picker follows), the back
 * disc, or [closing], a pull down at the picker's top, run it back in about [RvMotion.RETURN_MS]. A touch while it is on
 * its way in or out catches it; the finger then moves it, and letting go settles it by the fling or by where it is.
 * Whether it is open survives a pushed page and the activity's recreation.
 */
@Stable
internal class RvPullState(open: Boolean, private val scope: CoroutineScope, private val motion: RvMotionScale, private val px: RvPullPx) {
    /** How far the picker has come in: 0 on 活動, 1 open. */
    val shown = Animatable(if (open) 1f else 0f)

    /** Committed and not on its way back: the picker takes back, and 活動 no pull. */
    var open by mutableStateOf(open)
        private set

    /** The screen's height in pixels: a full reveal. */
    var height by mutableFloatStateOf(0f)

    /** How the picker comes in; P3 hands the pull's distance to the reveal. */
    var variant by mutableStateOf(RvPullMotion.Sheet)

    /** 活動's pull: the hero stretches with it; let go past the threshold, the picker opens. */
    val opening = RvStretch(scope, motion, px, enabled = { !this.open && !moving }, onCommit = ::reveal, gesture = "pull")

    /** The picker's pull down at its top: let go past the threshold, the picker closes. */
    val closing = RvStretch(scope, motion, px, enabled = { this.open && !moving }, onCommit = { close(-it) }, gesture = "pull-close")

    /** A finger (or a back gesture) holds the picker part way; [moved] once it has dragged it. */
    private var held by mutableStateOf(false)
    private var moved = false
    private var backFrom = 1f

    /** On its way in or out, or held: a touch anywhere catches it. */
    val moving: Boolean get() = shown.isRunning || held

    /**
     * Draw the picker: from a reveal until a close has run all the way back. A state of its own, set only there, so
     * that composition, which reads it, hears of it only when it flips. Worked out from [shown] as `open || shown > 0`,
     * it read [shown] on every frame of a close (where [open] is already false) and composed the whole picker again
     * each frame, about 100 ms a frame in a debug build.
     */
    var drawn by mutableStateOf(open)
        private set

    /**
     * Composed, shown or not: from [warmUp], as soon as 活動 is pulled, or from a reveal, and kept from then on. On the
     * phone composing the picker took the reveal's first frame 45 ms and disposing it the close's last 24 ms; composed
     * while the finger pulls (a row a frame) and kept, neither falls in a frame that moves. Not [drawn], it is measured
     * but not placed: not drawn, not touched, and hidden from accessibility. Not composed before a pull, so a page pushed
     * over an unpulled 活動 does not dispose it with 活動.
     */
    var warm by mutableStateOf(open)
        private set

    /** Composes the picker ahead, hidden. */
    fun warmUp() {
        warm = true
    }

    /** How far 活動 has blurred and faded: the pull's progress, then at least how far the picker has come in. */
    val look: Float get() = maxOf(opening.progress, shown.value)

    /** Opens the picker, carrying a downward [velocity] in pixels per second. */
    fun reveal(velocity: Float = 0f) {
        open = true
        drawn = true
        warm = true
        // P3: 活動 has already moved down with the finger, so the reveal takes over from that distance.
        val from = if (variant == RvPullMotion.Drop && height > 0f) (opening.pulled / height).coerceIn(0f, 1f) else null
        if (from != null) opening.clear()
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            from?.let { shown.snapTo(it) }
            RvFrames.begin(REVEAL)
            try {
                shown.animateTo(1f, motion.settle(RvMotion.REVEAL_MS, FRACTION), velocity / height.coerceAtLeast(1f))
            } finally {
                RvFrames.end(REVEAL)
            }
        }
    }

    /** Back to 活動 the way it came, from wherever it is; [velocity] in pixels per second, downward positive. */
    fun close(velocity: Float = 0f) {
        open = false
        held = false
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            RvFrames.begin(CLOSE)
            try {
                // At animator scale 0 it is gone in this frame: an animation, even a snap, would end in the next one.
                if (motion.instant) shown.snapTo(0f) else shown.animateTo(0f, motion.settle(RvMotion.RETURN_MS, FRACTION), velocity / height.coerceAtLeast(1f))
                // All the way back, not caught nor turned round on the way (either cancels the animation above).
                drawn = false
            } finally {
                RvFrames.end(CLOSE)
            }
        }
    }

    fun grab() {
        RvFrames.begin(GRAB)
        held = true
        moved = false
        scope.launch(start = CoroutineStart.UNDISPATCHED) { shown.stop() }
    }

    fun drag(dy: Float) {
        moved = true
        scope.launch(start = CoroutineStart.UNDISPATCHED) { shown.snapTo((shown.value + dy / height.coerceAtLeast(1f)).coerceIn(0f, 1f)) }
    }

    /** Lets the picker go: a touch that did not drag sends it on its way; otherwise a fling decides, or where it is. */
    fun letGo(velocity: Float) {
        RvFrames.end(GRAB)
        held = false
        val opens = when {
            !moved -> open
            abs(velocity) >= px.fling -> velocity > 0f
            else -> shown.value >= HALF
        }
        if (opens) reveal(velocity) else close(velocity)
    }

    /** The system's back gesture started: the picker will follow it back from where it is. */
    fun backStarted() {
        held = true
        backFrom = shown.value
        scope.launch(start = CoroutineStart.UNDISPATCHED) { shown.stop() }
    }

    fun backProgress(progress: Float) {
        scope.launch(start = CoroutineStart.UNDISPATCHED) { shown.snapTo(backFrom * (1f - progress)) }
    }

    /** A cancelled back gesture: the picker goes back to open. */
    fun backCancelled() {
        held = false
        reveal()
    }

    private companion object {
        const val FRACTION = 0.001f
        const val HALF = 0.5f
        const val REVEAL = "reveal"
        const val CLOSE = "close"
        const val GRAB = "picker-grab"
    }
}

/** The pull-down's state, the picker [open] at first or not; afterwards whatever it was when the activity went. */
@Composable
internal fun rememberRvPullState(open: Boolean = false): RvPullState {
    val motion = Rv.motion
    val scope = rememberCoroutineScope()
    val px = with(LocalDensity.current) { RvPullPx(RvMotion.pullCommit.toPx(), RvMotion.pullDead.toPx(), RvMotion.pullGive.toPx(), RvMotion.pullFling.toPx()) }
    val saver = Saver<RvPullState, Boolean>({ it.open }, { RvPullState(it, scope, motion, px) })
    return rememberSaveable(motion, saver = saver) { RvPullState(open, scope, motion, px) }
}

/**
 * What 活動 shows of the pull: the hero's [stretch] in pixels (null when 活動 itself moves, P3), the [progress] its blur,
 * fade and search pill follow, and [open], the pull's action for TalkBack.
 */
@Immutable
internal class RvPullLook(val stretch: (() -> Float)?, val progress: () -> Float, val open: (() -> Unit)?) {
    companion object {
        val None = RvPullLook(null, { 0f }, null)
    }
}

/** What the picker does with the pull: [close] it, give its n-th [section] a modifier (P3's drop), move its list by [stretch]. */
@Immutable
internal class RvPickerMotion(val close: () -> Unit, val section: (Int) -> Modifier, val stretch: () -> Float)

/**
 * 活動 with the node picker it pulls down, moving as [variant] says: P1 the picker slides down over 活動 as one sheet
 * and 活動 dims; P2 it cross-fades in; P3 活動 slides down out of view and the picker's sections drop in one after another.
 */
@Composable
internal fun RvPullDown(variant: RvPullMotion, state: RvPullState, activity: @Composable (RvPullLook) -> Unit, picker: @Composable (RvPickerMotion) -> Unit) {
    SideEffect { state.variant = variant }
    PullBack(state)
    ThresholdTick(state.opening)
    ThresholdTick(state.closing)
    WarmUp(state)
    val dropPx = with(LocalDensity.current) { RvMotion.drop.toPx() }
    val drop = variant == RvPullMotion.Drop
    val look = remember(state, drop) { RvPullLook(if (drop) null else ({ state.opening.pulled }), { state.look }, { state.reveal() }) }
    // One motion each, kept: a new one would make the picker compose again whenever this does.
    val still = remember(state) { RvPickerMotion({ state.close() }, { Modifier }, { state.closing.pulled }) }
    val staggered = remember(state, dropPx) {
        RvPickerMotion({ state.close() }, { i -> Modifier.graphicsLayer { dropIn(state.shown.value, i, dropPx) } }, { state.closing.pulled })
    }
    Box(Modifier.fillMaxSize().onSizeChanged { state.height = it.height.toFloat() }.then(state.opening.catching).then(state.opening.nested)) {
        if (drop && state.warm) PickerBox(state, Modifier) { picker(staggered) }
        Box(Modifier.fillMaxSize().graphicsLayer { if (drop) translationY = state.opening.pulled + state.shown.value * state.height }) {
            activity(look)
            if (variant == RvPullMotion.Sheet && state.drawn) {
                // The dim is a layer's alpha, so its frames redraw nothing: drawn, it made this box, 活動 and its bars
                // with their glass, record again on every frame of the sheet.
                Box(
                    Modifier.fillMaxSize().graphicsLayer {
                        alpha = RvMotion.DIM * state.shown.value
                        compositingStrategy = CompositingStrategy.ModulateAlpha
                    }.background(Color.Black),
                )
            }
        }
        if (state.warm) {
            when (variant) {
                RvPullMotion.Sheet -> PickerBox(state, Modifier.graphicsLayer { translationY = (state.shown.value - 1f) * state.height }) { picker(still) }
                RvPullMotion.Stretch -> PickerBox(state, Modifier.graphicsLayer { alpha = state.shown.value }) { picker(still) }
                RvPullMotion.Drop -> Unit
            }
        }
        if (state.moving) GrabLayer(state)
    }
}

/**
 * The picker, with its own pull down at the top to close it. Until it is [RvPullState.drawn] it is measured and not
 * placed, so nothing draws it and no touch reaches it, its semantics are cleared, and its rows follow no latency test or
 * update (a busy bar would run in each).
 */
@Composable
private fun PickerBox(state: RvPullState, modifier: Modifier, content: @Composable () -> Unit) {
    val drawn = state.drawn
    val shown = remember(state) { { state.drawn } }
    val hidden = if (drawn) Modifier else Modifier.clearAndSetSemantics {}
    Layout(
        {
            CompositionLocalProvider(
                LocalRvShown provides shown,
                LocalRvTesting provides (drawn && LocalRvTesting.current),
                LocalRvTestingNodes provides if (drawn) LocalRvTestingNodes.current else emptySet(),
                LocalRvUpdating provides if (drawn) LocalRvUpdating.current else emptySet(),
                content = content,
            )
        },
        modifier.fillMaxSize().then(state.closing.catching).then(state.closing.nested).then(hidden),
    ) { measurables, constraints ->
        // As a Box lays out its content, at its top start, but placed only while shown.
        val placeables = measurables.map { it.measure(constraints.copy(minWidth = 0, minHeight = 0)) }
        val width = placeables.maxOfOrNull { it.width }?.coerceAtLeast(constraints.minWidth) ?: constraints.minWidth
        val height = placeables.maxOfOrNull { it.height }?.coerceAtLeast(constraints.minHeight) ?: constraints.minHeight
        layout(width, height) { if (shown()) placeables.forEach { it.placeRelative(0, 0) } }
    }
}

/**
 * Whether the content is on screen: the picker composed ahead is not until it is revealed. Its rows come in one a frame
 * while hidden, and a shared element there takes part only while shown.
 */
internal val LocalRvShown = staticCompositionLocalOf<() -> Boolean> { { true } }

/** The subscriptions being updated: their rows show [RvBusy] where what is left would be. */
internal val LocalRvUpdating = compositionLocalOf { emptySet<String>() }

/**
 * How 活動's pull-down opens the node picker: P1 the picker slides down as one sheet over 活動, which dims (the default);
 * P2 the hero stretches with the finger, then the picker cross-fades in; P3 活動 slides down out of view and the picker's
 * sections drop in one after another.
 */
internal enum class RvPullMotion(val code: String) { Sheet("P1"), Stretch("P2"), Drop("P3") }

/** Composes the picker ahead, hidden, as soon as 活動 is pulled: by the time the pull commits its rows are in. */
@Composable
private fun WarmUp(state: RvPullState) {
    LaunchedEffect(state) {
        if (state.warm) return@LaunchedEffect
        snapshotFlow { state.opening.progress > 0f }.first { it }
        state.warmUp()
    }
}

/** Over everything while the picker moves or is held: a touch catches it, the finger moves it 1:1, letting go settles it. */
@Composable
private fun GrabLayer(state: RvPullState) {
    val drag = rememberDraggableState { state.drag(it) }
    Box(Modifier.fillMaxSize().draggable(drag, Orientation.Vertical, startDragImmediately = true, onDragStarted = { state.grab() }, onDragStopped = { state.letGo(it) }))
}

/** Back while the picker is open, or on its way in, takes it back; the system's predictive back moves it with the gesture. */
@Composable
private fun PullBack(state: RvPullState) {
    PredictiveBackHandler(enabled = state.open) { events ->
        try {
            var started = false
            events.collect {
                if (!started) state.backStarted()
                started = true
                state.backProgress(it.progress)
            }
            state.close()
        } catch (e: CancellationException) {
            state.backCancelled()
            throw e
        }
    }
}

/** One haptic tick each time [stretch] crosses its threshold on the way out; none on the way back. */
@Composable
private fun ThresholdTick(stretch: RvStretch) {
    val haptic = LocalHapticFeedback.current
    LaunchedEffect(stretch) { snapshotFlow { stretch.armed }.collect { if (it) haptic.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate) } }
}

/**
 * 活動 under the pull as [progress] runs to 1: a blur of a fixed radius cross-fades in (from API 31; the radius is never
 * animated, only the blurred copy's alpha), then the page colour fades over it by [RvMotion.PULL_FADE].
 *
 * A frame of the pull costs no blur. What it draws is a layer of its own, which keeps itself up to date, and the sharp
 * and blurred copies hold references to it, recorded once a pull (again only if the size changes): a frame sets the
 * blurred copy's alpha, and the blur, drawn once, is drawn again only when 活動 itself changes. This draw is a layer of
 * its own too, so a frame records it alone, not the bars beside 活動 with their glass.
 */
@Composable
internal fun Modifier.rvPullBlur(progress: () -> Float): Modifier {
    val sharp = rememberGraphicsLayer()
    val soft = rememberGraphicsLayer()
    val radius = with(LocalDensity.current) { RvMotion.pullBlur.toPx() }
    val page = Rv.colors.page
    val blurs = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val blur = remember(radius) { BlurEffect(radius, radius, TileMode.Clamp) }
    val recorded = remember { RvRecorded() }
    return graphicsLayer {}.drawWithContent {
        val p = progress()
        if (p <= 0f) {
            recorded.size = Size.Unspecified
            return@drawWithContent drawContent()
        }
        if (blurs) {
            if (recorded.size != size) {
                sharp.record { this@drawWithContent.drawContent() }
                soft.renderEffect = blur
                soft.record { drawLayer(sharp) }
                recorded.size = size
            }
            soft.alpha = p
            drawLayer(sharp)
            drawLayer(soft)
        } else {
            drawContent()
        }
        drawRect(page, alpha = RvMotion.PULL_FADE * p)
    }.graphicsLayer {}
}

/** The size [rvPullBlur]'s copies were recorded at; unspecified when they are to be recorded again. */
private class RvRecorded {
    var size = Size.Unspecified
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
