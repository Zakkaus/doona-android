package dev.doona.android.designsystem.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import dev.doona.android.designsystem.theme.DoonaTheme

internal object DoonaRowDefaults {
    val minHeight get() = DoonaTheme.size.touch
}

internal object DoonaCardDefaults {
    val inset get() = DoonaTheme.spacing.cardInset
    val gap get() = DoonaTheme.spacing.block
}

/** Card (§7): padding 16, gap 12, radius 16, 1 px border, base fill on the sheet. */
@Composable
fun DoonaCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = DoonaTheme.colors
    val shape = DoonaTheme.shape.card
    Column(
        modifier = modifier
            .layoutRole(LayoutRole.Card)
            .clip(shape)
            .background(c.card)
            .border(DoonaTheme.size.border, c.cardBorder, shape)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(DoonaCardDefaults.inset),
        verticalArrangement = Arrangement.spacedBy(DoonaCardDefaults.gap),
        content = content,
    )
}

/**
 * A row of a card or of the sheet: at least 48 dp high, its content centred vertically. A switch, a picker or a text
 * with a trailing control sits in one.
 */
@Composable
fun DoonaRow(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier.layoutRole(LayoutRole.Row).heightIn(min = DoonaRowDefaults.minHeight),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/** Control card (§7): tinted icon and 14/500 label, controls at the end of the same row. */
@Composable
fun DoonaControlCard(@DrawableRes icon: Int, iconTint: Color, label: String, trailing: @Composable RowScope.() -> Unit) {
    DoonaCard(Modifier.fillMaxWidth()) {
        DoonaRow {
            DoonaIcon(icon, iconTint)
            Spacer(Modifier.width(DoonaTheme.spacing.item))
            Text(label, style = DoonaTheme.type.label, color = DoonaTheme.colors.text.primary, maxLines = 1, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(DoonaTheme.spacing.block))
            trailing()
        }
    }
}

/** Metric tiles side by side, as high as the highest. */
@Composable
fun DoonaTileRow(content: @Composable RowScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(DoonaTheme.spacing.block),
        content = content,
    )
}

/** `.rp-tile-head`: the 12/16 caption of a metric tile, in the secondary colour. */
@Composable
fun DoonaTileCaption(text: String) {
    DoonaText(text, style = DoonaTheme.type.caption, color = DoonaTheme.colors.text.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

/**
 * Dashboard tile (§7): a head of tinted icon and [caption] at control height, then the [value]. With [onClick] the whole
 * tile is the touch target, so the head keeps doona's control height.
 */
@Composable
fun DoonaMetricTile(
    @DrawableRes icon: Int,
    tint: Color,
    caption: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    value: @Composable () -> Unit,
) {
    DoonaCard(modifier, onClick = onClick) {
        Row(Modifier.heightIn(min = DoonaTheme.size.control), verticalAlignment = Alignment.CenterVertically) {
            DoonaIcon(icon, tint)
            DoonaHGap(DoonaTheme.spacing.item)
            caption()
        }
        value()
    }
}
