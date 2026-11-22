package dev.doona.android.designsystem.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import dev.doona.android.R
import dev.doona.android.designsystem.theme.DoonaTheme

/**
 * A row of a settings card (the sample's 設定): a bare 24 dp [icon], a title over an optional [subtitle], and [trailing]
 * content. The glyph and the trailing control centre on the first line of text, which is where Revolut's rows keep
 * them when the subtitle wraps. The row's touch behaviour comes in [modifier], outside the padding, so the whole row is
 * the target. A row is 58 dp with one line and keeps its natural height with more (Addendum A); no dividers.
 */
@Composable
fun DoonaSettingsRow(
    title: String,
    modifier: Modifier = Modifier,
    @DrawableRes icon: Int? = null,
    subtitle: String? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val d = DoonaSettingsRowDefaults
    Row(
        modifier
            .layoutRole(LayoutRole.ListRow, mergeDescendants = true)
            .fillMaxWidth()
            .heightIn(min = DoonaTheme.size.rowOneLine)
            .padding(horizontal = DoonaTheme.spacing.rowPadding, vertical = d.verticalPad),
        verticalAlignment = Alignment.Top,
    ) {
        icon?.let {
            FirstLine { DoonaGlyph(it, d.glyph, DoonaTheme.colors.text) }
            Spacer(Modifier.width(DoonaTheme.spacing.rowGap))
        }
        Column(Modifier.weight(1f)) {
            LineText(title, DoonaTheme.type.rowTitle, maxLines = d.TITLE_LINES)
            subtitle?.let { LineText(it, DoonaTheme.type.rowSub, color = DoonaTheme.colors.textSecondary, maxLines = d.SUBTITLE_LINES) }
        }
        trailing?.let {
            Spacer(Modifier.width(DoonaTheme.spacing.trailingGap))
            FirstLine(it)
        }
    }
}

@Composable
private fun FirstLine(content: @Composable () -> Unit) {
    // The title's first line: 26 dp, or the line a large font makes of it.
    val line = max(DoonaSettingsRowDefaults.titleLine, lineHeightOf(DoonaTheme.type.rowTitle))
    Box(Modifier.height(line), contentAlignment = Alignment.Center) { content() }
}

/** A settings row that opens something: an optional [value] in secondary text, then the chevron. */
@Composable
fun DoonaNavRow(title: String, onClick: () -> Unit, modifier: Modifier = Modifier, @DrawableRes icon: Int? = null, value: String? = null) {
    DoonaSettingsRow(title, modifier.press(RectangleShape, Role.Button, onClick), icon) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            value?.let {
                LineText(it, DoonaTheme.type.settingValue, color = DoonaTheme.colors.textSecondary)
                Spacer(Modifier.width(DoonaTheme.spacing.tight))
            }
            DoonaChevron()
        }
    }
}

/** A settings row that only shows a [value]. */
@Composable
fun DoonaValueRow(title: String, value: String, modifier: Modifier = Modifier, @DrawableRes icon: Int? = null) {
    DoonaSettingsRow(title, modifier, icon) { LineText(value, DoonaTheme.type.settingValue, color = DoonaTheme.colors.textSecondary) }
}

/**
 * A settings row whose whole width toggles its [DoonaSwitch] (Addendum A): a tap anywhere on it calls [onChange], and a
 * screen reader reads the title as the switch's name, followed by On or Off.
 */
@Composable
fun DoonaSwitchRow(
    title: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    @DrawableRes icon: Int? = null,
    subtitle: String? = null,
) {
    val state = stringResource(if (checked) R.string.ui_on else R.string.ui_off)
    val toggle = modifier
        .toggleable(checked, interactionSource = null, indication = ripple(color = DoonaTheme.colors.stateLayer), role = Role.Switch, onValueChange = onChange)
        .semantics { stateDescription = state }
    DoonaSettingsRow(title, toggle, icon, subtitle) { DoonaSwitch(checked) }
}

/** A row of a radio list: the title, and the radio at the end; the whole row selects it. */
@Composable
fun DoonaRadioRow(title: String, selected: Boolean, onSelect: () -> Unit, modifier: Modifier = Modifier) {
    val choose = modifier.selectable(selected, interactionSource = null, indication = ripple(color = DoonaTheme.colors.stateLayer), role = Role.RadioButton, onClick = onSelect)
    DoonaSettingsRow(title, choose) { DoonaRadio(selected) }
}

/** The settings row's own metrics (the sample's RvSettingsSpace and RvSettingsSize). */
internal object DoonaSettingsRowDefaults {
    val verticalPad = 16.dp
    val glyph = 24.dp

    /** The first line of a row's title: the glyph and the trailing control centre on it. */
    val titleLine = 26.dp
    const val TITLE_LINES = 2
    const val SUBTITLE_LINES = 2
}
