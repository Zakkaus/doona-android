package dev.doona.android.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import dev.doona.android.R
import dev.doona.android.designsystem.theme.DoonaTheme

/** Tag chip (§7): control height, padding 12, radius 8, overlay fill, 14/400. */
@Composable
fun DoonaTagChip(text: String, onClick: () -> Unit = {}) {
    Box(
        Modifier
            .layoutRole(LayoutRole.Control)
            .heightIn(min = DoonaTheme.size.touch)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .height(DoonaTheme.size.control)
                .clip(DoonaTheme.shape.control)
                .background(DoonaTheme.colors.control)
                .padding(horizontal = DoonaTheme.spacing.block),
            contentAlignment = Alignment.Center,
        ) {
            Text(text, style = DoonaTheme.type.body, color = DoonaTheme.colors.text.primary, maxLines = 1)
        }
    }
}

/**
 * Segmented control (§7): overlay track, radius 8, gap 4, 36 high; items 14/400 tabular, padding 0 × 8 on the
 * phone; the selected item is a surface box with a 2 px accent border. The row keeps a 48 dp touch height.
 */
@Composable
fun DoonaSegmented(items: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val c = DoonaTheme.colors
    Box(modifier.layoutRole(LayoutRole.Control).heightIn(min = DoonaTheme.size.touch), contentAlignment = Alignment.CenterStart) {
        Row(
            Modifier
                .height(DoonaTheme.size.control)
                .clip(DoonaTheme.shape.control)
                .background(c.control)
                .selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(DoonaTheme.spacing.tight),
        ) {
            items.forEachIndexed { i, label ->
                val on = i == selected
                Box(
                    Modifier
                        .height(DoonaTheme.size.control)
                        .widthIn(min = DoonaTheme.size.control)
                        .clip(DoonaTheme.shape.control)
                        .then(if (on) Modifier.background(c.surface).border(DoonaTheme.size.borderStrong, c.accent, DoonaTheme.shape.control) else Modifier)
                        .selectable(on, role = Role.RadioButton, onClick = { onSelect(i) })
                        .padding(horizontal = DoonaTheme.spacing.item),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(label, style = DoonaTheme.type.body.copy(fontFeatureSettings = DoonaTheme.type.tabular), color = c.text.primary, maxLines = 1, softWrap = false)
                }
            }
        }
    }
}

/** Switch (§7): track 32 × 16, radius 8, hl-high off / accent on; 12 px surface knob inset 2, travels 16. */
@Composable
fun DoonaSwitch(checked: Boolean, onChange: (Boolean) -> Unit, label: String, modifier: Modifier = Modifier) {
    val c = DoonaTheme.colors
    val s = DoonaTheme.size
    Box(
        modifier
            .size(s.touch)
            .toggleable(checked, role = Role.Switch, onValueChange = onChange)
            .semantics { contentDescription = label },
        // The visible track keeps to the end of its 48 dp target, so it lines up with the content edge.
        contentAlignment = Alignment.CenterEnd,
    ) {
        Box(
            Modifier
                .size(s.switchWidth, s.switchHeight)
                .clip(DoonaTheme.shape.control)
                .background(if (checked) c.accent else c.switchOff)
                .padding(DoonaTheme.spacing.hairline),
            contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
        ) {
            Box(
                Modifier
                    .size(s.switchKnob)
                    .clip(DoonaTheme.shape.pill)
                    .background(c.switchKnob),
            )
        }
    }
}

/** A quiet picker: value and chevron, 48 dp touch height (doona's quiet button, §7). */
@Composable
fun DoonaPicker(value: String, onClick: () -> Unit, filled: Boolean = false, leading: (@Composable () -> Unit)? = null) {
    val c = DoonaTheme.colors
    Box(Modifier.layoutRole(LayoutRole.Control).heightIn(min = DoonaTheme.size.touch).clickable(role = Role.Button, onClick = onClick), contentAlignment = Alignment.Center) {
        Row(
            Modifier
                .height(DoonaTheme.size.control)
                .clip(DoonaTheme.shape.control)
                // A quiet picker has no fill, so it drops the inline padding and its text lines up with the content edge.
                .then(if (filled) Modifier.background(c.control).padding(horizontal = DoonaTheme.spacing.block) else Modifier),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(DoonaTheme.spacing.item),
        ) {
            leading?.invoke()
            Text(value, style = DoonaTheme.type.body, color = c.text.primary, maxLines = 1)
            DoonaIcon(R.drawable.ic_chevron_down, c.text.primary, Modifier.layoutRole(LayoutRole.Chevron), DoonaTheme.size.iconInline)
        }
    }
}
