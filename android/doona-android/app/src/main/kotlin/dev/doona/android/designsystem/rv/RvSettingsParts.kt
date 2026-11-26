package dev.doona.android.designsystem.rv

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp

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

/**
 * A settings page's body: its section titles, cards and footnotes on [RvSettingsSpace.margin] from each side. A
 * [RvFootnote] in it takes that margin off its own inset, so a page never passes one.
 */
@Composable
internal fun RvSettingsColumn(
    modifier: Modifier = Modifier,
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    content: @Composable ColumnScope.() -> Unit,
) {
    CompositionLocalProvider(LocalRvColumnInset provides RvSettingsSpace.margin) {
        Column(modifier.fillMaxWidth().padding(horizontal = RvSettingsSpace.margin), verticalArrangement, content = content)
    }
}

/** How far the column a part sits in is already inset from the page's edge: [RvSettingsColumn]'s margin, else none. */
internal val LocalRvColumnInset = staticCompositionLocalOf { RvSpace.none }

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
 * the row's height. A row of one line centres its disc on the row. A row with a subtitle puts the disc's top on the
 * title's top, as Revolut's settings rows do (Appearance and Language cards: icon top = title top in the 2-line rows,
 * centred in the 1-line ones), so in a 2-line row it stands 1.5 dp above the row's centre and a card of 1- and 2-line
 * rows reads as one column. The trailing control centres on the title's first line, where Revolut keeps the switch: the
 * line a one-line row keeps for its title (58 dp less its insets), or the title's own line where it is taller
 * ([rvOneLine]), so at a large font size it stays beside the title's first line. The title, the subtitle and that line
 * take their role's Latin line box whatever their script, as [RvRow]'s text. The row's touch behaviour comes in
 * [modifier], outside the padding, so the whole row is the target. A subtitle that says an action failed is in the
 * [error] hue, as a field's error message ([dev.doona.android.designsystem.rv.RvFieldMessage]). An [action] (an in-card
 * button, 40 dp as the disc, taller than a line) takes the trailing place instead and stands as the disc does: its top on
 * the title's top with a subtitle, on the row's centre without, so the two 40 dp ends share one rule and the button stays
 * inside the row's text inset; its end is the row's inset, where a chevron's box ends.
 * [titleKeepWords] and [subtitleKeepWords] are [RvRow]'s: false for a name or an identifier. [titleLines],
 * [subtitleLines], [titleWhole] and [subtitleWhole] are [RvRow]'s too: a title or subtitle of [Int.MAX_VALUE] lines is
 * shown whole only with an owner's exception, and an exception given to a text with a line limit is an error.
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
    titleKeepWords: Boolean = true,
    subtitleKeepWords: Boolean = true,
    subtitleLines: Int = RV_ROW_SUBTITLE_LINES,
    titleWhole: RvWhole? = null,
    subtitleWhole: RvWhole? = null,
    remove: RvRowRemove? = null,
    removeLabel: String = title,
    trailing: (@Composable () -> Unit)? = null,
) {
    rvRequireWhole(titleWhole, titleLines)
    rvRequireWhole(subtitleWhole, subtitleLines)
    Box(
        modifier
            .fillMaxWidth()
            .testTag(RvPart.ROW)
            .heightIn(min = if (subtitle == null) RvSize.rowOneLine else RvSize.rowTwoLine)
            .padding(horizontal = RvSettingsSpace.rowInset),
        contentAlignment = Alignment.CenterStart,
    ) {
        val text = rowText(SettingsRowWords(title, subtitle, titleKeepWords, subtitleKeepWords, subtitleLines, titleWhole, subtitleWhole), code, titleLines, error, SettingsRowEnds(icon, action, trailing, remove, removeLabel))
        Row(Modifier.fillMaxWidth().testTag(RvRowPart.SETTINGS_ROW).padding(vertical = RvSpace.rowTextInset), verticalAlignment = Alignment.Top, content = text)
    }
}

/** A settings row's title and subtitle, whether each holds its words ([RvSettingsRow]'s keepWords), and its exception to R14. */
private class SettingsRowWords(val title: String, val subtitle: String?, val titleKeepWords: Boolean, val subtitleKeepWords: Boolean, val subtitleLines: Int, val titleWhole: RvWhole?, val subtitleWhole: RvWhole?)

/** A settings row's remove control in edit mode, its leading disc, and its trailing [action] or [trailing] control ([RvSettingsRow]). */
private class SettingsRowEnds(
    @DrawableRes val icon: Int?,
    val action: (@Composable () -> Unit)?,
    val trailing: (@Composable () -> Unit)?,
    val remove: RvRowRemove?,
    val removeLabel: String,
)

/**
 * [RvSettingsRow]'s disc, title over subtitle, and trailing control: the control on the title's first line; the disc
 * and an action on the title's top when there is a subtitle, on the first line (the row's centre) when there is none.
 */
@Composable
private fun rowText(words: SettingsRowWords, code: Boolean, titleLines: Int, error: Boolean, ends: SettingsRowEnds): @Composable RowScope.() -> Unit {
    // A title of honk code keeps its own line boxes, as it is a code view; any other takes its role's Latin one.
    val title = words.title
    val line = maxOf(RvSize.rowOneLine - RvSpace.rowTextInset * 2, rvOneLine(title, Rv.type.rowTitle, lineBox = !code))
    val top = words.subtitle != null
    return {
        // Style A's edit mode: the remove disc on the title's first line, before the disc or the text.
        ends.remove?.takeIf { it.style == RvRemoveStyle.Editing }?.let { Row(Modifier.height(line), verticalAlignment = Alignment.CenterVertically) { RvRemoveStart(it, ends.removeLabel) } }
        ends.icon?.let {
            Tall(line, top, Modifier.testTag(RvPart.DISC)) { RvIconDisc(it) }
            Spacer(Modifier.width(RvSpace.rowGap))
        }
        // R18: a one-line row's title is centred in the first-line box, as the disc and the control are; a taller block fills it.
        Column(Modifier.weight(1f).heightIn(min = line), verticalArrangement = Arrangement.Center) {
            if (code) {
                RvCodeText(title, Rv.type.rowTitle, maxLines = titleLines)
            } else {
                RvText(title, Rv.type.rowTitle, rvWhole(words.titleWhole), maxLines = titleLines, lineBox = true, keepWords = words.titleKeepWords)
            }
            val hue = if (error) Rv.colors.error else Rv.colors.textSecondary
            words.subtitle?.let {
                // R12 (owner, q-s1125c-titlegap): the subtitle stands its gap under the title, as in every row.
                Spacer(Modifier.height(RvSpace.titleToSubtitle))
                RvText(it, Rv.type.rowSub, rvWhole(words.subtitleWhole), color = hue, maxLines = words.subtitleLines, lineBox = true, keepWords = words.subtitleKeepWords)
            }
        }
        ends.action?.let {
            Spacer(Modifier.width(RvSettingsSpace.trailingGap))
            Tall(line, top, content = it)
        }
        ends.trailing?.let {
            Spacer(Modifier.width(RvSettingsSpace.trailingGap))
            FirstLine(line, it)
        }
    }
}

/** [content] centred on the title's first [line]. */
@Composable
private fun FirstLine(line: Dp, content: @Composable () -> Unit) {
    Box(Modifier.height(line).testTag(RvRowPart.END), contentAlignment = Alignment.Center) { content() }
}

/**
 * An end of the row's left or right side: one as tall as a line box or taller (the 40 dp disc, an in-card button) is not
 * held to the box's height and never makes the row taller, its top on the title's [top] (a row with a subtitle), else
 * centred on the first [line]; a shorter one (the busy bar standing in for a button) is centred on the first line.
 */
@Composable
private fun Tall(line: Dp, top: Boolean, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Layout({ Box(modifier) { content() } }, Modifier.height(line)) { (part), _ ->
        val placed = part.measure(Constraints())
        val height = line.roundToPx()
        layout(placed.width, height) { placed.place(0, if (top && placed.height >= height) 0 else (height - placed.height) / 2) }
    }
}

/**
 * A row that opens something, then a chevron; its optional [subtitle] is the row's second line, so the row is the
 * two-line 82 dp one: a value, as Revolut's Appearance rows show Theme over "Light - Glass" (§13.3), or what the row
 * leads to. A row of a plain list (the licences) has no [icon], so its text starts on the card's padding, as DNS's 自訂.
 * A title that is honk text (a skipped declaration) is [code], highlighted as all honk text is ([RvCodeText]), and takes
 * [titleLines] as many lines as it needs: what the user looks for in the file is the text, so it is never cut. A [mark]
 * (a status chip, 使用中) stands before the chevron. [titleKeepWords] and [subtitleKeepWords] false for a name or an
 * identifier (a file's path: [RvText]'s `keepWords`).
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
    titleKeepWords: Boolean = true,
    subtitleKeepWords: Boolean = true,
    remove: RvRowRemove? = null,
    removeLabel: String = title,
) {
    val press = Modifier.rvRowPress(Role.Button, onClick)
    RvSettingsRow(title, press, icon, subtitle, code, titleLines, titleKeepWords = titleKeepWords, subtitleKeepWords = subtitleKeepWords, remove = remove, removeLabel = removeLabel) {
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

/**
 * A row of a radio list (in an [RvSettingsRadioCard]): the title over an optional [subtitle], and the radio at the end;
 * [subtitleKeepWords] false for a subtitle that is an address (a DNS server's).
 */
@Composable
internal fun RvSettingsRadioRow(title: String, selected: Boolean, onSelect: () -> Unit, subtitle: String? = null, subtitleKeepWords: Boolean = true) {
    val choose = Modifier.rvRowSelect(selected, onSelect)
    RvSettingsRow(title, choose, subtitle = subtitle, subtitleKeepWords = subtitleKeepWords) { RvRadio(selected) }
}
