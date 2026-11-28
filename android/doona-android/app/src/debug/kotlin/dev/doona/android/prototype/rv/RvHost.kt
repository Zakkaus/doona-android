package dev.doona.android.prototype.rv

import android.os.Trace
import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.SeekableTransitionState
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import dev.doona.android.app.RvPushMotion
import dev.doona.android.app.RvTabMotion
import dev.doona.android.app.rvAxisMove
import dev.doona.android.app.rvBackPreview
import dev.doona.android.app.rvPopMs
import dev.doona.android.app.rvSearchMove
import dev.doona.android.app.rvTabSwitch
import dev.doona.android.app.rvZoomMove
import dev.doona.android.designsystem.rv.LocalRvShared
import dev.doona.android.designsystem.rv.LocalRvTabEntry
import dev.doona.android.designsystem.rv.Rv
import dev.doona.android.designsystem.rv.RvFrames
import dev.doona.android.designsystem.rv.RvHeroSwipe
import dev.doona.android.designsystem.rv.RvMotion
import dev.doona.android.designsystem.rv.RvMotionScale
import dev.doona.android.designsystem.rv.RvPullMotion
import dev.doona.android.designsystem.rv.RvShared
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Android's back from a tab home: on 策略, 連線 or 設定 with nothing stacked on it, back goes to the [home] tab (活動); on
 * [home] it is left to the system, which leaves the app. A page stacked on a tab, or a page that is no tab, pops as before.
 */
@Composable
internal fun RvBackToHome(stack: List<String>, home: String, tabs: Set<String>, onHome: () -> Unit) {
    BackHandler(enabled = stack.size == 1 && stack[0] != home && stack[0] in tabs, onBack = onHome)
}

/** The motion options the owner compares; the defaults are P1, T1, S1 and H0. */
@Immutable
internal data class RvMotionOptions(
    val pull: RvPullMotion = RvPullMotion.Sheet,
    val tab: RvTabMotion = RvTabMotion.Cut,
    val push: RvPushMotion = RvPushMotion.Zoom,
    val hero: RvHeroSwipe = RvHeroSwipe.Off,
)

/**
 * The sample's pages: [stack] (routes, the tab home first, the page on top last), each drawn by [page] with its saved
 * state kept while it is on the stack. A push or a pop moves as [RvMotionOptions.push] says, a new tab home as
 * [RvMotionOptions.tab] says. Android's predictive back seeks [rvBackPreview] with the gesture, so the page follows the finger;
 * releasing past the system's threshold calls [onBack] and the pop runs on from there, and a cancelled gesture runs the
 * pop back to the page and leaves the stack as it was.
 */
@Composable
internal fun RvStackHost(stack: List<String>, onBack: () -> Unit, options: RvMotionOptions, modifier: Modifier = Modifier, page: @Composable (String) -> Unit) {
    val motion = Rv.motion
    val holder = rememberSaveableStateHolder()
    val state = remember { SeekableTransitionState(stack) }
    val transition = rememberTransition(state, label = "stack")
    // Whether a back gesture is under way; its progress is not state: each event seeks the transition directly, so the host composes nothing for it.
    var seeking by remember { mutableStateOf(false) }
    // The edge of the back gesture being previewed or committed, which side the page moves away to; null on any other move.
    var backEdge by remember { mutableStateOf<Int?>(null) }
    // Every route drawn so far, and the tab homes among them: a pushed page off the stack starts afresh next time.
    val seen = remember { mutableSetOf<String>() }
    val homes = remember { mutableSetOf<String>() }
    PredictiveBackHandler(enabled = stack.size > 1) { events ->
        try {
            seeking = true
            events.collect {
                backEdge = it.swipeEdge
                state.seekTo(it.progress, stack.dropLast(1))
            }
            // The pop runs on with the preview's move until it lands ([backEdge] is cleared there).
            seeking = false
            onBack()
        } catch (e: CancellationException) {
            seeking = false
            throw e
        }
    }
    if (!seeking) {
        LaunchedEffect(stack) {
            if (state.currentState != stack) {
                val gesture = stackGesture(state.currentState, stack)
                RvFrames.begin(gesture)
                try {
                    state.animateTo(stack)
                } finally {
                    RvFrames.end(gesture)
                    backEdge = null
                }
            } else if (state.targetState != stack) {
                // A cancelled back gesture: the pop runs back from where the finger left it, then the page is at rest.
                if (!motion.instant) {
                    val ms = (state.fraction * RvMotion.BACK_MS).roundToInt()
                    Animatable(state.fraction).animateTo(0f, tween(ms, easing = LinearEasing)) { launch { state.seekTo(value) } }
                }
                state.snapTo(stack)
                backEdge = null
            }
            homes += stack.first()
            (seen - stack.toSet() - homes).forEach {
                holder.removeState(it)
                seen -= it
            }
        }
    }
    val shift = with(LocalDensity.current) { RvMotion.axisShift.roundToPx() }
    val margin = with(LocalDensity.current) { RvMotion.backMargin.roundToPx() }
    SharedTransitionLayout(modifier) {
        transition.AnimatedContent(transitionSpec = { backEdge?.let { back(options, motion, shift, margin, it) } ?: move(options, motion, shift) }, contentKey = { it.last() }) { shown ->
            seen += shown.last()
            val entering = options.tab == RvTabMotion.Cut && shown == transition.targetState && transition.currentState.first() != shown.first()
            holder.SaveableStateProvider(shown.last()) {
                val shared = RvShared(this@SharedTransitionLayout, this, rvPopMs(options.push))
                CompositionLocalProvider(LocalRvTabEntry provides entering, LocalRvShared provides shared) {
                    // A page's composition is a section of its own in a Perfetto trace.
                    Trace.beginSection(TRACE_PAGE)
                    page(shown.last())
                    Trace.endSection()
                }
            }
        }
    }
}

private fun AnimatedContentTransitionScope<List<String>>.move(options: RvMotionOptions, motion: RvMotionScale, shift: Int): ContentTransform {
    val from = initialState
    val to = targetState
    return when {
        motion.instant -> EnterTransition.None togetherWith ExitTransition.None
        from.first() != to.first() -> rvTabSwitch(options.tab, motion)
        to.last() == SEARCH || from.last() == SEARCH -> rvSearchMove(motion, shift, forward = to.size > from.size)
        options.push == RvPushMotion.Zoom -> rvZoomMove(motion, forward = to.size > from.size)
        else -> rvAxisMove(motion, shift, forward = to.size > from.size)
    }
}

/**
 * A predictive back from [edge]: the search page sinks as on a plain back; under [RvPushMotion.Axis] the pop is the
 * shared axis seeked; every other page shows [rvBackPreview].
 */
private fun AnimatedContentTransitionScope<List<String>>.back(options: RvMotionOptions, motion: RvMotionScale, shift: Int, margin: Int, edge: Int): ContentTransform = when {
    motion.instant -> EnterTransition.None togetherWith ExitTransition.None
    initialState.last() == SEARCH -> rvSearchMove(motion, shift, forward = false)
    options.push == RvPushMotion.Axis -> rvAxisMove(motion, shift, forward = false)
    else -> rvBackPreview(motion, margin, edge)
}

private const val TRACE_PAGE = "RvPage"

/** [RvFrames]' name for the move from [from] to [to]: `tab`, or `push-` or `pop-` and the route of the page that moves. */
private fun stackGesture(from: List<String>, to: List<String>): String = when {
    from.first() != to.first() -> "tab"
    to.size > from.size -> "push-" + to.last().substringBefore(':')
    else -> "pop-" + from.last().substringBefore(':')
}
