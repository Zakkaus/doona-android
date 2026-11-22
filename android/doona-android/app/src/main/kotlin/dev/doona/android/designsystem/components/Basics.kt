package dev.doona.android.designsystem.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import dev.doona.android.designsystem.theme.DoonaTheme
import dev.doona.android.designsystem.theme.scripted

// The pieces every component of the design system draws with: a line of text in a role, the one press feedback, a glyph.

/** Text in a type role, [maxLines] (one by default) ending in an ellipsis; CJK runs in Noto, the rest in Inter. */
@Composable
internal fun LineText(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = DoonaTheme.colors.text,
    align: TextAlign = TextAlign.Start,
    maxLines: Int = 1,
) {
    BasicText(scripted(text), modifier, style.copy(color = color, textAlign = align), overflow = TextOverflow.Ellipsis, maxLines = maxLines)
}

/** The one press feedback: the content colour's state layer, clipped to the visible [shape]. */
@Composable
internal fun Modifier.press(shape: Shape, role: Role, onClick: () -> Unit): Modifier =
    clip(shape).clickable(interactionSource = null, indication = ripple(color = DoonaTheme.colors.stateLayer), role = role, onClick = onClick)

/** A glyph of the icon set in [color], [size] square; decorative: the control or text that holds it says what it means. */
@Composable
fun DoonaGlyph(@DrawableRes icon: Int, size: Dp, color: Color, modifier: Modifier = Modifier) {
    Image(painterResource(icon), contentDescription = null, modifier.size(size), colorFilter = ColorFilter.tint(color))
}

/** What a value that is not there shows: a dash. */
internal const val NO_VALUE = "—"
