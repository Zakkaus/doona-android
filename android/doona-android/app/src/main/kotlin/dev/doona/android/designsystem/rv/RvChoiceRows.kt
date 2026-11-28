package dev.doona.android.designsystem.rv

import androidx.annotation.DrawableRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer

// The rows whose whole width sets a control: the switch row and the radio row, both on RvRow.

/** A card row whose whole width toggles its switch: [icon]'s disc, [title] over an optional [subtitle], the switch at the end. */
@Composable
internal fun RvSwitchRow(@DrawableRes icon: Int, title: String, checked: Boolean, subtitle: String? = null, onChange: ((Boolean) -> Unit)?) =
    RvSwitchRow({ RvIconDisc(icon) }, title, checked, subtitle, onChange = onChange)

/**
 * [RvSwitchRow] with its own [leading] visual (an app's icon); [titleKeepWords] and [subtitleKeepWords] are [RvRow]'s,
 * false for a name or an identifier (an app's label and package).
 */
@Composable
internal fun RvSwitchRow(
    leading: @Composable () -> Unit,
    title: String,
    checked: Boolean,
    subtitle: String? = null,
    titleKeepWords: Boolean = true,
    subtitleKeepWords: Boolean = true,
    onChange: ((Boolean) -> Unit)?,
) {
    val fade = if (onChange != null) Modifier else Modifier.graphicsLayer { alpha = RvAlpha.DISABLED }
    RvRow(leading, title, subtitle, fade.rvRowToggle(checked, onChange), titleKeepWords = titleKeepWords, subtitleKeepWords = subtitleKeepWords, endFirstLine = true) { RvSwitch(checked) }
}

/** A card row whose whole width picks it: [icon]'s disc, [title] over an optional [subtitle], the radio at the end. */
@Composable
internal fun RvRadioRow(
    @DrawableRes icon: Int,
    title: String,
    selected: Boolean,
    subtitle: String? = null,
    enabled: Boolean = true,
    onSelect: () -> Unit,
) = RvRadioRow({ RvIconDisc(icon) }, title, selected, subtitle, enabled, onSelect = onSelect)

/**
 * A row of a radio list (in a card with a selectable group, [RvSettingsRadioCard]): its [leading] visual, none in a plain
 * list of choices, whose text then starts on the row's padding; [title] over an optional [subtitle]; the radio at the end,
 * on the title's first line with a subtitle (R11). A choice that cannot be made is not [enabled]: faded to
 * [RvAlpha.DISABLED], it takes no press. [titleKeepWords] and [subtitleKeepWords] are [RvRow]'s: false for a name or an
 * identifier (a DNS server's address).
 */
@Composable
internal fun RvRadioRow(
    leading: (@Composable () -> Unit)?,
    title: String,
    selected: Boolean,
    subtitle: String? = null,
    enabled: Boolean = true,
    titleKeepWords: Boolean = true,
    subtitleKeepWords: Boolean = true,
    onSelect: () -> Unit,
) {
    val fade = if (enabled) Modifier else Modifier.graphicsLayer { alpha = RvAlpha.DISABLED }
    val pick = fade.rvRowSelect(selected, onSelect, enabled = enabled)
    RvRow(leading, title, subtitle, pick, titleKeepWords = titleKeepWords, subtitleKeepWords = subtitleKeepWords, endFirstLine = true) { RvRadio(selected) }
}
