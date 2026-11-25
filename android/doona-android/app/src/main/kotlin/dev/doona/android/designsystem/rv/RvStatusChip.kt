package dev.doona.android.designsystem.rv

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color

/** Revolut's 'Market closed' chip, a tag chip tinted in [color]: its glyph and label in that colour. */
@Composable
internal fun RvStatusChip(@DrawableRes icon: Int, label: String, color: Color) {
    Row(
        Modifier.heightIn(min = RvControlSize.tag).clip(CircleShape).background(color.copy(alpha = RvAlpha.STATUS_CHIP)).padding(horizontal = RvControlSize.tagPad, vertical = RvSpace.tight),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(RvSpace.tight + RvSpace.tight / 2),
    ) {
        RvGlyph(icon, RvControlSize.tagGlyph, color)
        RvText(label, Rv.type.action, color = color)
    }
}
