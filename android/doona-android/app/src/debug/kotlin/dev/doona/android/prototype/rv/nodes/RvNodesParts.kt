package dev.doona.android.prototype.rv.nodes

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
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
import dev.doona.android.prototype.rv.RvGlyph
import dev.doona.android.prototype.rv.RvInter
import dev.doona.android.prototype.rv.RvSpace
import dev.doona.android.prototype.rv.RvText
import dev.doona.android.prototype.rv.data.RvNode
import dev.doona.android.prototype.rv.rvPress
import kotlin.math.abs

/** Sizes of the 節點 page and the node detail page, after revolut-android-measured.md (its section in brackets). */
internal object RvNodesSize {
    /** The 'Top movers' grid's flags, and the list rows' (§7: 40 dp logo circles). */
    val topFlag = 54.dp
    val rowFlag = 40.dp
    val detailFlag = 48.dp

    /** A card's own header (§7 listSubheader, 48 dp): text top-left, this far in. */
    val cardHeaderTop = 16.dp
    val cardHeaderBottom = 12.dp

    /** The Analytics filter chips (§8 small action button: 40 dp, a 16 dp chevron). */
    val chip = 40.dp
    val chipStart = 16.dp
    val chipEnd = 12.dp
    val chipGlyph = 16.dp

    /** 'Top movers': flag to name, and between the grid's two lines. */
    val topLabelGap = 8.dp
    val topRowGap = 16.dp

    /** The ▲▼ before a change (§7: a filled 8 dp triangle in the number's colour). */
    val delta = 8.dp

    /** 'Market closed', the status chip: a tinted pill with a glyph. */
    val status = 32.dp
    val statusPad = 12.dp
    val statusGlyph = 16.dp

    /** The floating 'Get started' pill and the round button beside it (§8: 52 dp, 8 dp apart, 16 dp above the inset). */
    val cta = 52.dp
    val ctaGap = 8.dp
    val ctaShadow = 6.dp
    val ctaGlyph = 24.dp

    /** The fade from the page into the floating actions, above them. */
    val ctaFade = 24.dp
}

/** The pages' own type steps, set in Inter or Noto as [RvText] does. */
internal object RvNodesType {
    /** A card's own header: Revolut's grey 'Top movers >' (§7: 19 sp medium on a 423 dp screen, 17 sp on ours). */
    val cardHeader = TextStyle(fontFamily = RvInter, fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 22.sp)

    /** The name under a 'Top movers' flag and its latency. */
    val topName = TextStyle(fontFamily = RvInter, fontWeight = FontWeight.Medium, fontSize = 15.sp, lineHeight = 20.sp)
    val topValue = TextStyle(fontFamily = RvInter, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 20.sp, fontFeatureSettings = "tnum")

    /** The floating pill's label (§8: 22 sp on a 423 dp screen, 20 sp on ours). */
    val cta = TextStyle(fontFamily = RvInter, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp)
}

/** The selected list row's tint: the scheme's primary, faint. */
@Composable
@ReadOnlyComposable
internal fun selectedRowTint(): Color = Rv.colors.positive.copy(alpha = if (Rv.colors.dark) SELECTED_DARK else SELECTED_LIGHT)

/** A card's own header, top-left in grey, as Revolut's 'Top movers' and 'Your trips'. */
@Composable
internal fun RvCardHeader(text: String) {
    RvText(
        text,
        RvNodesType.cardHeader,
        Modifier.fillMaxWidth().padding(start = RvSpace.rowPadding, end = RvSpace.rowPadding, top = RvNodesSize.cardHeaderTop, bottom = RvNodesSize.cardHeaderBottom),
        color = Rv.colors.textSecondary,
    )
}

/** Revolut's Analytics filter chip ('Personal ▾', 'This month'): a container pill, the label, and an optional ▾. */
@Composable
internal fun RvFilterChip(label: String, menu: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .minimumInteractiveComponentSize()
            .heightIn(min = RvNodesSize.chip)
            .clip(CircleShape)
            .background(Rv.colors.segmentTrack)
            .rvPress(CircleShape, Role.Button, onClick)
            .padding(start = RvNodesSize.chipStart, end = if (menu) RvNodesSize.chipEnd else RvNodesSize.chipStart, top = RvSpace.tight, bottom = RvSpace.tight),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(RvSpace.tight),
    ) {
        RvText(label, Rv.type.rowTitle)
        if (menu) RvGlyph(R.drawable.rv_nodes_ic_expand, RvNodesSize.chipGlyph, Rv.colors.text)
    }
}

/** A latency's colour: the fixed good, fair and slow hues. */
@Composable
@ReadOnlyComposable
internal fun latencyColor(ms: Int?): Color = Rv.colors.quality(latencyTone(ms))

/** [ms] as the app writes a latency, or a dash for a test that timed out. */
@Composable
internal fun latencyText(ms: Int?): String = ms?.let { stringResource(R.string.latency_ms, it) } ?: RV_NO_VALUE

/** The latency over its change since the test before: ▲ in the slow hue when it rose, ▼ in the good hue when it fell, a dash when it held. */
@Composable
internal fun RvLatencyChange(node: RvNode) {
    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(RvSpace.tight / 2)) {
        RvText(latencyText(node.latencyMs), Rv.type.rowValue, align = TextAlign.End)
        node.changeMs?.let { change ->
            val color = when {
                change > 0 -> Rv.colors.slow
                change < 0 -> Rv.colors.good
                else -> Rv.colors.textSecondary
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(RvSpace.tight)) {
                if (change != 0) RvDeltaTriangle(up = change > 0, color = color)
                // No change is not a measurement, so it shows a dash.
                RvText(if (change == 0) RV_NO_VALUE else stringResource(R.string.latency_ms, abs(change)), Rv.type.rowSub, color = color)
            }
        }
    }
}

/** Revolut's filled delta triangle, pointing [up] or down. */
@Composable
internal fun RvDeltaTriangle(up: Boolean, color: Color) {
    Canvas(Modifier.size(RvNodesSize.delta)) {
        val h = size.width * TRIANGLE_HEIGHT
        val top = (size.height - h) / 2
        val path = Path().apply {
            if (up) {
                moveTo(size.width / 2, top)
                lineTo(size.width, top + h)
                lineTo(0f, top + h)
            } else {
                moveTo(0f, top)
                lineTo(size.width, top)
                lineTo(size.width / 2, top + h)
            }
            close()
        }
        drawPath(path, color)
    }
}

/** Revolut's 'Market closed' chip: a pill tinted in [color], its glyph and label in that colour. */
@Composable
internal fun RvStatusChip(@DrawableRes icon: Int, label: String, color: Color) {
    Row(
        Modifier.heightIn(min = RvNodesSize.status).clip(CircleShape).background(color.copy(alpha = STATUS_TINT)).padding(horizontal = RvNodesSize.statusPad, vertical = RvSpace.tight),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(RvSpace.tight + RvSpace.tight / 2),
    ) {
        RvGlyph(icon, RvNodesSize.statusGlyph, color)
        RvText(label, Rv.type.action, color = color)
    }
}

/**
 * Revolut's floating 'Get started' with the round button beside it ('Buy' and more): a full-width pill in the text colour
 * over a soft shadow in light, and a container disc with no shadow.
 */
@Composable
internal fun RvFloatingActions(label: String, onClick: () -> Unit, @DrawableRes icon: Int, iconLabel: String, onIcon: () -> Unit, modifier: Modifier = Modifier) {
    val colors = Rv.colors
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(RvNodesSize.ctaGap)) {
        val lift = if (colors.raisedShadow) Modifier.shadow(RvNodesSize.ctaShadow, CircleShape) else Modifier
        Box(
            Modifier.weight(1f).heightIn(min = RvNodesSize.cta).then(lift).clip(CircleShape).background(colors.text).rvPress(CircleShape, Role.Button, onClick).padding(vertical = RvSpace.tight),
            contentAlignment = Alignment.Center,
        ) { RvText(label, RvNodesType.cta, color = colors.page, align = TextAlign.Center) }
        Box(
            Modifier
                .size(RvNodesSize.cta)
                .clip(CircleShape)
                .background(colors.roundButton)
                .rvPress(CircleShape, Role.Button, onIcon)
                .semantics { contentDescription = iconLabel },
            contentAlignment = Alignment.Center,
        ) { RvGlyph(icon, RvNodesSize.ctaGlyph, colors.text) }
    }
}

/** An equilateral triangle is this share of its width high. */
private const val TRIANGLE_HEIGHT = 0.866f
private const val STATUS_TINT = 0.14f
private const val SELECTED_LIGHT = 0.07f
private const val SELECTED_DARK = 0.12f
