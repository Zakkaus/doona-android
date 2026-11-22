package dev.doona.android.testing

import android.view.View
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onRoot

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

/** Whether the image has more than one colour, which is what a rendered page has and an empty window does not. */
fun ImageBitmap.showsSomething(): Boolean {
    val pixels = toPixelMap()
    val first = pixels[0, 0]
    return (0 until height step SAMPLE).any { y -> (0 until width step SAMPLE).any { x -> pixels[x, y] != first } }
}

private const val SAMPLE = 13
