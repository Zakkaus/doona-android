package dev.doona.android.designsystem.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import dev.doona.android.designsystem.rv.AppIcons
import dev.doona.android.designsystem.theme.DoonaTheme

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
