package dev.doona.android.designsystem.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.runtime.Immutable
import dev.doona.android.designsystem.tokens.MotionScale

/**
 * Motion roles: how long each motion takes and how it eases, every value Revolut's as measured (rv-motion.md §1).
 * Compose's animations follow the system animator scale (`MotionDurationScale`), so at scale 0 they are instant; a
 * timing that is not a Compose animation must multiply by that scale itself.
 */
@Immutable
object DoonaMotion {
    const val PULL_REVEAL_MS = MotionScale.PULL_REVEAL_MS
    const val PULL_RETURN_MS = MotionScale.PULL_RETURN_MS
    const val TAB_SWITCH_MS = MotionScale.TAB_SWITCH_MS
    const val HERO_FADE_MS = MotionScale.HERO_FADE_MS
    const val NAV_COLLAPSE_MS = MotionScale.NAV_COLLAPSE_MS
    const val POPUP_MS = MotionScale.POPUP_MS
    const val PUSH_MS = MotionScale.PUSH_MS
    const val PRESS_MS = MotionScale.PRESS_MS
    const val LONG_PRESS_MS = MotionScale.LONG_PRESS_MS

    /** One turn of the busy spinner. */
    const val SPINNER_TURN_MS = MotionScale.SPINNER_TURN_MS

    /** Material 3's emphasized decelerate: the pull-down's reveal and return, the push, the popup. No overshoot. */
    val emphasizedDecelerate: Easing = CubicBezierEasing(
        MotionScale.EMPHASIZED_DECELERATE_X1,
        MotionScale.EMPHASIZED_DECELERATE_Y1,
        MotionScale.EMPHASIZED_DECELERATE_X2,
        MotionScale.EMPHASIZED_DECELERATE_Y2,
    )

    /** Material 3's standard decelerate: press feedback, the nav's collapse, the hero's fade. No overshoot. */
    val standardDecelerate: Easing = CubicBezierEasing(
        MotionScale.STANDARD_DECELERATE_X1,
        MotionScale.STANDARD_DECELERATE_Y1,
        MotionScale.STANDARD_DECELERATE_X2,
        MotionScale.STANDARD_DECELERATE_Y2,
    )
}
