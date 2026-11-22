package dev.doona.android.testing

import android.view.View
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onRoot
import kotlin.math.abs

/**
 * Draws the content before it is captured. A window that was laid out but never invalidated can be captured empty,
 * which Robolectric does for a short page after enough earlier tests, and a golden recorded from it is blank. Setting
 * the content does not always invalidate the view, so this does.
 */
fun ComposeContentTestRule.drawFully() {
    val view = onRoot().fetchSemanticsNode().root as View
    runOnUiThread { view.invalidate() }
    waitForIdle()
}

/**
 * How much of a captured [PhoneFrame] page is ink, from 0 to 1: the share of the content area whose pixels differ clearly
 * from the pixel to their left or above them, which text, icons and borders do and a flat fill does not. The content area
 * is the capture without the frame's status bar and gesture bar, which is where the frame draws its clock, indicators and
 * handle: ink there says nothing about the page, and a count of colours over the whole capture is met by the frame alone.
 */
fun ImageBitmap.contentInk(density: Float): Float {
    val pixels = toPixelMap()
    val top = (StatusBar.value * density).toInt() + 1
    val bottom = height - (GestureBar.value * density).toInt()
    var ink = 0
    for (y in top until bottom) {
        for (x in 1 until width) {
            val c = pixels[x, y]
            if (c.distanceTo(pixels[x - 1, y]) > EDGE || c.distanceTo(pixels[x, y - 1]) > EDGE) ink++
        }
    }
    return ink.toFloat() / ((bottom - top) * (width - 1))
}

/** Whether the content area of a captured page shows anything but fills: the sparsest page, a title and the bars, shows five times [MIN_INK]. */
fun ImageBitmap.showsContent(density: Float): Boolean = contentInk(density) >= MIN_INK

/** The number of different colours in the image: what a check of the whole capture counts, and the frame alone satisfies. */
fun ImageBitmap.colours(): Int {
    val pixels = toPixelMap()
    val seen = HashSet<Int>()
    for (y in 0 until height) for (x in 0 until width) seen.add(pixels[x, y].toArgb())
    return seen.size
}

private fun Color.distanceTo(o: Color) = (abs(red - o.red) + abs(green - o.green) + abs(blue - o.blue)) / 3

private const val EDGE = 0.1f
private const val MIN_INK = 0.0005f
