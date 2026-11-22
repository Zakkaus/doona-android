package dev.doona.android.prototype.rv

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.SeekableTransitionState
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** How a tab switch looks: T1 a hard cut with the hero art fading in (the default), T2 a cross-fade of the whole page. */
internal enum class RvTabMotion(val code: String) { Cut("T1"), Fade("T2") }

/** How a page is pushed and popped: S1 a cross-fade with a slight zoom (the default), S2 Material's shared axis X. */
internal enum class RvPushMotion(val code: String) { Zoom("S1"), Axis("S2") }

/**
 * How 活動's pull-down opens the node picker: P1 the picker slides down as one sheet over 活動, which dims (the default);
 * P2 the hero stretches with the finger, then the picker cross-fades in; P3 活動 slides down out of view and the picker's
 * sections drop in one after another.
 */
internal enum class RvPullMotion(val code: String) { Sheet("P1"), Stretch("P2"), Drop("P3") }

/** The motion options the owner compares; the defaults are P1, T1 and S1. */
@Immutable
internal data class RvMotionOptions(val pull: RvPullMotion = RvPullMotion.Sheet, val tab: RvTabMotion = RvTabMotion.Cut, val push: RvPushMotion = RvPushMotion.Zoom)

/** True on a page a hard-cut tab switch is showing: its hero art fades in ([rvHeroArt]). */
internal val LocalRvTabEntry = staticCompositionLocalOf { false }

/** The hero art's alpha: from 0 up over [RvMotion.HERO_FADE_MS] on a page a hard-cut tab switch just showed, else 1. */
@Composable
internal fun rvHeroArt(): () -> Float {
    val motion = Rv.motion
    val entering = LocalRvTabEntry.current && !motion.instant
    val alpha = remember { Animatable(if (entering) 0f else 1f) }
    LaunchedEffect(alpha) { alpha.animateTo(1f, motion.tween(RvMotion.HERO_FADE_MS, LinearEasing)) }
    return { alpha.value }
}

/**
 * The sample's pages: [stack] (routes, the tab home first, the page on top last), each drawn by [page] with its saved
 * state kept while it is on the stack. A push or a pop moves as [RvMotionOptions.push] says, a new tab home as
 * [RvMotionOptions.tab] says. Android's predictive back seeks the pop with the gesture, so the page follows the finger;
 * releasing past the system's threshold calls [onBack] and the pop runs on from there, and a cancelled gesture runs the
 * pop back to the page and leaves the stack as it was.
 */
@Composable
internal fun RvStackHost(stack: List<String>, onBack: () -> Unit, options: RvMotionOptions, modifier: Modifier = Modifier, page: @Composable (String) -> Unit) {
    val motion = Rv.motion
    val holder = rememberSaveableStateHolder()
    val state = remember { SeekableTransitionState(stack) }
    val transition = rememberTransition(state, label = "stack")
    var back by remember { mutableStateOf<Float?>(null) }
    // Every route drawn so far, and the tab homes among them: a pushed page off the stack starts afresh next time.
    val seen = remember { mutableSetOf<String>() }
    val homes = remember { mutableSetOf<String>() }
    PredictiveBackHandler(enabled = stack.size > 1) { events ->
        try {
            events.collect { back = it.progress }
            back = null
            onBack()
        } catch (e: CancellationException) {
            back = null
            throw e
        }
    }
    val progress = back
    if (progress != null) {
        LaunchedEffect(progress) { state.seekTo(progress, stack.dropLast(1)) }
    } else {
        LaunchedEffect(stack) {
            if (state.currentState != stack) {
                state.animateTo(stack)
            } else if (state.targetState != stack) {
                // A cancelled back gesture: the pop runs back from where the finger left it, then the page is at rest.
                if (!motion.instant) {
                    val ms = (state.fraction * popMs(options.push)).roundToInt()
                    Animatable(state.fraction).animateTo(0f, tween(ms, easing = LinearEasing)) { launch { state.seekTo(value) } }
                }
                state.snapTo(stack)
            }
            homes += stack.first()
            (seen - stack.toSet() - homes).forEach {
                holder.removeState(it)
                seen -= it
            }
        }
    }
    val shift = with(LocalDensity.current) { RvMotion.axisShift.roundToPx() }
    SharedTransitionLayout(modifier) {
        transition.AnimatedContent(transitionSpec = { move(options, motion, shift) }, contentKey = { it.last() }) { shown ->
            seen += shown.last()
            val entering = options.tab == RvTabMotion.Cut && shown == transition.targetState && transition.currentState.first() != shown.first()
            holder.SaveableStateProvider(shown.last()) {
                CompositionLocalProvider(LocalRvTabEntry provides entering, LocalRvShared provides RvShared(this@SharedTransitionLayout, this)) { page(shown.last()) }
            }
        }
    }
}

/** The host's shared-element scopes: the [layout] over every page and the [page]'s own coming and going. */
internal class RvShared(val layout: SharedTransitionScope, val page: AnimatedVisibilityScope)

internal val LocalRvShared = compositionLocalOf<RvShared?> { null }

/**
 * Makes the search pill and the search page's field one element (brief rv-gestures G7): with the push it moves and
 * reshapes from one into the other in [RvMotion.SEARCH_MS], its content laid out afresh at each size, clipped to the
 * pill's shape. Outside the host (goldens, previews) it does nothing.
 */
@Composable
internal fun Modifier.rvSharedSearch(): Modifier {
    val shared = LocalRvShared.current ?: return this
    val motion = Rv.motion
    return with(shared.layout) {
        this@rvSharedSearch.sharedBounds(
            rememberSharedContentState(SEARCH),
            shared.page,
            // The pill's and the field's own content cross-fade as the bounds move, in the same time.
            enter = fadeIn(motion.tween(RvMotion.SEARCH_MS)),
            exit = fadeOut(motion.tween(RvMotion.SEARCH_MS)),
            boundsTransform = { _, _ -> motion.tween(RvMotion.SEARCH_MS) },
            resizeMode = SharedTransitionScope.ResizeMode.RemeasureToBounds,
            clipInOverlayDuringTransition = OverlayClip(CircleShape),
        )
    }
}

/** Whether the page this is in has finished coming in (at once outside the host). */
@Composable
internal fun rvPageArrived(): () -> Boolean {
    val page = LocalRvShared.current?.page ?: return { true }
    return { page.transition.currentState == EnterExitState.Visible && page.transition.targetState == EnterExitState.Visible }
}

private fun popMs(push: RvPushMotion) = if (push == RvPushMotion.Zoom) RvMotion.PUSH_MS else RvMotion.AXIS_MS

private fun AnimatedContentTransitionScope<List<String>>.move(options: RvMotionOptions, motion: RvMotionScale, shift: Int): ContentTransform {
    val from = initialState
    val to = targetState
    return when {
        motion.instant -> EnterTransition.None togetherWith ExitTransition.None
        from.first() != to.first() -> tabSwitch(options.tab, motion)
        to.last() == SEARCH || from.last() == SEARCH -> search(motion, shift, forward = to.size > from.size)
        options.push == RvPushMotion.Zoom -> zoom(motion, forward = to.size > from.size)
        else -> axis(motion, shift, forward = to.size > from.size)
    }
}

private fun tabSwitch(tab: RvTabMotion, motion: RvMotionScale): ContentTransform = when (tab) {
    RvTabMotion.Cut -> EnterTransition.None togetherWith ExitTransition.None
    RvTabMotion.Fade -> fadeIn(motion.tween(RvMotion.FADE_MS)) togetherWith fadeOut(motion.tween(RvMotion.FADE_MS))
}

/**
 * The search page (G7), whichever push option is set: it fades in rising [shift] px while the pill becomes its field,
 * in [RvMotion.SEARCH_MS]; on the way back it sinks and fades out over the page it came from.
 */
private fun search(motion: RvMotionScale, shift: Int, forward: Boolean): ContentTransform {
    val ms = RvMotion.SEARCH_MS
    return if (forward) {
        (fadeIn(motion.tween(ms)) + slideInVertically(motion.tween(ms)) { shift }) togetherWith fadeOut(motion.tween(ms))
    } else {
        ContentTransform(fadeIn(motion.tween(ms)), fadeOut(motion.tween(ms)) + slideOutVertically(motion.tween(ms)) { shift }, targetContentZIndex = -1f)
    }
}

/** S1: the new page fades in growing from 96 %; on a pop the leaving page, on top, fades out shrinking to 96 %. */
private fun zoom(motion: RvMotionScale, forward: Boolean): ContentTransform {
    val ms = RvMotion.PUSH_MS
    return if (forward) {
        (fadeIn(motion.tween(ms)) + scaleIn(motion.tween(ms), initialScale = RvMotion.PUSH_ZOOM)) togetherWith fadeOut(motion.tween(ms))
    } else {
        ContentTransform(fadeIn(motion.tween(ms)), fadeOut(motion.tween(ms)) + scaleOut(motion.tween(ms), targetScale = RvMotion.PUSH_ZOOM), targetContentZIndex = -1f)
    }
}

/** S2, Material's shared axis X: both pages travel [shift] px the same way; the old fades out first, then the new fades in. */
private fun axis(motion: RvMotionScale, shift: Int, forward: Boolean): ContentTransform {
    val ms = RvMotion.AXIS_MS
    val out = (ms * RvMotion.AXIS_FADE).roundToInt()
    val sign = if (forward) 1 else -1
    val enter = slideInHorizontally(motion.tween(ms)) { sign * shift } + fadeIn(motion.tween(ms - out, delay = out))
    val exit = slideOutHorizontally(motion.tween(ms)) { -sign * shift } + fadeOut(motion.tween(out))
    return enter togetherWith exit
}
