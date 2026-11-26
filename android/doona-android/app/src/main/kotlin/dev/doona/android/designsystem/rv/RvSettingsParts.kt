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
 * A settings page's body: its section titles, cards and footnotes on [RvSpace.gutter] from each side. A
 * [RvFootnote] in it takes that margin off its own inset, so a page never passes one.
 */
@Composable
internal fun RvSettingsColumn(
    modifier: Modifier = Modifier,
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    content: @Composable ColumnScope.() -> Unit,
) {
    CompositionLocalProvider(LocalRvColumnInset provides RvSpace.gutter) {
        Column(modifier.fillMaxWidth().padding(horizontal = RvSpace.gutter), verticalArrangement, content = content)
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
 * A row of a settings card: [RvRow] with the [icon]'s 40 dp disc ([RvIconDisc]; none in a plain list, whose text then
 * starts on the card's padding), its disc and ends standing on the title's first line (RvRow's `settings`),
 * and, in an editable list, its [remove] control in the trailing place. The row's touch behaviour comes in [modifier].
 * [code], [error] and [action] are RvRow's `titleCode`, `subtitleError` and `action`; [titleKeepWords],
 * [subtitleKeepWords], [titleLines], [subtitleLines], [titleWhole] and [subtitleWhole] are RvRow's own.
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
    RvRow(
        leading = icon?.let { { RvIconDisc(it) } },
        title = title,
        subtitle = subtitle,
        modifier = modifier,
        titleLines = titleLines,
        titleKeepWords = titleKeepWords,
        subtitleKeepWords = subtitleKeepWords,
        subtitleLines = subtitleLines,
        titleWhole = titleWhole,
        subtitleWhole = subtitleWhole,
        settings = true,
        titleCode = code,
        subtitleError = error,
        action = action,
        trailing = rvRemoveTrailing(remove, removeLabel) ?: trailing,
    )
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

/**
 * The end of a row that opens something: its chevron, after a [mark] (a status chip) when there is one, else after its
 * current [value] in the subtitle's grey (the policy page's 規則範本 and 設定檔, owner q-e8 2026-11-26), or after a busy
 * bar while what it opens is being updated ([busy]).
 */
@Composable
internal fun RvNavEnd(mark: (@Composable () -> Unit)? = null, value: String? = null, busy: Boolean = false) {
    val shown: (@Composable () -> Unit)? = when {
        mark != null -> mark

        busy -> {
            { RvBusy(Modifier.width(RvSize.busy)) }
        }

        value != null -> {
            { RvText(value, Rv.type.rowSub, color = Rv.colors.textSecondary) }
        }

        else -> null
    }
    NavEnd(shown)
}

@Composable
private fun NavEnd(mark: (@Composable () -> Unit)?) {
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

/**
 * A settings card holding a radio list ([RvRadioRow]): one group of choices, so a screen reader tells each row's
 * place among the choices.
 */
@Composable
internal fun RvSettingsRadioCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) = RvSettingsCard(modifier.selectableGroup(), content)
