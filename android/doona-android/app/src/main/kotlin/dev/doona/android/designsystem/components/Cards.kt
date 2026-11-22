package dev.doona.android.designsystem.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
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
import dev.doona.android.designsystem.theme.DoonaTheme

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

/** Control card (§7): tinted icon and 14/500 label, controls at the end of the same row. */
@Composable
fun DoonaControlCard(@DrawableRes icon: Int, iconTint: Color, label: String, trailing: @Composable RowScope.() -> Unit) {
    DoonaCard(Modifier.fillMaxWidth()) {
        Row(Modifier.heightIn(min = DoonaTheme.size.touch), verticalAlignment = Alignment.CenterVertically) {
            DoonaIcon(icon, iconTint)
            Spacer(Modifier.width(DoonaTheme.spacing.item))
            Text(label, style = DoonaTheme.type.label, color = DoonaTheme.colors.text.primary, maxLines = 1, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(DoonaTheme.spacing.block))
            trailing()
        }
    }
}
