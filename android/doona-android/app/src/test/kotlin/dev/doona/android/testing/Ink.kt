package dev.doona.android.testing

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.captureToImage
import kotlin.math.abs

/** How far the drawn pixels of a node sit inside its box on each side, in dp; [width] and [height] are the box's. */
class InkInsets(val left: Float, val top: Float, val right: Float, val bottom: Float, val width: Float, val height: Float) {
    fun max() = maxOf(left, top, right, bottom)

    /** The size of the drawn pixels' bounding box. */
    val inkWidth get() = width - left - right
    val inkHeight get() = height - top - bottom

    fun describe() = "left %.1f, top %.1f, right %.1f, bottom %.1f dp inside its box".format(left, top, right, bottom)

    /** Whether this box cuts ink off: the same glyph drawn whole, [full], has a bigger bounding box on some axis. */
    fun clippedAgainst(full: InkInsets, tolerance: Float) = inkWidth < full.inkWidth - tolerance || inkHeight < full.inkHeight - tolerance
}

/**
 * Renders the node and finds the pixels that differ from its corner pixel, which is the background the node sits on.
 * Returns null when nothing is drawn.
 */
fun SemanticsNodeInteraction.inkInsets(density: Float): InkInsets? {
    val image = captureToImage().toPixelMap()
    val background = image[0, 0]
    fun differs(x: Int, y: Int): Boolean {
        val c = image[x, y]
        return abs(c.red - background.red) + abs(c.green - background.green) + abs(c.blue - background.blue) > INK_THRESHOLD
    }
    val inked = (0 until image.height).flatMap { y -> (0 until image.width).filter { differs(it, y) }.map { it to y } }
    if (inked.isEmpty()) return null
    return InkInsets(
        left = inked.minOf { it.first } / density,
        top = inked.minOf { it.second } / density,
        right = (image.width - 1 - inked.maxOf { it.first }) / density,
        bottom = (image.height - 1 - inked.maxOf { it.second }) / density,
        width = image.width / density,
        height = image.height / density,
    )
}

/**
 * The colour a node draws its ink in: the pixel inside [box], in pixels of the image, that lies farthest from
 * [background]. A stroke wide enough to cover a pixel whole shows the colour it was drawn in exactly, where the edge
 * pixels of a glyph are mixed with the background.
 */
fun PixelMap.inkColour(box: Rect, background: Color): Color {
    val inside = (box.top.toInt() until box.bottom.toInt()).flatMap { y -> (box.left.toInt() until box.right.toInt()).map { x -> this[x, y] } }
    return inside.maxBy { abs(it.red - background.red) + abs(it.green - background.green) + abs(it.blue - background.blue) }
}

private const val INK_THRESHOLD = 0.25f
