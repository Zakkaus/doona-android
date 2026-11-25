package dev.doona.android.designsystem.rv

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
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
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.IntSize
import kotlin.math.ceil

/**
 * Frosted glass over a screen's content, Revolut's Glass style done with the platform's own blur: [rvGlassSource]
 * records the content into one layer each time it draws, and each [rvGlass] surface above it draws the part of that
 * recording under it, blurred, then its tint. On the GPU the surfaces keep referring to the recording, so they follow
 * the content as it scrolls. RenderEffect blur needs Android 12 (API 31); below it a surface shows its solid tint.
 */
@Stable
internal class RvGlass(val layer: GraphicsLayer) {
    /** Where the recorded content sits in the window, and how far it reaches: a surface's blur reads no further. */
    var origin by mutableStateOf(Offset.Zero)
    var size by mutableStateOf(IntSize.Zero)
}

@Composable
internal fun rememberRvGlass(): RvGlass {
    val layer = rememberGraphicsLayer()
    return remember(layer) { RvGlass(layer) }
}

/** Records what this element draws for the [glass] surfaces above it, and draws it unchanged. */
internal fun Modifier.rvGlassSource(glass: RvGlass): Modifier = onGloballyPositioned {
    glass.origin = it.positionInRoot()
    glass.size = it.size
}.drawWithContent {
    glass.layer.record { this@drawWithContent.drawContent() }
    drawLayer(glass.layer)
}

/** A glass surface's tints: [over] the blur, and [solid] where there is no blur. */
@Immutable
internal class RvGlassTint(val over: Color, val solid: Color)

/**
 * This element's background in [shape]: the [glass] content behind it blurred under [tint]'s `over`, or its `solid`.
 * Each tint is a plain fill. It used to be a gradient from the element's bottom edge to that same edge; Skia draws a
 * gradient of no length as its last colour, transparent, so no glass tint ever showed and the blurred content showed
 * through at full strength (item 23).
 */
@Composable
internal fun Modifier.rvGlass(glass: RvGlass, shape: Shape, tint: RvGlassTint): Modifier = rvGlass(glass, shape, { tint.over }, tint.solid)

/** [rvGlass] whose `over` tint is read as the surface draws, so a tint that moves every frame composes nothing. */
@Composable
internal fun Modifier.rvGlass(glass: RvGlass, shape: Shape, over: () -> Color, solid: Color): Modifier {
    val blur = rememberGraphicsLayer()
    val spot = remember { RvGlassSpot() }
    return onGloballyPositioned { spot.origin = it.positionInRoot() }
        .clip(shape)
        .drawBehind {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                drawBackdrop(glass, blur, spot.origin - glass.origin)
                drawRect(over())
            } else {
                drawRect(solid)
            }
        }
}

/** Where a glass surface sits in the window; state, so a surface that moves draws its backdrop again. */
@Stable
private class RvGlassSpot {
    var origin by mutableStateOf(Offset.Zero)
}

/**
 * The recording under this surface, [offset] into it, blurred by [RvChrome.blur]. The blur reads [RvChrome.blurReach]
 * past the surface's edges, as far as the recording goes, and the surface's clip keeps only its own part. Blurring the
 * surface's own rectangle alone made the blur clamp its edge pixels, so whatever crossed an edge was smeared across the
 * fill as a dark or pale streak (item 23). The recording's own edges are the screen's, where clamping is right.
 */
@RequiresApi(Build.VERSION_CODES.S)
private fun DrawScope.drawBackdrop(glass: RvGlass, blur: GraphicsLayer, offset: Offset) {
    val radius = RvChrome.blur.toPx()
    val reach = RvChrome.blurReach.toPx()
    val left = (offset.x - reach).coerceAtLeast(0f)
    val top = (offset.y - reach).coerceAtLeast(0f)
    val right = (offset.x + size.width + reach).coerceAtMost(glass.size.width.toFloat())
    val bottom = (offset.y + size.height + reach).coerceAtMost(glass.size.height.toFloat())
    if (right <= left || bottom <= top) return
    blur.renderEffect = BlurEffect(radius, radius, TileMode.Clamp)
    blur.record(IntSize(ceil(right - left).toInt(), ceil(bottom - top).toInt())) { translate(-left, -top) { drawLayer(glass.layer) } }
    translate(left - offset.x, top - offset.y) { drawLayer(blur) }
}
