package dev.doona.android.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.doona.android.R
import dev.doona.android.designsystem.theme.DoonaTheme
import dev.doona.android.designsystem.theme.scripted

/**
 * Tag chip (§7): control height, padding 12, radius 8, the disc fill, 14/400. Without [onClick] it is a label; with it,
 * it is hit over 48 dp, as every control.
 */
@Composable
fun DoonaTagChip(text: String, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    Box(
        modifier
            .layoutRole(LayoutRole.Control)
            .heightIn(min = DoonaTheme.size.control)
            .clip(DoonaTheme.shape.control)
            .background(DoonaTheme.colors.discOnCard)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(horizontal = DoonaTheme.spacing.fillInset),
        contentAlignment = Alignment.Center,
    ) {
        Text(scripted(text), style = DoonaTheme.type.body, color = DoonaTheme.colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * Segmented control (§7): overlay track, radius 8, gap 4, 36 high; items 14/400 tabular, padding 0 × 8 on the
 * phone; the selected item is a thumb filled with the accent, its label on the accent. A filled thumb reads as a choice
 * made, where an outline reads as the focus ring. The layout is the visible track: Compose hits
 * each item over 48 dp (the minimum touch target) without adding space around it, so the gaps of a page are measured
 * to what is drawn.
 */
@Composable
fun DoonaSegmented(items: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val c = DoonaTheme.colors
    Row(
        modifier
            .layoutRole(LayoutRole.Control)
            .heightIn(min = DoonaTheme.size.control)
            .clip(DoonaTheme.shape.control)
            .background(c.discOnCard)
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(DoonaTheme.spacing.tight),
    ) {
        items.forEachIndexed { i, label ->
            val on = i == selected
            Box(
                Modifier
                    .heightIn(min = DoonaTheme.size.control)
                    .widthIn(min = DoonaTheme.size.control)
                    .clip(DoonaTheme.shape.control)
                    .then(if (on) Modifier.background(c.positive) else Modifier)
                    .selectable(on, role = Role.RadioButton, onClick = { onSelect(i) })
                    .padding(horizontal = DoonaTheme.spacing.item),
                contentAlignment = Alignment.Center,
            ) {
                Text(scripted(label), style = DoonaTheme.type.bodyTabular, color = if (on) c.onPositive else c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/**
 * Switch row: [content] at the start, a switch at the end (§7: track 32 × 16, radius 8, see [DoonaSwitchDefaults] and
 * [SwitchTrack]). The whole row is the switch: it toggles anywhere, is hit over its full 48 dp
 * height, and a screen reader reads [content] as its name followed by On or Off.
 */
@Composable
fun DoonaSwitchRow(checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val state = stringResource(if (checked) R.string.ui_on else R.string.ui_off)
    Row(
        modifier
            .layoutRole(LayoutRole.Row)
            .fillMaxWidth()
            .heightIn(min = DoonaRowDefaults.minHeight)
            .toggleable(checked, role = Role.Switch, onValueChange = onChange)
            .semantics { stateDescription = state },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f)) { content() }
        Spacer(Modifier.width(DoonaTheme.spacing.block))
        SwitchTrack(checked)
    }
}

/** The switch's own metrics (§7): a 32 × 16 track, a 12 knob inset 2, so the knob travels 16. */
internal object DoonaSwitchDefaults {
    val trackWidth = 32.dp
    val trackHeight = 16.dp
    val knob = 12.dp
    val knobInset = 2.dp
}

/**
 * The sample's switch colours on U1's geometry: on, the accent knob over the accent's tint, outlined in the accent where
 * no tint reaches 3:1 on the card; off, the light knob with its ring over the control-grey track.
 */
@Composable
private fun SwitchTrack(checked: Boolean) {
    val c = DoonaTheme.colors
    val shape = DoonaTheme.shape.control
    Box(
        Modifier
            .size(DoonaSwitchDefaults.trackWidth, DoonaSwitchDefaults.trackHeight)
            .clip(shape)
            .background(if (checked) c.switchOnTrack else c.control)
            .then(c.switchOnRim?.takeIf { checked }?.let { Modifier.border(DoonaTheme.size.ring, it, shape) } ?: Modifier)
            .padding(DoonaSwitchDefaults.knobInset),
        contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .size(DoonaSwitchDefaults.knob)
                .clip(DoonaTheme.shape.pill)
                .background(if (checked) c.switchOnThumb else c.switchOffThumb)
                .then(if (checked) Modifier else Modifier.border(DoonaTheme.size.ring, c.control, DoonaTheme.shape.pill)),
        )
    }
}

/**
 * A picker: value and chevron on a control-height row. Quiet, it has no fill and no padding, so its text starts on the
 * content edge and its chevron ends on it; [DoonaFilterPicker] is the filled one of a filter bar.
 */
@Composable
fun DoonaPicker(value: String, onClick: () -> Unit, modifier: Modifier = Modifier) = PickerRow(value, onClick, modifier, filled = false)

/** The picker of a filter bar: a control with the disc fill and 12 dp of padding at its sides. */
@Composable
fun DoonaFilterPicker(value: String, onClick: () -> Unit, modifier: Modifier = Modifier) = PickerRow(value, onClick, modifier, filled = true)

@Composable
private fun PickerRow(value: String, onClick: () -> Unit, modifier: Modifier, filled: Boolean) {
    val c = DoonaTheme.colors
    Row(
        modifier
            .layoutRole(LayoutRole.Control)
            .heightIn(min = DoonaTheme.size.control)
            .clip(DoonaTheme.shape.control)
            .then(if (filled) Modifier.background(c.discOnCard) else Modifier)
            .clickable(role = Role.Button, onClick = onClick)
            .then(if (filled) Modifier.padding(horizontal = DoonaTheme.spacing.fillInset) else Modifier),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DoonaTheme.spacing.item),
    ) {
        Text(scripted(value), style = DoonaTheme.type.body, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        DoonaChevron(ChevronDirection.Down, c.text)
    }
}
