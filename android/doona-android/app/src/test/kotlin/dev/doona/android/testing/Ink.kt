package dev.doona.android.testing

import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.captureToImage
import kotlin.math.abs

/** How far the drawn pixels of a node sit inside its box on each side, in dp. */
class InkInsets(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    fun max() = maxOf(left, top, right, bottom)

    fun describe() = "left %.1f, top %.1f, right %.1f, bottom %.1f dp inside its box".format(left, top, right, bottom)
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
    )
}

private const val INK_THRESHOLD = 0.25f
