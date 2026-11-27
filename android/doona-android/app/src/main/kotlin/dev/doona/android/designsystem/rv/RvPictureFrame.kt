package dev.doona.android.designsystem.rv

import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip

/**
 * A picture inside a card: clipped to [Rv.highlightRadius] corners, with the flags' hairline ring ([RvChrome.ring] in
 * [RvColors.flagRing]) round it. 主題's mode pictures ([RvSwatch]) and its preview wear it.
 */
@Composable
internal fun Modifier.rvPictureFrame(): Modifier {
    val shape = RoundedCornerShape(Rv.highlightRadius)
    return clip(shape).border(RvChrome.ring, Rv.colors.flagRing, shape)
}
