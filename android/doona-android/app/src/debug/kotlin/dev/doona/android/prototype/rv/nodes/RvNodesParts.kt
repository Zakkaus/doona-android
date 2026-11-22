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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.doona.android.R
import dev.doona.android.designsystem.components.latencyTone
import dev.doona.android.prototype.rv.RV_NO_VALUE
import dev.doona.android.prototype.rv.Rv
import dev.doona.android.prototype.rv.RvAlpha
import dev.doona.android.prototype.rv.RvGlyph
import dev.doona.android.prototype.rv.RvInter
import dev.doona.android.prototype.rv.RvSpace
import dev.doona.android.prototype.rv.RvText
import dev.doona.android.prototype.rv.rvPress
import dev.doona.android.prototype.rv.rvSoftShadow

/** Sizes of the 節點 page and the node detail page, after revolut-android-measured.md (its section in brackets). */
internal object RvNodesSize {
    /** The 'Top movers' grid's flags, and the list rows' (§7: 40 dp logo circles). */
    val topFlag = 54.dp
    val rowFlag = 40.dp
    val detailFlag = 48.dp

    /** 'Top movers': flag to name, and between the grid's two lines. */
    val topLabelGap = 8.dp
    val topRowGap = 16.dp

    /** The ▲▼ before a change (§7: a filled 8 dp triangle in the number's colour). */
    val delta = 8.dp

    /** 'Market closed', the status chip: a tinted pill with a glyph. */
    val status = 32.dp
    val statusPad = 12.dp
    val statusGlyph = 16.dp

    /**
     * The floating 'Get started' pill and the round companion beside it (§8, N06: a 52 dp pill and a 56 dp disc on one
     * centre line, 8 dp apart, 16 dp above the inset), and the pill's soft shadow, cast from its own shape (S7-16).
     */
    val cta = 52.dp
    val ctaCompanion = 56.dp
    val ctaGap = 8.dp
    val ctaShadow = 12.dp
    val ctaShadowDrop = 4.dp
    val ctaGlyph = 24.dp
}

/** The pages' own type steps, set in Inter or Noto as [RvText] does. */
internal object RvNodesType {
    /** The name under a 'Top movers' flag and its latency. */
    val topName = TextStyle(fontFamily = RvInter, fontWeight = FontWeight.Medium, fontSize = 15.sp, lineHeight = 20.sp)
    val topValue = TextStyle(fontFamily = RvInter, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 20.sp, fontFeatureSettings = "tnum")

    /** The floating pill's label (§8: 22 sp on a 423 dp screen, 20 sp on ours). */
    val cta = TextStyle(fontFamily = RvInter, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp)
}

/** A latency's colour: the fixed good, fair and slow hues. */
@Composable
@ReadOnlyComposable
internal fun latencyColor(ms: Int?): Color = Rv.colors.quality(latencyTone(ms))

/** [ms] as the app writes a latency, or a dash for a test that timed out. */
@Composable
internal fun latencyText(ms: Int?): String = ms?.let { stringResource(R.string.latency_ms, it) } ?: RV_NO_VALUE

/** Revolut's 'Market closed' chip: a pill tinted in [color], its glyph and label in that colour. */
@Composable
internal fun RvStatusChip(@DrawableRes icon: Int, label: String, color: Color) {
    Row(
        Modifier.heightIn(min = RvNodesSize.status).clip(CircleShape).background(color.copy(alpha = RvAlpha.STATUS_CHIP)).padding(horizontal = RvNodesSize.statusPad, vertical = RvSpace.tight),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(RvSpace.tight + RvSpace.tight / 2),
    ) {
        RvGlyph(icon, RvNodesSize.statusGlyph, color)
        RvText(label, Rv.type.action, color = color)
    }
}

/**
 * Revolut's floating 'Get started' with the round companion beside it ('Buy' and more): a full-width pill in the text
 * colour (white in dark, Addendum A) over a soft shadow cast from the pill's own shape in light (S7-16), and a 56 dp
 * container disc with no shadow, both on one centre line (N06).
 */
@Composable
internal fun RvFloatingActions(label: String, onClick: () -> Unit, @DrawableRes icon: Int, iconLabel: String, onIcon: () -> Unit, modifier: Modifier = Modifier) {
    val colors = Rv.colors
    val fill = if (colors.dark) Color.White else colors.text
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(RvNodesSize.ctaGap), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .weight(1f)
                .heightIn(min = RvNodesSize.cta)
                .rvSoftShadow(CircleShape, RvNodesSize.ctaShadow, RvNodesSize.ctaShadowDrop, RvAlpha.CTA_SHADOW)
                .clip(CircleShape)
                .background(fill)
                .rvPress(CircleShape, Role.Button, onClick)
                .padding(vertical = RvSpace.tight),
            contentAlignment = Alignment.Center,
        ) { RvText(label, RvNodesType.cta, color = colors.page, align = TextAlign.Center) }
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
