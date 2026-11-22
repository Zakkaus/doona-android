package dev.doona.android.designsystem.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.dp
import dev.doona.android.designsystem.theme.DoonaElevation
import dev.doona.android.designsystem.theme.DoonaTheme

/**
 * Revolut's 'Market closed' chip: a pill tinted in [color], its [icon] and [label] in that colour (a latency hue, say;
 * the theme keeps those at 4.5:1 on their own tint over the page and the card).
 */
@Composable
fun DoonaStatusChip(@DrawableRes icon: Int, label: String, color: Color, modifier: Modifier = Modifier) {
    val d = DoonaStatusChipDefaults
    Row(
        modifier
            .layoutRole(LayoutRole.Chip, mergeDescendants = true)
            .heightIn(min = d.height)
            .clip(CircleShape)
            .background(DoonaTheme.colors.statusChip(color))
            .padding(horizontal = d.padH, vertical = DoonaTheme.spacing.tight),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(d.gap),
    ) {
        DoonaGlyph(icon, d.glyph, color)
        LineText(label, DoonaTheme.type.action, color = color)
    }
}

/**
 * The value pill as a component (the median and 統計's chips, S5-26, T04): 28 dp tall, radius 8, 8 dp at the sides,
 * 15 sp semibold secondary text on an opaque fill, over a soft 2 dp shadow in light. A chart draws the same pill on its
 * canvas with [drawValuePill].
 */
@Composable
fun DoonaValuePill(text: String, modifier: Modifier = Modifier) {
    val colors = DoonaTheme.colors
    val shape = DoonaTheme.shape.valuePill
    val lift = if (colors.raisedShadow) Modifier.shadow(DoonaTheme.elevation.pill, shape) else Modifier
    Box(
        modifier
            .layoutRole(LayoutRole.Tag)
            .height(DoonaTheme.chart.pill)
            .then(lift)
            .clip(shape)
            .background(colors.pill)
            .padding(horizontal = DoonaTheme.chart.pillPadH),
        contentAlignment = Alignment.Center,
    ) { LineText(text, DoonaTheme.type.pill, color = colors.textSecondary) }
}

/** The pill's look for a canvas: its fill, and whether it casts the soft shadow. */
internal class PillInk(val fill: Color, val shadow: Boolean)

/** The size of the value pill round [text]. */
internal fun DrawScope.pillSize(text: TextLayoutResult) = Size(text.size.width + 2 * DoonaTheme.chart.pillPadH.toPx(), DoonaTheme.chart.pill.toPx())

/** The value pill drawn on a canvas at [topLeft], with [text] centred in it; the shadow is two soft steps under it. */
internal fun DrawScope.drawValuePill(text: TextLayoutResult, topLeft: Offset, ink: PillInk) {
    val size = pillSize(text)
    val radius = CornerRadius(DoonaStatusChipDefaults.pillRadius.toPx())
    if (ink.shadow) {
        val step = DoonaElevation.pill.toPx() / 2
        drawRoundRect(Color.Black.copy(alpha = DoonaElevation.PILL_FAR_ALPHA), topLeft + Offset(0f, step * 2), size, radius)
        drawRoundRect(Color.Black.copy(alpha = DoonaElevation.PILL_NEAR_ALPHA), topLeft + Offset(0f, step), size, radius)
    }
    drawRoundRect(ink.fill, topLeft, size, radius)
    drawText(text, topLeft = topLeft + Offset((size.width - text.size.width) / 2, (size.height - text.size.height) / 2))
}

/** The status chip's own metrics (the sample's 'Market closed' chip), and the value pill's corner. */
internal object DoonaStatusChipDefaults {
    val height = 32.dp
    val padH = 12.dp
    val glyph = 16.dp
    val gap = 6.dp
    val pillRadius = 8.dp
}
