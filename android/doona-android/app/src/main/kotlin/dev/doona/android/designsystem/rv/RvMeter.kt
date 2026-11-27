package dev.doona.android.designsystem.rv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp

/**
 * A meter's track, Revolut's analyst bar (web `MeterTrack`): an 8 dp pill track with [fraction] of it filled by a
 * gradient of [color], light to full.
 */
@Composable
internal fun RvMeterTrack(fraction: Float, color: Color, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(RvStatsSize.ratingBar).clip(CircleShape).background(Rv.colors.track)) {
        val brush = Brush.horizontalGradient(listOf(color.copy(alpha = color.alpha * RvStatsAlpha.RATING_START), color))
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).fillMaxHeight().clip(CircleShape).background(brush))
    }
}

/**
 * A quantity in a known range (web `Meter`): [RvMeterTrack] with [fraction] filled in [color], then `[valueLabel]
 * [label]` after it, or the [label] alone and a bare track when the value is not [known]. The row grows past its height
 * when large text needs it, so the label is never cut. 分流比例 and the 評分 card show two each. Meters stacked with
 * longer labels pass one [labelWidth]: each label then takes that width, wrapping onto more lines when it needs them,
 * so their tracks stay the same length.
 */
@Composable
internal fun RvMeter(
    fraction: Float,
    valueLabel: String,
    label: String,
    color: Color,
    known: Boolean,
    modifier: Modifier = Modifier,
    labelWidth: Dp? = null,
) {
    Row(modifier.heightIn(min = RvStatsSize.ratingRow), verticalAlignment = Alignment.CenterVertically) {
        RvMeterTrack(if (known) fraction else 0f, color, Modifier.weight(1f))
        Spacer(Modifier.width(RvSpace.trailingGap))
        val text = if (known) "$valueLabel $label" else label
        if (labelWidth == null) {
            RvText(text, Rv.type.rowSub, Modifier.widthIn(min = RvStatsSize.ratingLabel), align = TextAlign.End)
        } else {
            RvText(text, Rv.type.rowSub, Modifier.width(labelWidth), align = TextAlign.End, maxLines = Int.MAX_VALUE)
        }
    }
}
