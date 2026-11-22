package dev.doona.android.prototype.rv

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
import androidx.compose.ui.graphics.Brush
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.isSpecified

/**
 * Frosted glass over a screen's content, Revolut's Glass style done with the platform's own blur: [rvGlassSource]
 * records the content into one layer each time it draws, and each [rvGlass] surface above it draws the part of that
 * recording under it, blurred, then its tint. On the GPU the surfaces keep referring to the recording, so they follow
 * the content as it scrolls. RenderEffect blur needs Android 12 (API 31); below it a surface shows its solid tint.
 */
@Stable
internal class RvGlass(val layer: GraphicsLayer) {
    /** Where the recorded content sits in the window. */
    var origin by mutableStateOf(Offset.Zero)
}

@Composable
internal fun rememberRvGlass(): RvGlass {
    val layer = rememberGraphicsLayer()
    return remember(layer) { RvGlass(layer) }
}

/** Records what this element draws for the [glass] surfaces above it, and draws it unchanged. */
internal fun Modifier.rvGlassSource(glass: RvGlass): Modifier = onGloballyPositioned { glass.origin = it.positionInRoot() }
    .drawWithContent {
        glass.layer.record { this@drawWithContent.drawContent() }
        drawLayer(glass.layer)
    }

/** A glass surface's tints: [over] the blur, and [solid] where there is no blur. */
@Immutable
internal class RvGlassTint(val over: Color, val solid: Color)

/**
 * This element's background in [shape]: the [glass] content behind it blurred under [tint]'s `over`, or its `solid`.
 * With [tintFade], the tint fades out over that much of the bottom while the blur goes on to the edge, so what scrolls
 * under the bottom shows through blurred rather than sharp and cut.
 */
@Composable
internal fun Modifier.rvGlass(glass: RvGlass, shape: Shape, tint: RvGlassTint, tintFade: Dp = Dp.Unspecified): Modifier {
    val blur = rememberGraphicsLayer()
    val spot = remember { RvGlassSpot() }
    return onGloballyPositioned { spot.origin = it.positionInRoot() }
        .clip(shape)
        .drawBehind {
            val fadeFrom = if (tintFade.isSpecified) size.height - tintFade.toPx() else size.height
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                drawBackdrop(glass, blur, spot.origin - glass.origin)
                drawRect(fadingDown(tint.over, fadeFrom, size.height))
            } else {
                drawRect(fadingDown(tint.solid, fadeFrom, size.height))
            }
        }
}

/** [color] down to [from], then fading to nothing at [to]. */
private fun fadingDown(color: Color, from: Float, to: Float) = Brush.verticalGradient(listOf(color, color.copy(alpha = 0f)), from, to)

/** Where a glass surface sits in the window; state, so a surface that moves draws its backdrop again. */
@Stable
private class RvGlassSpot {
    var origin by mutableStateOf(Offset.Zero)
}

/** The recording under this surface, [offset] into it, blurred by [RvChrome.blur] at the surface's edges as well. */
@RequiresApi(Build.VERSION_CODES.S)
private fun DrawScope.drawBackdrop(glass: RvGlass, blur: GraphicsLayer, offset: Offset) {
    val radius = RvChrome.blur.toPx()
    blur.renderEffect = BlurEffect(radius, radius, TileMode.Clamp)
    blur.record { translate(-offset.x, -offset.y) { drawLayer(glass.layer) } }
    drawLayer(blur)
}
