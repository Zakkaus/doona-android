package dev.doona.android.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
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
import dev.doona.android.R
import dev.doona.android.designsystem.theme.DoonaTheme

/**
 * A checkable row for a long list, an app per row: an optional [leading] (an app icon, laid out in a box of
 * [DoonaSize.appIcon]), the [title] over an optional [subtitle], each on up to two lines before an ellipsis, and a
 * checkbox at the end (§7, S2 Checkbox: a 2 dp outline, filled with the accent and a checkmark when checked). The whole
 * row toggles and is hit over its full width and at least 48 dp of height; a screen reader reads the title, the
 * subtitle and On or Off, as it does for [DoonaSwitchRow].
 */
@Composable
fun DoonaCheckRow(
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    leading: (@Composable () -> Unit)? = null,
) {
    val c = DoonaTheme.colors
    val state = stringResource(if (checked) R.string.ui_on else R.string.ui_off)
    Row(
        modifier
            .layoutRole(LayoutRole.Row)
            .fillMaxWidth()
            .heightIn(min = DoonaRowDefaults.minHeight)
            .toggleable(checked, role = Role.Checkbox, onValueChange = onChange)
            .semantics { stateDescription = state },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            Box(Modifier.size(DoonaTheme.size.appIcon), contentAlignment = Alignment.Center) { leading() }
            DoonaHGap(DoonaTheme.spacing.block)
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = DoonaTheme.type.label, color = c.text.primary, maxLines = LABEL_LINES, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) {
                Text(subtitle, style = DoonaTheme.type.caption, color = c.text.secondary, maxLines = LABEL_LINES, overflow = TextOverflow.Ellipsis)
            }
        }
        DoonaHGap(DoonaTheme.spacing.block)
        CheckBox(checked)
    }
}

@Composable
private fun CheckBox(checked: Boolean) {
    val c = DoonaTheme.colors
    val shape = DoonaTheme.shape.checkbox
    Box(
        Modifier
            .size(DoonaTheme.size.checkbox)
            .clip(shape)
            .then(if (checked) Modifier.background(c.accent) else Modifier.border(DoonaTheme.size.borderStrong, c.boxLine, shape)),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) DoonaIcon(R.drawable.ic_checkmark, c.onAccent, size = DoonaTheme.size.iconInline)
    }
}
