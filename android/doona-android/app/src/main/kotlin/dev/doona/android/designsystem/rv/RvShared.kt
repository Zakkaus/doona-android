package dev.doona.android.designsystem.rv

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier

/**
 * The host's shared-element scopes: the [layout] over every page and the [page]'s own coming and going; a push or pop
 * takes [pushMs].
 */
internal class RvShared(val layout: SharedTransitionScope, val page: AnimatedVisibilityScope, val pushMs: Int)

internal val LocalRvShared = compositionLocalOf<RvShared?> { null }

/** True on a page a hard-cut tab switch is showing: its hero art fades in. The page host provides it. */
internal val LocalRvTabEntry = staticCompositionLocalOf { false }

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
            rememberSharedContentState(SEARCH_KEY),
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

/** The search pill's and field's shared key; the stack host's route to the search page has the same word. */
private const val SEARCH_KEY = "search"

/**
 * Makes a node row's flag and its page's header flag one element (rule 4): pushing the node's page, the flag moves and
 * grows from the row into the header with the push, and goes back with the pop. Only one flag per node may carry it on
 * a page, so 節點's top tiles and 活動's rows do not. Outside the host it does nothing.
 */
@Composable
internal fun Modifier.rvSharedFlag(node: String): Modifier {
    val shared = LocalRvShared.current ?: return this
    val motion = Rv.motion
    val shown = LocalRvShown.current
    // A flag in the picker composed ahead and hidden takes no part: a node page pushed from 活動 would fly it in from
    // a row nobody sees.
    val config = remember(shown) { RvShownConfig(shown) }
    return with(shared.layout) {
        this@rvSharedFlag.sharedBounds(
            rememberSharedContentState(FLAG + node, config),
            shared.page,
            enter = fadeIn(motion.tween(shared.pushMs)),
            exit = fadeOut(motion.tween(shared.pushMs)),
            boundsTransform = { _, _ -> motion.tween(shared.pushMs) },
            clipInOverlayDuringTransition = OverlayClip(CircleShape),
        )
    }
}

private const val FLAG = "flag:"

/** A shared element that takes part only while [shown]. */
private class RvShownConfig(private val shown: () -> Boolean) : SharedTransitionScope.SharedContentConfig {
    override val SharedTransitionScope.SharedContentState.isEnabled: Boolean
        get() = shown()
}

/** Whether the page this is in has finished coming in (at once outside the host). */
@Composable
internal fun rvPageArrived(): () -> Boolean {
    val page = LocalRvShared.current?.page ?: return { true }
    return { page.transition.currentState == EnterExitState.Visible && page.transition.targetState == EnterExitState.Visible }
}
