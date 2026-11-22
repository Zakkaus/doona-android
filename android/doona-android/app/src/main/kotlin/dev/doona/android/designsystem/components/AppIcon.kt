package dev.doona.android.designsystem.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import dev.doona.android.designsystem.theme.DoonaTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The launcher icon of the installed app [packageName], in a box of [DoonaSize.appIcon]: the leading slot of a
 * [DoonaCheckRow]. The icon is read off the main thread and kept in a memory cache, so a row scrolled back into view
 * shows it at once. Until it has loaded, and for a package that is not installed or whose icon cannot be read, the box
 * is a neutral rounded square, so a list never jumps when its icons arrive.
 */
@Composable
fun DoonaAppIcon(packageName: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current.applicationContext
    val edge = DoonaTheme.size.appIcon
    val px = with(LocalDensity.current) { edge.roundToPx() }
    val icon by produceState(AppIcons.cached(packageName, px), packageName, px) { value = AppIcons.load(context, packageName, px) }
    val loaded = icon
    if (loaded != null) {
        Image(loaded, contentDescription = null, modifier = modifier.size(edge))
    } else {
        Box(modifier.size(edge).clip(DoonaTheme.shape.control).background(DoonaTheme.colors.discOnCard))
    }
}

/**
 * Icons by package and pixel size. The cache holds at most [MAX_BYTES] of bitmaps and drops the least recently used
 * first. A read that failed is not kept: the package may be installed, or its icon readable, the next time a row asks.
 */
internal object AppIcons {
    private const val MAX_BYTES = 8 * 1024 * 1024
    private const val BYTES_PER_PIXEL = 4

    private val cache = object : LruCache<String, ImageBitmap>(MAX_BYTES) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * BYTES_PER_PIXEL
    }

    private fun key(packageName: String, px: Int) = "$px:$packageName"

    fun cached(packageName: String, px: Int): ImageBitmap? = cache.get(key(packageName, px))

    /** The icon of an app, or null when it could not be read. */
    suspend fun load(context: Context, packageName: String, px: Int): ImageBitmap? {
        cached(packageName, px)?.let { return it }
        val bitmap = withContext(Dispatchers.IO) { read(context, packageName, px) } ?: return null
        cache.put(key(packageName, px), bitmap)
        return bitmap
    }

    fun clear() = cache.evictAll()

    /** Draws the icon into a square of [px], which is what the box shows; any failure of the package manager is a miss. */
    private fun read(context: Context, packageName: String, px: Int): ImageBitmap? = try {
        val drawable = context.packageManager.getApplicationIcon(packageName)
        val bitmap = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        drawable.setBounds(0, 0, px, px)
        drawable.draw(Canvas(bitmap))
        bitmap.asImageBitmap()
    } catch (_: Exception) {
        null
    }
}
