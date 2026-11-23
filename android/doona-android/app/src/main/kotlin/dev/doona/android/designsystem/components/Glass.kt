package dev.doona.android.designsystem.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntSize
import dev.doona.android.designsystem.theme.DoonaGlassTint
import dev.doona.android.designsystem.theme.DoonaTheme
import kotlin.math.ceil

/**
 * Frosted glass over a screen's content, Revolut's Glass style done with the platform's own blur: [glassSource] records
 * the content into one layer each time it draws, and each [glass] surface above it draws the part of that recording
 * under it, blurred, then its tint. On the GPU the surfaces keep referring to the recording, so they follow the content
 * as it scrolls. RenderEffect blur is there from Android 12 (API 31), the app's minimum.
 */
@Stable
class GlassBackdrop internal constructor(internal val layer: GraphicsLayer) {
    /** Where the recorded content sits in the window, and how far it reaches: a surface's blur reads no further. */
    internal var origin by mutableStateOf(Offset.Zero)
    internal var size by mutableStateOf(IntSize.Zero)
}

/** The glass of one screen: one [glassSource] under any number of [glass] surfaces. */
@Composable
fun rememberGlass(): GlassBackdrop {
    val layer = rememberGraphicsLayer()
    return remember(layer) { GlassBackdrop(layer) }
}

/**
 * Whether a glass surface blurs what is under it. Without the blur it draws its tint's solid fill: what a screenshot
 * test sees, since Robolectric draws no RenderEffect, and what a test sets to check that fill.
 */
internal val LocalGlassBlur = staticCompositionLocalOf { true }

/** Records what this element draws for the [glass] surfaces above it, and draws it unchanged. */
fun Modifier.glassSource(glass: GlassBackdrop): Modifier = onGloballyPositioned {
    glass.origin = it.positionInRoot()
    glass.size = it.size
}.drawWithContent {
    glass.layer.record { this@drawWithContent.drawContent() }
    drawLayer(glass.layer)
}

/**
 * This element's background in [shape]: the [glass] content behind it blurred under [tint]'s `over`, or its `solid`
 * where glass does not blur ([LocalGlassBlur]). Each tint is a plain fill. It used to be a gradient from the element's
 * bottom edge to that same edge; Skia draws a gradient of no length as its last colour, transparent, so no glass tint
 * ever showed and the blurred content showed through at full strength (item 23).
 */
@Composable
fun Modifier.glass(glass: GlassBackdrop, shape: Shape, tint: DoonaGlassTint): Modifier {
    val blur = rememberGraphicsLayer()
    val spot = remember { GlassSpot() }
    val blurs = LocalGlassBlur.current
    val radius = DoonaTheme.glass.blur
    val reach = DoonaTheme.glass.blurReach
    return onGloballyPositioned { spot.origin = it.positionInRoot() }
        .clip(shape)
        .drawBehind {
            if (blurs) {
                drawBackdrop(glass, blur, spot.origin - glass.origin, radius.toPx(), reach.toPx())
                drawRect(tint.over)
            } else {
                drawRect(tint.solid)
            }
        }
}

/** Where a glass surface sits in the window; state, so a surface that moves draws its backdrop again. */
@Stable
private class GlassSpot {
    var origin by mutableStateOf(Offset.Zero)
}

/**
 * The recording under this surface, [offset] into it, blurred by [radius]. The blur reads [reach] past the surface's
 * edges, as far as the recording goes, and the surface's clip keeps only its own part. Blurring the surface's own
 * rectangle alone made the blur clamp its edge pixels, so whatever crossed an edge was smeared across the fill as a dark
 * or pale streak (item 23). The recording's own edges are the screen's, where clamping is right.
 */
private fun DrawScope.drawBackdrop(glass: GlassBackdrop, blur: GraphicsLayer, offset: Offset, radius: Float, reach: Float) {
    val left = (offset.x - reach).coerceAtLeast(0f)
    val top = (offset.y - reach).coerceAtLeast(0f)
    val right = (offset.x + size.width + reach).coerceAtMost(glass.size.width.toFloat())
    val bottom = (offset.y + size.height + reach).coerceAtMost(glass.size.height.toFloat())
    if (right <= left || bottom <= top) return
    blur.renderEffect = BlurEffect(radius, radius, TileMode.Clamp)
    blur.record(IntSize(ceil(right - left).toInt(), ceil(bottom - top).toInt())) { translate(-left, -top) { drawLayer(glass.layer) } }
    translate(left - offset.x, top - offset.y) { drawLayer(blur) }
}

/** A soft drop shadow of [shape] under a glass surface or a raised control: in light only, as the theme says. */
@Composable
fun Modifier.softShadow(shape: Shape, radius: Dp, drop: Dp, opacity: Float): Modifier =
    if (DoonaTheme.colors.glassShadow) dropShadow(shape, Shadow(radius, Color.Black, offset = DpOffset.Zero.copy(y = drop), alpha = opacity)) else this
