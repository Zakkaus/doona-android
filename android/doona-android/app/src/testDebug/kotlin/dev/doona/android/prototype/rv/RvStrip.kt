package dev.doona.android.prototype.rv

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb

/**
 * A frame strip, for the goldens of a motion: [frames] cropped to their top [bottom] px, side by side [GAP_PX] apart on
 * magenta, each with its time under it, in ink on the page's white (or black, [dark]).
 */
internal fun rvStrip(frames: List<Pair<String, ImageBitmap>>, bottom: Int, dark: Boolean): Bitmap {
    val width = frames.first().second.width
    val out = Bitmap.createBitmap(frames.size * (width + GAP_PX) - GAP_PX, bottom + BAND_PX, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(out)
    canvas.drawColor(GAP.toArgb())
    val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = (if (dark) Color.White else Color.Black).toArgb()
        textSize = TIME_PX
        textAlign = Paint.Align.CENTER
    }
    val band = Paint().apply { color = (if (dark) Color.Black else Color.White).toArgb() }
    frames.forEachIndexed { i, (time, frame) ->
        val x = i * (width + GAP_PX)
        canvas.drawBitmap(frame.asAndroidBitmap(), Rect(0, 0, width, bottom), Rect(x, 0, x + width, bottom), null)
        canvas.drawRect(Rect(x, bottom, x + width, bottom + BAND_PX), band)
        canvas.drawText(time, x + width / 2f, bottom + BAND_PX * TIME_LINE, ink)
    }
    return out
}

private const val GAP_PX = 8
private const val BAND_PX = 56
private const val TIME_PX = 28f
private const val TIME_LINE = 0.7f
private val GAP = Color.Magenta
