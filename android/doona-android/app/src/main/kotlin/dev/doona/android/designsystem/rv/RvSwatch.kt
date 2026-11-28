package dev.doona.android.designsystem.rv

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect

/**
 * A palette as a small picture of a page (web `Swatch`), in [rvPictureFrame]: the page's colour, a dot for the hero and
 * two cards, in [colors]; with [split] its right half is that palette instead, as 跟隨系統 shows light and dark.
 */
@Composable
internal fun RvSwatch(colors: RvColors, modifier: Modifier = Modifier, split: RvColors? = null) {
    Canvas(modifier.size(RvSettingsSize.modeThumb).rvPictureFrame()) {
        page(colors)
        if (split != null) clipRect(left = size.width / 2) { page(split) }
    }
}

private fun DrawScope.page(colors: RvColors) {
    drawRect(colors.page)
    drawCircle(colors.positive, size.width * THUMB_DOT, Offset(size.width / 2, size.height * THUMB_DOT_Y))
    val inset = size.width * THUMB_INSET
    val radius = CornerRadius(inset)
    for (top in THUMB_CARDS) {
        drawRoundRect(colors.card, Offset(inset, size.height * top), Size(size.width - 2 * inset, size.height * THUMB_CARD), radius)
    }
}

/** The picture's hero dot (radius and centre as shares of its width and height) and its two cards' tops and height. */
private const val THUMB_DOT = 0.08f
private const val THUMB_DOT_Y = 0.2f
private const val THUMB_INSET = 0.12f
private val THUMB_CARDS = listOf(0.38f, 0.66f)
private const val THUMB_CARD = 0.22f
