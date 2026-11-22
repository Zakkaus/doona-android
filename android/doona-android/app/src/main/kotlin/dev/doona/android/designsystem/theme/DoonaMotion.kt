package dev.doona.android.designsystem.theme

import androidx.compose.runtime.Immutable
import dev.doona.android.designsystem.tokens.MotionScale

/** Motion roles: how long the motion of a component takes. */
@Immutable
object DoonaMotion {
    /** One turn of the busy spinner. */
    const val SPINNER_TURN_MS = MotionScale.SPINNER_TURN_MS
}
