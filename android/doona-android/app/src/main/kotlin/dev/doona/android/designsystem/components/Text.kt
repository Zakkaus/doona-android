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

/** Body text in the theme's type; feature code uses this, not Material's `Text`. */
@Composable
fun DoonaText(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = DoonaTheme.colors.text.primary,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Ellipsis,
    softWrap: Boolean = true,
) {
    Text(text, modifier, color = color, style = style, maxLines = maxLines, overflow = overflow, softWrap = softWrap)
}

/** A tile's value (`.rp-big`, §7): the tabular figure on its own line. */
@Composable
fun DoonaMetricValue(text: String, modifier: Modifier = Modifier) {
    Text(text, style = DoonaTheme.type.tileValue, color = DoonaTheme.colors.text.primary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = modifier.layoutRole(LayoutRole.Value))
}

/** A node or group name on one line, ellipsized. */
@Composable
fun DoonaNodeName(name: String, style: TextStyle, modifier: Modifier = Modifier, color: Color = DoonaTheme.colors.text.primary) {
    Text(name, modifier, color = color, style = style, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

/**
 * The size of a dot that sits on a text line of [style], [dot] being its size on that line at the normal font size: the
 * dot grows with the line when the font does, as the text beside it does, and does not stay a speck.
 */
@Composable
fun dotSizeFor(style: TextStyle, dot: Dp): Dp = lineHeightOf(style) * (dot.value / style.lineHeight.value)

/** Height of one text line of [style] in dp, so a dot or icon can sit on the centre of a first line. */
@Composable
fun lineHeightOf(style: TextStyle): Dp = with(LocalDensity.current) { style.lineHeight.toDp() }
