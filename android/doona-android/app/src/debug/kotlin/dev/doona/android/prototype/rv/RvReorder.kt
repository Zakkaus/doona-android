package dev.doona.android.prototype.rv

import androidx.compose.animation.core.animate
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker1D
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.util.fastFirstOrNull
import androidx.compose.ui.zIndex
import dev.doona.android.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Drag to reorder (brief rv-gestures G4) for a keyed `Column` of [RvRow]s inside an [RvCard] on a `verticalScroll` page.
 * Its intended users are 策略's rule rows and group rows: rules are matched in order and the first match wins, so the
 * order is the user's to set. That page lives on another branch, so this is the hook it adopts; nothing here uses it yet.
 *
 * A long press ([RvMotion.LONG_PRESS_MS], or the user's longer delay) lifts the row with a haptic tick: it draws above
 * its neighbours on the raised fill under the long-press menu's shadow and follows the finger 1:1 (a layer's
 * translation, so a frame lays nothing out and allocates nothing). The other rows give way as it passes their midpoint,
 * each settling in [RvMotion.MOVE_MS]. Near the viewport's top or bottom the page scrolls under it ([RvMotion.reorderEdge],
 * [RvMotion.reorderScroll]) and the row stays under the finger. Letting go ticks again and the row springs into its slot
 * with the finger's velocity; once it is there [onMove] reports the move, once, and the page reorders its list in the
 * same frame. A touch on the list while it lands catches it. TalkBack has 上移 and 下移 on each row ([rvReorderMenu]).
 * At animator scale 0 every settling is instant; the haptics still fire. [RvFrames] times the whole as `reorder`.
 *
 * The rows must sit one after another in one parent (a card's rows do). [onMove] must reorder the page's list at once,
 * not later, or the rows show their old order for a frame.
 */
@Stable
internal class RvReorderState(
    private val scroll: ScrollState,
    private val scope: CoroutineScope,
    private val motion: RvMotionScale,
    private val haptic: HapticFeedback,
    private val px: RvReorderPx,
) {
    /** Moves the row at `from` to `to` in the page's list. */
    var onMove: (from: Int, to: Int) -> Unit = { _, _ -> }

    /** How many rows there are. */
    var count = 0
        private set

    private var phase by mutableStateOf(Phase.Idle)
    private var raised by mutableStateOf(false)
    private var from by mutableIntStateOf(NONE)
    private var to = NONE

    /** The finger's travel since the lift, in the window. */
    private var travel by mutableFloatStateOf(0f)
    private var anchor = 0f
    private var finger = 0f
    private var scroll0 = 0
    private var landing by mutableFloatStateOf(0f)
    private var landJob: Job? = null
    private var scrolling: Job? = null
    private val velocity = VelocityTracker1D(false)

    private var tops = FloatArray(0)
    private var heights = FloatArray(0)
    private var places = arrayOfNulls<LayoutCoordinates>(0)
    private val shifts = mutableListOf<MutableFloatState>()
    private var shiftTo = FloatArray(0)
    private var shiftJobs = arrayOfNulls<Job>(0)

    /** The scrolling container's viewport, for the edges that scroll. */
    internal var viewport: LayoutCoordinates? = null

    /** Nothing is held or landing: a long press may lift a row, and TalkBack may move one. */
    val idle: Boolean get() = phase == Phase.Idle

    /** Whether the row at [index] is the one held or landing, and whether a finger raised it (TalkBack's move does not). */
    fun moving(index: Int): Boolean = phase != Phase.Idle && from == index

    fun lifted(index: Int): Boolean = moving(index) && raised

    /** Where the row at [index] is drawn, from its slot: the held row with the finger, the others out of its way. */
    fun offset(index: Int): Float = when {
        phase == Phase.Idle -> 0f
        index == from -> if (phase == Phase.Held) travel + (scroll.value - scroll0) else landing
        index < count -> shifts[index].floatValue
        else -> 0f
    }

    /** Makes room for [rows] rows; it only grows, so a list that shrinks and grows again allocates nothing. */
    fun resize(rows: Int) {
        count = rows
        if (rows <= tops.size) return
        tops = tops.copyOf(rows)
        heights = heights.copyOf(rows)
        places = places.copyOf(rows)
        shiftTo = shiftTo.copyOf(rows)
        shiftJobs = shiftJobs.copyOf(rows)
        while (shifts.size < rows) shifts += mutableFloatStateOf(0f)
    }

    fun place(index: Int, coordinates: LayoutCoordinates) {
        if (index >= tops.size) return
        tops[index] = coordinates.positionInParent().y
        heights[index] = coordinates.size.height.toFloat()
        places[index] = coordinates
    }

    /** A long press on the row at [index], the finger at [y] in the row: it lifts. */
    fun lift(index: Int, y: Float, time: Long): Boolean {
        if (phase != Phase.Idle || index >= count) return false
        finger = rootY(index, y)
        anchor = finger
        travel = 0f
        scroll0 = scroll.value
        from = index
        to = index
        raised = true
        phase = Phase.Held
        velocity.resetTracking()
        velocity.addDataPoint(time, finger)
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        RvFrames.begin(GESTURE)
        return true
    }

    /** A finger down on row [index] while a row lands: it stops where it is, and the finger carries it on from there. */
    fun catch(index: Int, y: Float, time: Long): Boolean {
        if (phase != Phase.Landing) return false
        landJob?.cancel()
        finger = rootY(index, y)
        travel = landing - (scroll.value - scroll0)
        anchor = finger - travel
        raised = true
        phase = Phase.Held
        velocity.resetTracking()
        velocity.addDataPoint(time, finger)
        return true
    }

    /** The finger, on row [index] at [y] in it, moved. */
    fun drag(index: Int, y: Float, time: Long) {
        if (phase != Phase.Held) return
        finger = rootY(index, y)
        travel = finger - anchor
        velocity.addDataPoint(time, finger)
        retarget()
        if (scrolling?.isActive != true && edgeSpeed() != 0f) scrolling = scope.launch { autoScroll() }
    }

    /** The finger let go: the row lands. True when it lands where it was lifted, having barely moved (a long press). */
    fun release(slop: Float): Boolean {
        if (phase != Phase.Held) return false
        haptic.performHapticFeedback(HapticFeedbackType.GestureEnd)
        val still = to == from && abs(travel) <= slop
        land(travel + (scroll.value - scroll0), velocity.calculateVelocity())
        return still
    }

    /** TalkBack's 上移 ([by] -1) or 下移 (1): the row and its neighbour trade places, then [onMove] reports it. */
    fun step(index: Int, by: Int) {
        val target = index + by
        if (phase != Phase.Idle || index !in 0 until count || target !in 0 until count) return
        scroll0 = scroll.value
        from = index
        to = target
        raised = false
        RvFrames.begin(GESTURE)
        shiftOthers()
        land(0f, 0f)
    }

    private fun land(at: Float, speed: Float) {
        landing = at
        phase = Phase.Landing
        val slot = slot()
        landJob = scope.launch {
            animate(at, slot, speed, motion.settle(RvMotion.MOVE_MS, PIXEL)) { v, _ -> landing = v }
            commit()
        }
    }

    /** The row is in its slot: everything is drawn where the new order lays it out, in the frame the page reorders. */
    private fun commit() {
        val moved = from
        val target = to
        for (i in 0 until shiftJobs.size) {
            shiftJobs[i]?.cancel()
            shiftJobs[i] = null
            shiftTo[i] = 0f
            shifts[i].floatValue = 0f
        }
        phase = Phase.Idle
        raised = false
        from = NONE
        to = NONE
        RvFrames.end(GESTURE)
        if (moved != target) onMove(moved, target)
    }

    /** Where the held row's centre is now decides its slot: past a neighbour's midpoint it takes that neighbour's place. */
    private fun retarget() {
        val centre = tops[from] + heights[from] / 2f + travel + (scroll.value - scroll0)
        var at = from
        while (at + 1 < count && centre > middle(at + 1)) at++
        if (at == from) while (at > 0 && centre < middle(at - 1)) at--
        if (at == to) return
        to = at
        shiftOthers()
    }

    private fun middle(index: Int): Float = tops[index] + heights[index] / 2f

    /** Each row between the held row's slot and its target moves by the held row's height; the rest go back. */
    private fun shiftOthers() {
        val gap = heights[from]
        for (i in 0 until count) {
            if (i == from) continue
            val target = when {
                i in from + 1..to -> -gap
                i in to until from -> gap
                else -> 0f
            }
            if (target == shiftTo[i]) continue
            shiftTo[i] = target
            shiftJobs[i]?.cancel()
            val shift = shifts[i]
            shiftJobs[i] = scope.launch { animate(shift.floatValue, target, 0f, motion.settle(RvMotion.MOVE_MS, PIXEL)) { v, _ -> shift.floatValue = v } }
        }
    }

    /** The held row's offset from its own slot to the slot it lands in. */
    private fun slot(): Float = when {
        to > from -> tops[to] + heights[to] - heights[from] - tops[from]
        to < from -> tops[to] - tops[from]
        else -> 0f
    }

    /** Scrolls the page each frame while the finger is in an edge band; the held row stays under it. */
    private suspend fun autoScroll() {
        var last = withFrameNanos { it }
        while (phase == Phase.Held) {
            val now = withFrameNanos { it }
            val speed = edgeSpeed()
            if (speed == 0f) return
            val before = scroll.value
            scroll.dispatchRawDelta(speed * (now - last) / NS_PER_SECOND)
            last = now
            if (scroll.value == before) return
            retarget()
        }
    }

    /** Pixels per second: negative up in the top band, positive down in the bottom one, rising with the depth into it. */
    private fun edgeSpeed(): Float {
        val view = viewport?.takeIf { it.isAttached } ?: return 0f
        val height = view.size.height.toFloat()
        val y = finger - view.localToRoot(Offset.Zero).y
        val band = px.edge.coerceAtMost(height / 2f)
        return when {
            y < band -> -px.speed * ((band - y) / band).coerceAtMost(1f)
            y > height - band -> px.speed * ((y - height + band) / band).coerceAtMost(1f)
            else -> 0f
        }
    }

    private fun rootY(index: Int, y: Float): Float {
        val at = places.getOrNull(index)?.takeIf { it.isAttached } ?: return y
        return at.localToRoot(Offset(0f, y)).y
    }

    private enum class Phase { Idle, Held, Landing }

    private companion object {
        const val NONE = -1
        const val GESTURE = "reorder"
        const val PIXEL = 0.5f
        const val NS_PER_SECOND = 1_000_000_000f
    }
}

/** The reorder's distances in pixels: the [edge] band that scrolls and the [speed] per second at the very edge. */
internal class RvReorderPx(val edge: Float, val speed: Float)

/** The reorder of [count] rows on the page that [scroll] scrolls; [onMove] moves one in the page's list. */
@Composable
internal fun rememberRvReorderState(scroll: ScrollState, count: Int, onMove: (from: Int, to: Int) -> Unit): RvReorderState {
    val motion = Rv.motion
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val px = with(LocalDensity.current) { RvReorderPx(RvMotion.reorderEdge.toPx(), RvMotion.reorderScroll.toPx()) }
    val state = remember(scroll, motion, haptic, px.edge) { RvReorderState(scroll, scope, motion, haptic, px) }
    state.onMove = onMove
    state.resize(count)
    return state
}

/** On the scrolling container, before its `verticalScroll`: its viewport is where the edges that scroll are. */
internal fun Modifier.rvReorderViewport(state: RvReorderState): Modifier = onPlaced { state.viewport = it }

/**
 * Makes the row at [index] reorderable (see [RvReorderState]). [actions] offers 上移 and 下移 to TalkBack; a page that
 * gives the row a long-press menu puts [rvReorderMenu] in it instead and passes false, since the menu offers its items.
 * Such a row takes no `onLongClick` of its own: a long press lifts it, and [onHold] runs when it is let go where it
 * was, without moving, which is where that page opens the menu.
 */
@Composable
internal fun Modifier.rvReorder(state: RvReorderState, index: Int, actions: Boolean = true, onHold: (() -> Unit)? = null): Modifier {
    val items = if (actions) rvReorderMenu(state, index) else emptyList()
    val hold by rememberUpdatedState(onHold)
    val shape = remember { RoundedCornerShape(RvSize.cardRadius) }
    val lifted = if (state.lifted(index)) {
        Modifier.rvSoftShadow(shape, RvChrome.navShadow, RvChrome.navShadowDrop, RvChrome.NAV_SHADOW_ALPHA).clip(shape).background(Rv.colors.raised)
    } else {
        Modifier
    }
    val announce = if (items.isEmpty()) {
        Modifier
    } else {
        Modifier.semantics {
            customActions = items.map { item ->
                CustomAccessibilityAction(item.label) {
                    item.onClick()
                    true
                }
            }
        }
    }
    return onPlaced { state.place(index, it) }
        .pointerInput(state, index) { reorder(state, index) { hold?.invoke() } }
        .then(announce)
        .zIndex(if (state.moving(index)) LIFTED_Z else 0f)
        .graphicsLayer { translationY = state.offset(index) }
        .then(lifted)
}

/** 上移 and 下移 for the row at [index] (none past either end), for TalkBack and for a row's long-press menu. */
@Composable
internal fun rvReorderMenu(state: RvReorderState, index: Int): List<RvMenuItem> {
    val up = stringResource(R.string.rv_reorder_up)
    val down = stringResource(R.string.rv_reorder_down)
    return buildList {
        if (index > 0) add(RvMenuItem(R.drawable.rv_sym_arrow_upward, up) { state.step(index, -1) })
        if (index < state.count - 1) add(RvMenuItem(R.drawable.rv_sym_arrow_downward, down) { state.step(index, 1) })
    }
}

/** One touch on a row: a catch of a landing row, or a long press that lifts this one; then the finger carries it. */
private suspend fun PointerInputScope.reorder(state: RvReorderState, index: Int, hold: () -> Unit) = awaitEachGesture {
    val down = awaitFirstDown(requireUnconsumed = false)
    val caught = state.catch(index, down.position.y, down.uptimeMillis)
    if (!caught) {
        if (!state.idle) return@awaitEachGesture
        var at = down
        val wait = maxOf(viewConfiguration.longPressTimeoutMillis, RvMotion.LONG_PRESS_MS)
        val ended = withTimeoutOrNull(wait) { awaitHoldEnd(down, viewConfiguration.touchSlop) { at = it } }
        if (ended != null || !state.lift(index, at.position.y, at.uptimeMillis)) return@awaitEachGesture
    }
    val still = try {
        follow(state, index, down.id)
        state.release(viewConfiguration.touchSlop)
    } finally {
        // A gesture cancelled while it holds the row still lets it land; after a release this does nothing.
        state.release(viewConfiguration.touchSlop)
    }
    if (still && !caught) hold()
}

/** Returns when the finger lifts or moves past [slop] before the long press; [last] keeps its latest position. */
private suspend fun AwaitPointerEventScope.awaitHoldEnd(down: PointerInputChange, slop: Float, last: (PointerInputChange) -> Unit) {
    while (true) {
        val change = awaitPointerEvent().changes.fastFirstOrNull { it.id == down.id } ?: return
        if (!change.pressed || (change.position - down.position).getDistance() > slop) return
        last(change)
    }
}

/**
 * Carries the held row with finger [id] until it lifts. It takes every change first (the initial pass), so the page
 * does not scroll under the finger and the row's own click does not fire on the release.
 */
private suspend fun AwaitPointerEventScope.follow(state: RvReorderState, index: Int, id: PointerId) {
    while (true) {
        val change = awaitPointerEvent(PointerEventPass.Initial).changes.fastFirstOrNull { it.id == id } ?: return
        change.consume()
        if (!change.pressed) return
        state.drag(index, change.position.y, change.uptimeMillis)
    }
}

/** The held or landing row draws over its neighbours. */
private const val LIFTED_Z = 1f
