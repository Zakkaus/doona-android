package dev.doona.android.prototype.rv.settings

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.unit.Dp
import dev.doona.android.designsystem.rv.RV_ROW_SUBTITLE_LINES
import dev.doona.android.designsystem.rv.RV_ROW_TITLE_LINES
import dev.doona.android.designsystem.rv.Rv
import dev.doona.android.designsystem.rv.RvCard
import dev.doona.android.designsystem.rv.RvChevron
import dev.doona.android.designsystem.rv.RvCodeText
import dev.doona.android.designsystem.rv.RvIconDisc
import dev.doona.android.designsystem.rv.RvPart
import dev.doona.android.designsystem.rv.RvRadio
import dev.doona.android.designsystem.rv.RvSectionTitle
import dev.doona.android.designsystem.rv.RvSize
import dev.doona.android.designsystem.rv.RvSpace
import dev.doona.android.designsystem.rv.RvSpanText
import dev.doona.android.designsystem.rv.RvSwitch
import dev.doona.android.designsystem.rv.RvText
import dev.doona.android.designsystem.rv.rvOneLine
import dev.doona.android.designsystem.rv.rvRowPress
import dev.doona.android.designsystem.rv.rvRowSelect
import dev.doona.android.designsystem.rv.rvRowToggle
import dev.doona.android.designsystem.rv.scripted

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
    RvSpanText(scripted(text), style.copy(color = color, textAlign = align), modifier, maxLines, TextOverflow.Ellipsis)
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
 * A row of a card: the [icon]'s 40 dp disc ([RvIconDisc]), a title over an optional subtitle, and trailing content, 58 dp
 * tall with one line and 82 with a subtitle, as [RvRow]; a subtitle that wraps makes it taller. The disc starts its text
 * [RvSpace.rowGap] after it, as [RvRow]'s leading disc does, so every list row starts alike. The text block centres in
 * the row's height, and both ends, the disc and the trailing control, centre on its first line: Revolut's settings rows
 * keep both there when the text runs to more lines (privacy and accessibility dumps: icon and switch 2 px above the
 * title line's centre), never on the row's centre. That line is the one a one-line row keeps for its title (58 dp less its insets),
 * or the title's own line where it is taller ([rvOneLine]), so at a large font size it stays beside the title's first
 * line. The title, the subtitle and that line take their role's Latin line box whatever their script, as [RvRow]'s
 * text. The row's touch behaviour comes in [modifier], outside the padding, so the whole row is the target. A subtitle
 * that says an action failed is in the [error] hue, as a field's error message
 * ([dev.doona.android.designsystem.rv.RvFieldMessage]). An [action] (an in-card button, 40 dp, taller than a line) takes
 * the trailing place instead and centres on the whole row, title and subtitle together, so it neither rises above the
 * row's text inset nor sits on one line of the two; its end is the row's inset, where a chevron's box ends.
 */
@Composable
internal fun RvSettingsRow(
    title: String,
    modifier: Modifier = Modifier,
    @DrawableRes icon: Int? = null,
    subtitle: String? = null,
    code: Boolean = false,
    titleLines: Int = RV_ROW_TITLE_LINES,
    error: Boolean = false,
    action: (@Composable () -> Unit)? = null,
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
        val text = rowText(title, subtitle, code, titleLines, error, icon, trailing)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).padding(vertical = RvSpace.rowTextInset), verticalAlignment = Alignment.Top, content = text)
            action?.let {
                Spacer(Modifier.width(RvSettingsSpace.trailingGap))
                it()
            }
        }
    }
}

/** [RvSettingsRow]'s disc, title over subtitle, and trailing control, the disc and the control on the title's first line. */
@Composable
private fun rowText(
    title: String,
    subtitle: String?,
    code: Boolean,
    titleLines: Int,
    error: Boolean,
    @DrawableRes icon: Int?,
    trailing: (@Composable () -> Unit)?,
): @Composable RowScope.() -> Unit {
    // A title of honk code keeps its own line boxes, as it is a code view; any other takes its role's Latin one.
    val line = maxOf(RvSize.rowOneLine - RvSpace.rowTextInset * 2, rvOneLine(title, Rv.type.rowTitle, lineBox = !code))
    return {
        icon?.let {
            // The disc (40 dp) is taller than a line box of a small font, so it is not held to the box's height.
            FirstLine(line) { Box(Modifier.wrapContentHeight(unbounded = true).testTag(RvPart.DISC)) { RvIconDisc(it) } }
            Spacer(Modifier.width(RvSpace.rowGap))
        }
        Column(Modifier.weight(1f)) {
            if (code) RvCodeText(title, Rv.type.rowTitle, maxLines = titleLines) else RvText(title, Rv.type.rowTitle, maxLines = titleLines, lineBox = true)
            val hue = if (error) Rv.colors.error else Rv.colors.textSecondary
            subtitle?.let { RvText(it, Rv.type.rowSub, color = hue, maxLines = RV_ROW_SUBTITLE_LINES, lineBox = true) }
        }
        trailing?.let {
            Spacer(Modifier.width(RvSettingsSpace.trailingGap))
            FirstLine(line, it)
        }
    }
}

/** [content] centred on the title's first [line]. */
@Composable
private fun FirstLine(line: Dp, content: @Composable () -> Unit) {
    Box(Modifier.height(line), contentAlignment = Alignment.Center) { content() }
}

/**
 * A row that opens something, then a chevron; its optional [subtitle] is the row's second line, so the row is the
 * two-line 82 dp one: a value, as Revolut's Appearance rows show Theme over "Light - Glass" (§13.3), or what the row
 * leads to. A row of a plain list (the licences) has no [icon], so its text starts on the card's padding, as DNS's 自訂.
 * A title that is honk text (a skipped declaration) is [code], highlighted as all honk text is ([RvCodeText]), and takes
 * [titleLines] as many lines as it needs: what the user looks for in the file is the text, so it is never cut. A [mark]
 * (a status chip, 使用中) stands before the chevron.
 */
@Composable
internal fun RvSettingsNavRow(
    title: String,
    @DrawableRes icon: Int?,
    onClick: () -> Unit,
    subtitle: String? = null,
    code: Boolean = false,
    titleLines: Int = RV_ROW_TITLE_LINES,
    mark: (@Composable () -> Unit)? = null,
) {
    RvSettingsRow(title, Modifier.rvRowPress(Role.Button, onClick), icon, subtitle = subtitle, code = code, titleLines = titleLines) {
        RvNavEnd(mark)
    }
}

/** The end of a row that opens something: its chevron, after a [mark] (a status chip) when there is one. */
@Composable
internal fun RvNavEnd(mark: (@Composable () -> Unit)?) {
    if (mark == null) {
        RvChevron()
    } else {
        // A chip is taller than the first line it is centred on, so it is not held to that line's height.
        Row(Modifier.wrapContentHeight(unbounded = true), verticalAlignment = Alignment.CenterVertically) {
            mark()
            Spacer(Modifier.width(RvSpace.trailingGap))
            RvChevron()
        }
    }
}

/** A row whose whole width toggles its switch. */
@Composable
internal fun RvSettingsSwitchRow(title: String, @DrawableRes icon: Int, checked: Boolean, onChange: (Boolean) -> Unit, subtitle: String? = null) {
    val toggle = Modifier.rvRowToggle(checked, onChange)
    RvSettingsRow(title, toggle, icon, subtitle) { RvSwitch(checked) }
}

/**
 * A settings card holding a radio list ([RvSettingsRadioRow]): one group of choices, so a screen reader tells each row's
 * place among the choices.
 */
@Composable
internal fun RvSettingsRadioCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) = RvSettingsCard(modifier.selectableGroup(), content)

/** A row of a radio list (in an [RvSettingsRadioCard]): the title over an optional [subtitle], and the radio at the end. */
@Composable
internal fun RvSettingsRadioRow(title: String, selected: Boolean, onSelect: () -> Unit, subtitle: String? = null) {
    val choose = Modifier.rvRowSelect(selected, onSelect)
    RvSettingsRow(title, choose, subtitle = subtitle) { RvRadio(selected) }
}
