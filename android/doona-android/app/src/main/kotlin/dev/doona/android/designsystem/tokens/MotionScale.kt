package dev.doona.android.designsystem.tokens

/**
 * Durations and easings of the motion, measured on Revolut's app (plans/briefs/rv-motion.md, top of the file). Defaults
 * only: the options to compare belong to the motion lane.
 *
 * Compose's animations scale their durations by the system animator scale (Compose reads it as `MotionDurationScale`),
 * so at scale 0 they are instant. A timing that does not run through a Compose animation, a `delay` or a hand-driven
 * clock, must apply the scale itself.
 */
object MotionScale {
    /** The pull-down on 活動 that opens the nodes page: about 700 ms to reveal, about 400 ms back, decelerating, no overshoot. */
    const val PULL_REVEAL_MS = 700
    const val PULL_RETURN_MS = 400

    /** A tab switch is a hard cut; the hero's art fades in over 330 ms. */
    const val TAB_SWITCH_MS = 0
    const val HERO_FADE_MS = 330

    /** The floating nav collapses and expands in about 130 ms (the prototype's 220 ms was a guess, not a measure). */
    const val NAV_COLLAPSE_MS = 130

    /** The More popup scales and fades in about 267 ms. */
    const val POPUP_MS = 267

    /** A push is a cross-fade with a slight zoom, about 130 ms. */
    const val PUSH_MS = 130

    /** Press feedback shows in about 60 ms. */
    const val PRESS_MS = 60

    /** A long press opens its menu after about 500 ms. */
    const val LONG_PRESS_MS = 500

    /** The busy spinner's arc turns once in this many milliseconds (U1). */
    const val SPINNER_TURN_MS = 1000

    /**
     * Material 3's emphasized decelerate, cubic-bezier(0.05, 0.7, 0.1, 1): fast out of the finger, a long settle, both
     * control points inside the unit square so it never overshoots. The pull-down's reveal and return, the push, the popup.
     */
    const val EMPHASIZED_DECELERATE_X1 = 0.05f
    const val EMPHASIZED_DECELERATE_Y1 = 0.7f
    const val EMPHASIZED_DECELERATE_X2 = 0.1f
    const val EMPHASIZED_DECELERATE_Y2 = 1f

    /** Material 3's standard decelerate, cubic-bezier(0, 0, 0, 1), no overshoot: the short ones, press, nav collapse and hero fade. */
    const val STANDARD_DECELERATE_X1 = 0f
    const val STANDARD_DECELERATE_Y1 = 0f
    const val STANDARD_DECELERATE_X2 = 0f
    const val STANDARD_DECELERATE_Y2 = 1f
}
