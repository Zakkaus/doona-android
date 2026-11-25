package dev.doona.android.app

import androidx.activity.BackEventCompat
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.scene.Scene
import dev.doona.android.designsystem.rv.RvMotion
import dev.doona.android.designsystem.rv.RvMotionScale
import kotlin.math.roundToInt

// RV's page transitions (rv-gestures T1/T2, S1/S2, G7), shared by the app's NavDisplay ([RvNavHost]) and the design
// sandbox's stack host, so both move alike.

/** How a tab switch looks: T1 a hard cut with the hero art fading in (the default), T2 a cross-fade of the whole page. */
internal enum class RvTabMotion(val code: String) { Cut("T1"), Fade("T2") }

/** How a page is pushed and popped: S1 a cross-fade with a slight zoom (the default), S2 Material's shared axis X. */
internal enum class RvPushMotion(val code: String) { Zoom("S1"), Axis("S2") }

/** How long a push or a pop under [push] takes: the shared elements move as long. */
internal fun rvPopMs(push: RvPushMotion) = if (push == RvPushMotion.Zoom) RvMotion.PUSH_MS else RvMotion.AXIS_MS

/** A tab switch: [RvTabMotion.Cut] shows the new tab at once, [RvTabMotion.Fade] cross-fades the pages. */
internal fun rvTabSwitch(tab: RvTabMotion, motion: RvMotionScale): ContentTransform = when (tab) {
    RvTabMotion.Cut -> EnterTransition.None togetherWith ExitTransition.None
    RvTabMotion.Fade -> fadeIn(motion.tween(RvMotion.FADE_MS)) togetherWith fadeOut(motion.tween(RvMotion.FADE_MS))
}

/**
 * The search page (G7), whichever push option is set: it fades in rising [shift] px while the pill becomes its field,
 * in [RvMotion.SEARCH_MS]; on the way back it sinks and fades out over the page it came from.
 */
internal fun rvSearchMove(motion: RvMotionScale, shift: Int, forward: Boolean): ContentTransform {
    val ms = RvMotion.SEARCH_MS
    return if (forward) {
        (fadeIn(motion.tween(ms)) + slideInVertically(motion.tween(ms)) { shift }) togetherWith fadeOut(motion.tween(ms))
    } else {
        ContentTransform(fadeIn(motion.tween(ms)), fadeOut(motion.tween(ms)) + slideOutVertically(motion.tween(ms)) { shift }, targetContentZIndex = -1f)
    }
}

/** S1: the new page fades in growing from 96 %; on a pop the leaving page, on top, fades out shrinking to 96 %. */
internal fun rvZoomMove(motion: RvMotionScale, forward: Boolean): ContentTransform {
    val ms = RvMotion.PUSH_MS
    return if (forward) {
        (fadeIn(motion.tween(ms)) + scaleIn(motion.tween(ms), initialScale = RvMotion.PUSH_ZOOM)) togetherWith fadeOut(motion.tween(ms))
    } else {
        ContentTransform(fadeIn(motion.tween(ms)), fadeOut(motion.tween(ms)) + scaleOut(motion.tween(ms), targetScale = RvMotion.PUSH_ZOOM), targetContentZIndex = -1f)
    }
}

/** S2, Material's shared axis X: both pages travel [shift] px the same way; the old fades out first, then the new fades in. */
internal fun rvAxisMove(motion: RvMotionScale, shift: Int, forward: Boolean): ContentTransform {
    val ms = RvMotion.AXIS_MS
    val out = (ms * RvMotion.AXIS_FADE).roundToInt()
    val sign = if (forward) 1 else -1
    val enter = slideInHorizontally(motion.tween(ms)) { sign * shift } + fadeIn(motion.tween(ms - out, delay = out))
    val exit = slideOutHorizontally(motion.tween(ms)) { -sign * shift } + fadeOut(motion.tween(out))
    return enter togetherWith exit
}

/**
 * A predictive back's preview ([RvMotion.BACK_MS]): the page on top shrinks to [RvMotion.BACK_SCALE] and moves away from
 * the swipe's [edge] by 1/[RvMotion.BACK_SHIFT_PARTS] of its width less [margin] px, fading out only at the end, over the page under it,
 * which stands in place. The gesture's progress seeks the move: the shrink and the shift decelerate ([RvMotion.Decelerate]),
 * so most of them show within the first part of a swipe, as the system's own preview does, and the fade runs linearly.
 */
internal fun rvBackPreview(motion: RvMotionScale, margin: Int, edge: Int): ContentTransform {
    val ms = RvMotion.BACK_MS
    val fade = (ms * RvMotion.BACK_FADE).roundToInt()
    val away = if (edge == BackEventCompat.EDGE_RIGHT) -1 else 1
    val exit = scaleOut(motion.tween(ms, RvMotion.Decelerate), targetScale = RvMotion.BACK_SCALE) +
        slideOutHorizontally(motion.tween(ms, RvMotion.Decelerate)) { away * (it / RvMotion.BACK_SHIFT_PARTS - margin) } +
        fadeOut(motion.tween(fade, LinearEasing, delay = ms - fade))
    return ContentTransform(EnterTransition.None, exit, targetContentZIndex = -1f)
}

/** The metadata entry [RvNavHost] puts on every entry: its back stack key, which [Scene]s otherwise do not show. */
internal const val RV_NAV_KEY = "dev.doona.android.app.key"

/** The key of [entry], as [RvNavHost] recorded it. */
internal fun rvKeyOf(entry: NavEntry<*>): NavKey? = entry.metadata[RV_NAV_KEY] as? NavKey

/** The key on top of this scene. */
internal fun Scene<*>.rvTop(): NavKey? = entries.lastOrNull()?.let(::rvKeyOf)

/** The tab this scene belongs to, from the whole stack it shows: [currentTab] of the entries under it and its own. */
internal fun Scene<*>.rvTab(): Tab = currentTab((previousEntries + entries).mapNotNull(::rvKeyOf))

/**
 * The app's move from one scene to the next ([forward] for a push, else a pop, predictive or not): a cut when the tab
 * changes (活動 stays at the bottom of the stack, so a tab switch can look like a push, and the tab of each side tells
 * them apart), the search page's rise, else S1's zoom. At animator scale 0 nothing moves.
 */
internal fun AnimatedContentTransitionScope<Scene<NavKey>>.rvSceneMove(motion: RvMotionScale, shift: Int, forward: Boolean): ContentTransform = when {
    motion.instant -> EnterTransition.None togetherWith ExitTransition.None
    initialState.rvTab() != targetState.rvTab() -> rvTabSwitch(RvTabMotion.Cut, motion)
    initialState.rvTop() == NodeSearchKey || targetState.rvTop() == NodeSearchKey -> rvSearchMove(motion, shift, forward)
    else -> rvZoomMove(motion, forward)
}

/**
 * The app's predictive back from the swipe's [edge]: [rvBackPreview] for every pop the finger can follow, the search
 * page's sinking as on a plain back. At animator scale 0 nothing moves.
 */
internal fun AnimatedContentTransitionScope<Scene<NavKey>>.rvSceneBack(motion: RvMotionScale, shift: Int, margin: Int, edge: Int): ContentTransform = when {
    motion.instant || initialState.rvTop() == NodeSearchKey -> rvSceneMove(motion, shift, forward = false)
    else -> rvBackPreview(motion, margin, edge)
}
