package dev.doona.android.prototype.rv.nodes

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.doona.android.R
import dev.doona.android.designsystem.components.latencyTone
import dev.doona.android.prototype.rv.RV_NO_VALUE
import dev.doona.android.prototype.rv.Rv
import dev.doona.android.prototype.rv.RvAlpha
import dev.doona.android.prototype.rv.RvControlSize
import dev.doona.android.prototype.rv.RvGlyph
import dev.doona.android.prototype.rv.RvPrimaryButton
import dev.doona.android.prototype.rv.RvSpace
import dev.doona.android.prototype.rv.RvText
import dev.doona.android.prototype.rv.rvPress

/** Sizes of the 節點 page and the node detail page, after revolut-android-measured.md (its section in brackets). */
internal object RvNodesSize {
    /** The 'Top movers' grid's flags, and the list rows' (§7: 40 dp logo circles). */
    val topFlag = 54.dp
    val rowFlag = 40.dp

    /** 'Top movers': flag to name, and between the grid's two lines. */
    val topLabelGap = 8.dp
    val topRowGap = 16.dp

    /** The ▲▼ before a change (§7: a filled 8 dp triangle in the number's colour). */
    val delta = 8.dp

    /**
     * The round companion beside the floating [dev.doona.android.prototype.rv.RvPrimaryButton] (§8, N06: a 56 dp disc
     * 8 dp from the 52 dp pill, on one centre line).
     */
    val ctaCompanion = 56.dp
    val ctaGap = 8.dp
    val ctaGlyph = 24.dp
}

/** A latency's colour: the fixed good, fair and slow hues. */
@Composable
@ReadOnlyComposable
internal fun latencyColor(ms: Int?): Color = Rv.colors.quality(latencyTone(ms))

/** [ms] as the app writes a latency, or a dash for a test that timed out. */
@Composable
internal fun latencyText(ms: Int?): String = ms?.let { stringResource(R.string.latency_ms, it) } ?: RV_NO_VALUE

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

/**
 * Revolut's floating 'Get started' with the round companion beside it ('Buy' and more): the library's [RvPrimaryButton]
 * and a 56 dp container disc with no shadow, both on one centre line (N06).
 */
@Composable
internal fun RvFloatingActions(label: String, onClick: () -> Unit, @DrawableRes icon: Int, iconLabel: String, onIcon: () -> Unit, modifier: Modifier = Modifier) {
    val colors = Rv.colors
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(RvNodesSize.ctaGap), verticalAlignment = Alignment.CenterVertically) {
        RvPrimaryButton(label, onClick, Modifier.weight(1f))
        Box(
            Modifier
                .size(RvNodesSize.ctaCompanion)
                .clip(CircleShape)
                .background(colors.roundButton)
                .rvPress(CircleShape, Role.Button, onIcon)
                .semantics { contentDescription = iconLabel },
            contentAlignment = Alignment.Center,
        ) { RvGlyph(icon, RvNodesSize.ctaGlyph, colors.text) }
    }
}
