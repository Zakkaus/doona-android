package dev.doona.android.designsystem.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import dev.doona.android.designsystem.theme.DoonaTheme

/** A decorative glyph in [tint]; the control or text that holds it says what it means. */
@Composable
fun DoonaIcon(@DrawableRes icon: Int, tint: Color, modifier: Modifier = Modifier, size: Dp = DoonaTheme.size.icon) {
    Icon(painterResource(icon), contentDescription = null, tint = tint, modifier = modifier.size(size))
}

/** Twemoji flag ahead of a node name (doona puts the flag glyph first, `src/ui/flags.css`). */
@Composable
fun DoonaFlag(@DrawableRes flag: Int, label: String, modifier: Modifier = Modifier) {
    val painter = painterResource(flag)
    val ratio = painter.intrinsicSize.width / painter.intrinsicSize.height
    val edge = DoonaTheme.colors.flagEdge
    Image(
        painter,
        contentDescription = label,
        modifier = modifier
            .height(DoonaTheme.size.flagHeight)
            .aspectRatio(ratio)
            .then(if (edge != null) Modifier.border(DoonaTheme.size.flagEdge, edge, DoonaTheme.shape.flag) else Modifier),
    )
}
