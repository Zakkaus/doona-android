package dev.doona.android.designsystem.components

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import dev.doona.android.designsystem.theme.DoonaTheme
import dev.doona.android.designsystem.theme.scripted

/** Body text in the theme's type; feature code uses this, not Material's `Text`. */
@Composable
fun DoonaText(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = DoonaTheme.colors.text,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Ellipsis,
    softWrap: Boolean = true,
) {
    Text(scripted(text), modifier, color = color, style = style, maxLines = maxLines, overflow = overflow, softWrap = softWrap)
}

/** Height of one text line of [style] in dp, so a dot or icon can sit on the centre of a first line. */
@Composable
fun lineHeightOf(style: TextStyle): Dp = with(LocalDensity.current) { style.lineHeight.toDp() }
