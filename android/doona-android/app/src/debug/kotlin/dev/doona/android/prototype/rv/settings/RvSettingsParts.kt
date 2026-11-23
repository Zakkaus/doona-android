package dev.doona.android.prototype.rv.settings

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import dev.doona.android.prototype.rv.RV_ROW_SUBTITLE_LINES
import dev.doona.android.prototype.rv.RV_ROW_TITLE_LINES
import dev.doona.android.prototype.rv.Rv
import dev.doona.android.prototype.rv.RvCard
import dev.doona.android.prototype.rv.RvChevron
import dev.doona.android.prototype.rv.RvGlyph
import dev.doona.android.prototype.rv.RvPart
import dev.doona.android.prototype.rv.RvRadio
import dev.doona.android.prototype.rv.RvSectionTitle
import dev.doona.android.prototype.rv.RvSize
import dev.doona.android.prototype.rv.RvSpace
import dev.doona.android.prototype.rv.RvSwitch
import dev.doona.android.prototype.rv.RvText
import dev.doona.android.prototype.rv.rvRowIndication
import dev.doona.android.prototype.rv.rvRowPress
import dev.doona.android.prototype.rv.scripted

/** Text in a role; CJK runs in Noto, the rest in Inter. [maxLines] defaults to one, ending in an ellipsis. */
@Composable
internal fun RvSettingsText(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = Rv.colors.text,
    align: TextAlign = TextAlign.Start,
    maxLines: Int = 1,
) {
    BasicText(scripted(text), modifier, style.copy(color = color, textAlign = align), overflow = TextOverflow.Ellipsis, maxLines = maxLines)
}

/** A settings group card: the library's card, 20 dp round as every card (N-06), rows edge to edge, no divider or shadow. */
@Composable
internal fun RvSettingsCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) = RvCard(modifier, content)

/**
 * The title of a group of cards: the shared outside section title, 24 dp above, 12 dp between it and its card; the
 * [first] under a page header adds nothing above itself.
 */
@Composable
internal fun RvSettingsSection(title: String, modifier: Modifier = Modifier, first: Boolean = false) {
    RvSectionTitle(title, modifier, first = first)
}

/**
 * A row of a card: a 24 dp glyph, a title over an optional subtitle, and trailing content, 58 dp tall with one line and
 * 82 with a subtitle, as [RvRow]; a subtitle that wraps makes it taller. The text block centres in that height, and the
 * glyph and the trailing control centre on its first line, which is where Revolut's rows keep them when the subtitle
 * wraps. The row's touch behaviour comes in [modifier], outside the padding, so the whole row is the target.
 */
@Composable
internal fun RvSettingsRow(
    title: String,
    modifier: Modifier = Modifier,
    @DrawableRes icon: Int? = null,
    subtitle: String? = null,
    titleStyle: TextStyle = Rv.type.rowTitle,
    trailing: (@Composable () -> Unit)? = null,
) {
    Box(
        modifier
            .fillMaxWidth()
            .testTag(RvPart.ROW)
            .heightIn(min = if (subtitle == null) RvSize.rowOneLine else RvSize.rowTwoLine)
            .padding(horizontal = RvSettingsSpace.rowInset),
        contentAlignment = Alignment.CenterStart,
    ) {
        Row(Modifier.fillMaxWidth().padding(vertical = RvSpace.rowTextInset), verticalAlignment = Alignment.Top) {
            icon?.let {
                FirstLine { RvGlyph(it, RvSettingsSize.glyph, Rv.colors.text) }
                Spacer(Modifier.width(RvSettingsSpace.glyphGap))
            }
            Column(Modifier.weight(1f)) {
                RvText(title, titleStyle, maxLines = RV_ROW_TITLE_LINES)
                subtitle?.let { RvText(it, Rv.type.rowSub, color = Rv.colors.textSecondary, maxLines = RV_ROW_SUBTITLE_LINES) }
            }
            trailing?.let {
                Spacer(Modifier.width(RvSettingsSpace.trailingGap))
                FirstLine(it)
            }
        }
    }
}

@Composable
private fun FirstLine(content: @Composable () -> Unit) {
    Box(Modifier.height(RvSettingsSize.titleLine), contentAlignment = Alignment.Center) { content() }
}

/**
 * A row that opens something, then a chevron; its optional [subtitle] is the row's second line, so the row is the
 * two-line 82 dp one: a value, as Revolut's Appearance rows show Theme over "Light - Glass" (§13.3), or what the row
 * leads to. A row of a plain list (the licences) has no [icon], so its text starts on the card's padding, as DNS's 自訂.
 * A title that is source text (a skipped declaration) takes [titleStyle] with Inter's ligatures off (`rvVerbatim`).
 */
@Composable
internal fun RvSettingsNavRow(
    title: String,
    @DrawableRes icon: Int?,
    onClick: () -> Unit,
    subtitle: String? = null,
    titleStyle: TextStyle = Rv.type.rowTitle,
) {
    RvSettingsRow(title, Modifier.rvRowPress(Role.Button, onClick), icon, subtitle = subtitle, titleStyle = titleStyle) { RvChevron() }
}

/** A row that only shows a [value], as its second line. */
@Composable
internal fun RvSettingsValueRow(title: String, @DrawableRes icon: Int, value: String) {
    RvSettingsRow(title, icon = icon, subtitle = value)
}

/** A row whose whole width toggles its switch. */
@Composable
internal fun RvSettingsSwitchRow(title: String, @DrawableRes icon: Int, checked: Boolean, onChange: (Boolean) -> Unit, subtitle: String? = null) {
    val toggle = Modifier.toggleable(
        value = checked,
        interactionSource = null,
        indication = rvRowIndication(),
        role = Role.Switch,
        onValueChange = onChange,
    )
    RvSettingsRow(title, toggle, icon, subtitle) { RvSwitch(checked) }
}

/** A row of a radio list: the title over an optional [subtitle], and the radio at the end. */
@Composable
internal fun RvSettingsRadioRow(title: String, selected: Boolean, onSelect: () -> Unit, subtitle: String? = null) {
    val choose = Modifier.selectable(
        selected = selected,
        interactionSource = null,
        indication = rvRowIndication(),
        role = Role.RadioButton,
        onClick = onSelect,
    )
    RvSettingsRow(title, choose, subtitle = subtitle) { RvRadio(selected) }
}
