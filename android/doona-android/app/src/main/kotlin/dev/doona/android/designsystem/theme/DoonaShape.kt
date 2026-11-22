package dev.doona.android.designsystem.theme

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Immutable
import dev.doona.android.designsystem.tokens.RadiusScale

/** Shape roles (§5). */
@Immutable
object DoonaShape {
    val badge = RoundedCornerShape(RadiusScale.sm)
    val control = RoundedCornerShape(RadiusScale.md)
    val card = RoundedCornerShape(RadiusScale.xl)
    val pill = RoundedCornerShape(percent = RadiusScale.PILL_PERCENT)

    /** The box of a checkbox: 2 dp corners (§7, S2 Checkbox). */
    val checkbox = RoundedCornerShape(RadiusScale.xs)

    /** Page sheet: 16 on the top corners only. */
    val sheet = RoundedCornerShape(topStart = RadiusScale.xl, topEnd = RadiusScale.xl)

    /** A circle flag: the flag drawables are discs, and their edge and fallback follow the same outline. */
    val flag = CircleShape
}
