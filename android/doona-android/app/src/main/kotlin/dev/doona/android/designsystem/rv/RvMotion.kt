package dev.doona.android.designsystem.rv

import android.provider.Settings
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.FrameRateCategory
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.platform.InfiniteAnimationPolicy
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.preferredFrameRate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.round
import dev.doona.android.R
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Every duration and easing of the sample's motion, in one place (brief rv-motion). Revolut's measured values where there
 * are any: press about 60 ms, nav collapse about 130 ms, push about 130 ms, the hero art's fade 330 ms after a hard-cut
 * tab switch, the pull-down's reveal about 700 ms and return about 400 ms, decelerating with no overshoot, a long press
 * at about 500 ms. Every animation reads them through [RvMotionScale], which makes each instant at animator scale 0.
 */
internal object RvMotion {
    /** The state layer under a finger comes up, and goes, in about 60 ms (measured). */
    const val PRESS_MS = 60

    /** A round disc under a finger grows by 4 % as it lightens, in [PRESS_MS] (measured: slightly). */
    const val DISC_GROW = 0.04f

    /** The floating nav collapsing or expanding (measured about 130 ms). */
    const val NAV_MS = 130

    /** S1 push and pop: a cross-fade with the entering page growing from 96 % (measured about 130 ms). */
    const val PUSH_MS = 130
    const val PUSH_ZOOM = 0.96f

    /** T2's tab cross-fade; also the short cross-fade of a value or a state that changes (status, rates, a result). */
    const val FADE_MS = 150

    /** S2, Material's shared axis X: 30 dp of travel, the old page gone in the first 35 %, the new one in the rest. */
    const val AXIS_MS = 250
    const val AXIS_FADE = 0.35f
    val axisShift = 30.dp

    /** The segmented control's pill sliding to the chosen segment (as long as S2's shared axis); H1's hero turning to its other view. */
    const val SLIDE_MS = 250

    /**
     * The search page (measured): it follows the search pill, which becomes its field, in 270 ms; the keyboard rises
     * only after that, with the IME's own animation.
     */
    const val SEARCH_MS = 270

    /** A tab's hero art fades in after the hard cut (measured 330 ms); a theme change cross-fades as long. */
    const val HERO_FADE_MS = 330

    /**
     * A long-press menu (as Revolut's More popup) scales from [MENU_FROM] (Material's menu's) and fades in, from where it
     * was asked for, in about 267 ms ([Decelerate]), and goes the same way in about 233 ms ([Accelerate]); both measured.
     * [RvSheet] rises and falls in the same times.
     */
    const val MENU_MS = 267
    const val MENU_OUT_MS = 233
    const val MENU_FROM = 0.8f

    /**
     * Predictive back (Material 3's full-screen preview): while the finger moves, the page on top shrinks toward
     * [BACK_SCALE] and moves away from the edge the swipe started at by 1/[BACK_SHIFT_PARTS] of its width less [backMargin], the
     * page under it already in place; it fades only in the last [BACK_FADE] of the gesture's travel. [BACK_MS] is the
     * whole move's length, which the finger seeks; once the system commits, the rest runs on, and a cancel runs it back.
     */
    const val BACK_MS = 300
    const val BACK_SCALE = 0.9f
    const val BACK_FADE = 0.35f
    const val BACK_SHIFT_PARTS = 20
    val backMargin = 8.dp

    /**
     * A row coming or going (an import, a delete, 查看全部) grows or shrinks in [ITEM_MS], [STAGGER_MS] apart ([STAGGER_ROWS]
     * at most); a leaving row's words are gone in [ITEM_OUT_MS], before an arriving row's fade in over [FADE_MS] (RvItems).
     */
    const val ITEM_MS = 300
    const val ITEM_OUT_MS = 75
    const val STAGGER_ROWS = 4

    /** A live rate glides to each new average of it (time constant [GLIDE_TAU_MS]) in [GLIDE_MS] ([rvGlidingRate]). */
    const val GLIDE_MS = 700
    const val GLIDE_TAU_MS = 2_000.0

    /** A drag dismisses a sheet past this share of its height, or when it lets go faster than [sheetFling] a second. */
    const val SHEET_DISMISS_SHARE = 0.33f
    val sheetFling = 1_000.dp

    /** Pull-down: the reveal (measured about 700 ms) and the way back (about 400 ms). */
    const val REVEAL_MS = 700
    const val RETURN_MS = 400

    /** A pull let go short of its threshold springs back in about 167 ms (measured); so does H1's hero swipe. */
    const val SNAP_MS = 167

    /** A row moving to its new place, a card opening in place, the map panning to a new node: the return's 400 ms. */
    const val MOVE_MS = 400

    /**
     * Tapping the tab already shown glides its page back to the top, decelerating, as long as the return of a pull:
     * quick from anywhere, and the page's top settles into place rather than arriving at speed (G6).
     */
    const val TOP_MS = 400

    /** One section, or one latency result, after another. */
    const val STAGGER_MS = 30

    /** A live sample arrives every second, and a live chart glides one step in that second. */
    const val SAMPLE_MS = 1_000

    /** While connected, the map marker's ring runs outward to its glow once in this period. */
    const val PULSE_MS = 1_600

    /** An indeterminate progress bar fills across once in this period; without motion it rests at [BUSY_STILL]. */
    const val BUSY_MS = 1_000
    const val BUSY_STILL = 0.5f

    /** The old shell's busy spinner turns once in this period. */
    const val SPINNER_MS = 1_000

    /** A long press opens after about 500 ms (measured), or later if the user set a longer delay. */
    const val LONG_PRESS_MS = 500L

    /**
     * The pull commits past [pullCommit], or on a downward fling of [pullFling] per second once past [pullDead], the
     * travel before anything shows (measured 16 dp). Past the commit distance the pull meets resistance and runs out
     * [pullGive] further at most, however far the finger goes. H1's hero swipe takes the same fling and rubber band sideways.
     */
    val pullCommit = 96.dp
    val pullFling = 1_000.dp
    val pullDead = 16.dp
    val pullGive = 96.dp

    /**
     * Revolut's pull: as the finger travels from [pullDead] to [pullCommit], 活動 blurs (a fixed [pullBlur] cross-faded
     * in, the radius never animated), fades toward the page by [PULL_FADE] and the search pill, the picker's anchor,
     * grows by [ANCHOR_GROW] (measured 1.00 to 1.03).
     */
    val pullBlur = 12.dp
    const val PULL_FADE = 0.3f
    const val ANCHOR_GROW = 0.03f

    /**
     * A held row (G4's reorder) scrolls the page while the finger is within [reorderEdge] of the viewport's top or
     * bottom, faster the deeper it goes, up to [reorderScroll] per second at the very edge.
     */
    val reorderEdge = 64.dp
    val reorderScroll = 1_200.dp

    /** A spring moving something in pixels is at rest within half a pixel. */
    const val REST_PX = 0.5f

    /** A critically damped spring is within 1 % of its travel after this many radians (the root of (1 + x)e^-x = 0.01). */
    const val SETTLED_RADIANS = 6.64f

    /** P1: how dark 活動 gets under the open sheet (Material's scrim). P3: how far each section drops from. */
    const val DIM = 0.32f
    val drop = 24.dp

    /** The pull-down's curve: decelerating to rest, no overshoot (Material's standard decelerate). */
    val Decelerate: Easing = CubicBezierEasing(0f, 0f, 0f, 1f)

    /** What leaves: accelerating away (the menu's and the sheet's close). */
    val Accelerate: Easing = CubicBezierEasing(0.3f, 0f, 1f, 1f)
    val Standard: Easing = FastOutSlowInEasing

    /** What arrives and settles (a row coming in): Material 3's emphasized decelerate. */
    val Emphasized: Easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
}

/**
 * Material 3's spring speeds (md.sys.motion.spring, the standard scheme's stiffness): [spatial] for what moves or
 * changes size, [effects] for colour and opacity. Fast for small parts (a switch, a button), Default for what covers
 * part of the screen (a sheet), Slow for the whole screen. Every spring here is critically damped: Revolut overshoots
 * nowhere (UX2, "no overshoot anywhere").
 */
internal enum class RvSpring(val spatial: Float, val effects: Float) {
    Fast(1_400f, 3_800f),
    Default(700f, 1_600f),
    Slow(300f, 800f),
}

/**
 * The animator duration scale the user set: at 0 (Remove animations, or Developer options) [instant] is true and every
 * spec from [tween] snaps. Other scales need nothing here: Compose already stretches every animation by the scale.
 */
@Immutable
internal class RvMotionScale(val instant: Boolean) {
    fun <T> tween(ms: Int, easing: Easing = RvMotion.Standard, delay: Int = 0): FiniteAnimationSpec<T> =
        if (instant) snap() else androidx.compose.animation.core.tween(ms, delay, easing)

    /**
     * A gesture's settling: a spring with damping ratio 1 (no overshoot) stiff enough to come within 1 % of its travel
     * in about [ms], so it takes the finger's velocity and still lands in the measured time; [threshold] is where it
     * counts as at rest (half a pixel, or a thousandth of a fraction).
     */
    fun settle(ms: Int, threshold: Float): FiniteAnimationSpec<Float> {
        if (instant) return snap()
        val omega = RvMotion.SETTLED_RADIANS * MS_PER_SECOND / ms
        return spring(Spring.DampingRatioNoBouncy, omega * omega, threshold)
    }

    /** [RvSpring]'s spring for something that moves or changes size. */
    fun <T> spatial(speed: RvSpring, threshold: T? = null): FiniteAnimationSpec<T> = if (instant) snap() else spring(Spring.DampingRatioNoBouncy, speed.spatial, threshold)

    /** [RvSpring]'s spring for a colour or an opacity. */
    fun <T> effects(speed: RvSpring): FiniteAnimationSpec<T> = if (instant) snap() else spring(Spring.DampingRatioNoBouncy, speed.effects)

    private companion object {
        const val MS_PER_SECOND = 1_000f
    }
}

/** The scale as the system has it now. */
@Composable
internal fun rememberRvMotion(): RvMotionScale {
    val resolver = LocalContext.current.contentResolver
    return remember(resolver) { RvMotionScale(Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f) }
}

/**
 * The one press feedback: the content colour's state layer over the visible shape, up in [RvMotion.PRESS_MS]. [shape] is
 * the visible shape the focus outline follows.
 */
@Composable
internal fun rvPressIndication(shape: Shape): IndicationNodeFactory =
    RvPressIndication(Rv.colors.stateLayer, Rv.colors.positive, Rv.motion.instant, inset = null, radius = Rv.highlightRadius, shape = shape, layer = true)

/**
 * A row's press, the same layer as Revolut highlights a row (revolut-ux3 §1, §2): inset [RvSpace.tight] in the row, its
 * corners [Rv.highlightRadius], so inside a card it follows the card's corners. Its focus outline follows the same shape.
 */
@Composable
internal fun rvRowIndication(): IndicationNodeFactory =
    RvPressIndication(Rv.colors.stateLayer, Rv.colors.positive, Rv.motion.instant, inset = RvSpace.tight, radius = Rv.highlightRadius, shape = RoundedCornerShape(Rv.highlightRadius), layer = true)

/**
 * Only the focus outline of [rvPressIndication], for a control that draws its own press (the primary button, a disc):
 * every clickable of the library shows focus the same way (q-a11y-focus).
 */
@Composable
internal fun rvFocusIndication(shape: Shape): IndicationNodeFactory =
    RvPressIndication(Rv.colors.stateLayer, Rv.colors.positive, Rv.motion.instant, inset = null, radius = Rv.highlightRadius, shape = shape, layer = false)

private data class RvPressIndication(
    val color: Color,
    val focus: Color,
    val instant: Boolean,
    val inset: Dp?,
    val radius: Dp,
    val shape: Shape,
    val layer: Boolean,
) : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode = PressNode(interactionSource, this)
}

/**
 * Shows the layer while any press lasts (a short tap still shows it fully before it goes), and, over it, a
 * [RvChrome.focusRing] outline in [RvPressIndication.focus] while the clickable has focus from a keyboard or a D-pad.
 * Compose gives a clickable focus only outside touch mode, so a tap never draws it; the input mode is checked as well.
 * Switch Access moves accessibility focus, which emits no FocusInteraction, so it draws no outline here; the system draws
 * its own switch highlight.
 */
private class PressNode(private val source: InteractionSource, private val spec: RvPressIndication) :
    Modifier.Node(),
    DrawModifierNode,
    CompositionLocalConsumerModifierNode {
    private val shown = Animatable(0f)
    private var focused = false

    override fun onAttach() {
        if (spec.layer) coroutineScope.launch { source.rvFollowPress(::to) }
        coroutineScope.launch {
            source.interactions.collect { interaction ->
                val now = when (interaction) {
                    is FocusInteraction.Focus -> true
                    is FocusInteraction.Unfocus -> false
                    else -> focused
                }
                if (now != focused) {
                    focused = now
                    invalidateDraw()
                }
            }
        }
    }

    private suspend fun to(target: Float) {
        if (spec.instant) shown.snapTo(target) else shown.animateTo(target, tween(RvMotion.PRESS_MS, easing = LinearEasing)) { invalidateDraw() }
        invalidateDraw()
    }

    override fun ContentDrawScope.draw() {
        drawContent()
        val a = shown.value
        if (a > 0f) {
            if (spec.inset == null) {
                drawRect(spec.color, alpha = RvAlpha.PRESSED * a)
            } else {
                val i = spec.inset.toPx()
                val box = Size((size.width - 2 * i).coerceAtLeast(0f), (size.height - 2 * i).coerceAtLeast(0f))
                drawRoundRect(spec.color, Offset(i, i), box, CornerRadius(spec.radius.toPx()), alpha = RvAlpha.PRESSED * a)
            }
        }
        if (focused && currentValueOf(LocalInputModeManager).inputMode == InputMode.Keyboard) drawFocusRing()
    }

    /** The outline lies inside the visible shape, [RvChrome.focusRing] wide, so a clip round the clickable never trims it. */
    private fun DrawScope.drawFocusRing() {
        val i = spec.inset?.toPx() ?: 0f
        val box = Size((size.width - 2 * i).coerceAtLeast(0f), (size.height - 2 * i).coerceAtLeast(0f))
        val outline = spec.shape.createOutline(box, layoutDirection, this)
        translate(i, i) {
            clipPath(outline.rvPath()) { drawOutline(outline, spec.focus, style = Stroke(RvChrome.focusRing.toPx() * 2)) }
        }
    }
}

private fun Outline.rvPath(): Path = when (this) {
    is Outline.Generic -> path
    is Outline.Rectangle -> Path().apply { addRect(rect) }
    is Outline.Rounded -> Path().apply { addRoundRect(roundRect) }
}

/**
 * How far a press on [source] is up, 0 to 1: rising and falling linearly in [RvMotion.PRESS_MS] ([rvFollowPress]), as a
 * row's state layer does. Read its value where it is drawn.
 */
@Composable
internal fun rvPressLevel(source: InteractionSource): State<Float> {
    val motion = Rv.motion
    val press = remember { Animatable(0f) }
    LaunchedEffect(source, motion) { source.rvFollowPress { press.animateTo(it, motion.tween(RvMotion.PRESS_MS, LinearEasing)) } }
    return press.asState()
}

/**
 * Runs [to] with 1 as a press on this source begins, and with 0 once the last press has ended and the rise to 1 has
 * finished, so a short tap still shows its press fully before it goes.
 */
internal suspend fun InteractionSource.rvFollowPress(to: suspend (Float) -> Unit): Unit = coroutineScope {
    var held = 0
    var rising: Job? = null
    interactions.collect { interaction ->
        when (interaction) {
            is PressInteraction.Press -> {
                held++
                rising = launch { to(1f) }
            }

            is PressInteraction.Release, is PressInteraction.Cancel -> {
                held = (held - 1).coerceAtLeast(0)
                val up = rising
                launch {
                    up?.join()
                    if (held == 0) to(0f)
                }
            }
        }
    }
}

/**
 * A live chart's glide: when [values] moved on by one sample (the window shifted left), the series is drawn [shift] steps
 * right of rest, from 1 down to 0 over [RvMotion.SAMPLE_MS], with [lead] (the sample that just left) in front, so the
 * line scrolls on instead of jumping a step each second. Any other change draws at rest. While [covered] it is drawn
 * where it was when the cover came ([drawnShift]).
 */
internal class RvGlide(val lead: Float?, val shift: Animatable<Float, AnimationVector1D>, private val covered: () -> Boolean = { false }) {
    private var held = Float.NaN

    /**
     * The steps right of rest to draw the series at: [shift], or while [covered] where it stood when the cover came, read
     * without observing the animation, so a covered page's frames redraw nothing and nothing jumps.
     */
    fun drawnShift(): Float {
        if (!covered()) {
            held = Float.NaN
            return shift.value
        }
        if (held.isNaN()) held = Snapshot.withoutReadObservation { shift.value }
        return held
    }
}

/**
 * Whether the page is covered, as 活動 is under the pull and the picker: its live charts hold their glide, so that it
 * does not change, and the blur and glass over it are not drawn again, on every frame.
 */
internal val LocalRvCovered = staticCompositionLocalOf<() -> Boolean> { { false } }

@Composable
internal fun rememberRvGlide(values: List<Float>): RvGlide {
    val instant = Rv.motion.instant
    val covered = LocalRvCovered.current
    val seen = remember { arrayOfNulls<List<Float>>(1) }
    val glide = remember(values) {
        val before = seen[0]
        seen[0] = values
        val shifted = before != null && before.size == values.size && values.size > 1 && before.subList(1, before.size) == values.subList(0, values.size - 1)
        val lead = before?.firstOrNull()?.takeIf { shifted && !instant }
        RvGlide(lead, Animatable(if (lead == null) 0f else 1f), covered)
    }
    LaunchedEffect(glide) { glide.shift.animateTo(0f, tween(RvMotion.SAMPLE_MS, easing = LinearEasing)) }
    return glide
}

/**
 * Moves the element to a new place in its parent over [RvMotion.MOVE_MS] instead of jumping, as a list re-sorted by
 * latency moves its rows (the item placement animation of a lazy list, for a plain column). Key the element by its item.
 * While not [animate] (rows above it growing or shrinking, which already move it smoothly) it follows at once.
 */
@Composable
internal fun Modifier.rvMoves(animate: Boolean = true): Modifier {
    val motion = Rv.motion
    val scope = rememberCoroutineScope()
    val offset = remember { Animatable(IntOffset.Zero, IntOffset.VectorConverter) }
    val at = remember { arrayOfNulls<IntOffset>(1) }
    return onPlaced { coordinates ->
        val now = coordinates.positionInParent().round()
        val before = at[0]
        at[0] = now
        if (before != null && before != now && animate && !motion.instant) {
            scope.launch {
                offset.snapTo(offset.value + before - now)
                offset.animateTo(IntOffset.Zero, motion.tween(RvMotion.MOVE_MS))
            }
        }
    }.offset { offset.value }
}

/** Something under way with no known end (connecting, a latency test): [RvProgress] filling across, again and again. */
@Composable
internal fun RvBusy(modifier: Modifier = Modifier) {
    val spoken = stringResource(R.string.rv_a11y_busy)
    val info = { ProgressBarRangeInfo.Indeterminate }
    if (Rv.motion.instant) return RvProgressBar({ RvMotion.BUSY_STILL }, modifier, info, spoken)
    val fill = rememberInfiniteTransition(label = "busy").animateFloat(0f, 1f, infiniteRepeatable(tween(RvMotion.BUSY_MS, easing = RvMotion.Standard), RepeatMode.Restart), label = "fill")
    // An ambient loop: a layer of its own, so a frame of it redraws the bar alone, voting the normal rate; the fill is
    // read as it is measured, so a frame composes nothing.
    RvProgressBar({ fill.value }, modifier.preferredFrameRate(FrameRateCategory.Normal).graphicsLayer {}, info, spoken)
}

/**
 * Runs [tick] every [RvMotion.SAMPLE_MS] for as long as the caller lives: a live value's clock. Like an infinite
 * animation it yields to an [InfiniteAnimationPolicy], so a test that advances its clock by itself does not spin on it.
 */
internal suspend fun rvEverySample(tick: () -> Unit) {
    val loop: suspend () -> Unit = {
        while (true) {
            delay(RvMotion.SAMPLE_MS.toLong())
            tick()
        }
    }
    currentCoroutineContext()[InfiniteAnimationPolicy]?.onInfiniteOperation(loop) ?: loop()
}

/**
 * Cross-fades [content] when [target] changes, as a theme change does: the last frame of the old look, held as a
 * picture, fades out over the new one in [RvMotion.HERO_FADE_MS]. The content is composed once, so its state stays.
 */
@Composable
internal fun <T> RvLookFade(target: T, content: @Composable (T) -> Unit) {
    // It wraps the theme, so it reads the scale itself.
    val motion = rememberRvMotion()
    var shown by remember { mutableStateOf(target) }
    var before by remember { mutableStateOf<ImageBitmap?>(null) }
    val fade = remember { Animatable(0f) }
    val layer = rememberGraphicsLayer()
    LaunchedEffect(target) {
        if (target == shown) return@LaunchedEffect
        if (!motion.instant) before = layer.toImageBitmap()
        shown = target
        fade.snapTo(1f)
        fade.animateTo(0f, motion.tween(RvMotion.HERO_FADE_MS, LinearEasing))
        before = null
    }
    Box(
        Modifier.drawWithContent {
            layer.record { this@drawWithContent.drawContent() }
            drawLayer(layer)
            before?.let { drawImage(it, alpha = fade.value) }
        },
    ) { content(shown) }
}

/**
 * Swaps [content] for [target]'s as Material's fade through does, so the old and the new are never seen at once: the old
 * fades out in the first [RvMotion.AXIS_FADE] of [RvMotion.FADE_MS] (accelerating away), then the new fades in
 * (decelerating), both at [alignment] in the room the larger takes; at animator scale 0 at once. For a state that turns
 * one thing into another in place (a glyph into a busy bar, a word into its opposite), where [androidx.compose.animation.Crossfade]
 * would lay the two over each other half seen.
 */
@Composable
internal fun <T> RvFadeThrough(target: T, label: String, modifier: Modifier = Modifier, alignment: Alignment = Alignment.Center, content: @Composable (T) -> Unit) {
    val motion = Rv.motion
    val out = (RvMotion.FADE_MS * RvMotion.AXIS_FADE).roundToInt()
    AnimatedContent(
        target,
        modifier,
        transitionSpec = {
            val enter = fadeIn(motion.tween(RvMotion.FADE_MS - out, RvMotion.Decelerate, delay = out))
            ContentTransform(enter, fadeOut(motion.tween(out, RvMotion.Accelerate)), sizeTransform = null)
        },
        contentAlignment = alignment,
        label = label,
    ) { content(it) }
}

/** Long presses inside [content] open after [RvMotion.LONG_PRESS_MS], or the user's own longer delay. */
@Composable
internal fun RvLongPressTiming(content: @Composable () -> Unit) {
    val base = LocalViewConfiguration.current
    val timing = remember(base) {
        object : ViewConfiguration by base {
            override val longPressTimeoutMillis = maxOf(base.longPressTimeoutMillis, RvMotion.LONG_PRESS_MS)
        }
    }
    CompositionLocalProvider(LocalViewConfiguration provides timing, content = content)
}
