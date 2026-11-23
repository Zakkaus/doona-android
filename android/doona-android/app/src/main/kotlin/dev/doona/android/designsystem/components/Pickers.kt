package dev.doona.android.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import dev.doona.android.designsystem.theme.DoonaTheme
import dev.doona.android.designsystem.theme.scripted

// The old pickers, which the sample has no counterpart for: they stay, restyled by the theme, until the page lanes
// rebuild their screens with the filter chip ([DoonaFilterChip]).

/**
 * A picker: value and down arrow on a control-height row. Quiet, it has no fill and no padding, so its text starts on
 * the content edge and its arrow ends on it; [DoonaFilterPicker] is the filled one of a filter bar.
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
        DropDownGlyph(c.text)
    }
}
