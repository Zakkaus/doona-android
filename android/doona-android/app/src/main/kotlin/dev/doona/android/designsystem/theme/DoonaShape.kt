package dev.doona.android.designsystem.theme

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Immutable
import dev.doona.android.designsystem.tokens.AlphaScale
import dev.doona.android.designsystem.tokens.ChartScale
import dev.doona.android.designsystem.tokens.ChromeScale
import dev.doona.android.designsystem.tokens.RadiusScale
import dev.doona.android.designsystem.tokens.UsageScale

/** Shape roles. */
@Immutable
object DoonaShape {
    /** Every card: 20 dp corners. */
    val card = RoundedCornerShape(RadiusScale.card)
    val pill = RoundedCornerShape(percent = RadiusScale.PILL_PERCENT)

    /** The one value pill: 8 dp corners. */
    val valuePill = RoundedCornerShape(ChartScale.pillRadius)

    /** 今日用量's legend square. */
    val legend = RoundedCornerShape(UsageScale.legendRadius)

    /** Page sheet: a card's radius on the top corners only. */
    val sheet = RoundedCornerShape(topStart = RadiusScale.card, topEnd = RadiusScale.card)

    /** A circle flag: the flag drawables are discs, and their ring and fallback follow the same outline. */
    val flag = CircleShape

    // The old components' shapes, U1's.
    val badge = RoundedCornerShape(RadiusScale.sm)
    val control = RoundedCornerShape(RadiusScale.md)

    /** The box of a checkbox: 2 dp corners (§7, S2 Checkbox). */
    val checkbox = RoundedCornerShape(RadiusScale.xs)
}

/**
 * The soft shadows, in light only ([DoonaColors.glassShadow], [DoonaColors.raisedShadow]): the floating capsule's, the
 * top bar's controls', a value pill's two steps, a raised segment or range chip's, and the call to action's.
 */
@Immutable
object DoonaElevation {
    val nav = ChromeScale.navShadow
    val navDrop = ChromeScale.navShadowDrop
    const val NAV_ALPHA = AlphaScale.NAV_SHADOW
    val control = ChromeScale.controlShadow
    val controlDrop = ChromeScale.controlShadowDrop
    const val CONTROL_ALPHA = AlphaScale.CONTROL_SHADOW
    val pill = ChartScale.pillShadow
    const val PILL_NEAR_ALPHA = AlphaScale.PILL_SHADOW_NEAR
    const val PILL_FAR_ALPHA = AlphaScale.PILL_SHADOW_FAR
    val raised = ChartScale.rangeShadow
    const val CTA_ALPHA = AlphaScale.CTA_SHADOW
}

/**
 * Glass: the blur's radius and how far past a surface's edges it reads the content. The tints are colour roles
 * ([DoonaColors.navGlass], [DoonaColors.controlGlass], [DoonaColors.barGlass]); a surface with no blur shows their solid.
 */
@Immutable
object DoonaGlass {
    val blur = ChromeScale.blur
    val blurReach = ChromeScale.blurReach
}
