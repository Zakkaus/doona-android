package dev.doona.android.designsystem.rv

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import dev.doona.android.R

/**
 * What a banner says, by kind; the kind alone picks the glyph, so every banner of a kind looks the same. [Error]: an
 * action failed or a part stopped (a failed switch, save, export, import or update; the core stopped; every node failed).
 * [Warning]: something outside doona stops traffic (no network, another app's VPN). [Info]: nothing is broken now, the
 * banner offers something (the log of an earlier crash). The glyphs are the log levels'.
 */
internal enum class RvNoticeKind(@DrawableRes val glyph: Int) {
    Error(R.drawable.rv_sym_error),
    Warning(R.drawable.rv_nodes_ic_warning),
    Info(R.drawable.rv_sym_info),
}

/**
 * One banner: its [kind], the [title] over its [reason] (none when the title says all there is), and the one [action]
 * that [onAction] runs. [titleKeepWords] and
 * [reasonKeepWords] false for a title or reason that names something (a subscription, a file's path: [RvText]'s
 * `keepWords`).
 */
@Immutable
internal class RvNotice(
    val kind: RvNoticeKind,
    val title: String,
    val reason: String?,
    val action: String,
    val onAction: () -> Unit,
    val titleKeepWords: Boolean = true,
    val reasonKeepWords: Boolean = true,
)

/**
 * The one banner of the RV pages, as Revolut's notice card with a button (revolut-ux3 §3): 16 dp padding on every side;
 * a row's 40 dp disc with its kind's glyph in ink, its top on the title's line; the title over its reason (wrapping, in
 * secondary ink) 16 dp after the disc, or, with no reason, the title alone, centred on the disc as a row without a
 * subtitle (LAYOUT-RULES R8, D1); then, [RvSpace.noticeToAction] under the text, the one action as the full-width
 * secondary pill. No hue anywhere: Revolut's notice is neutral, and the owner ruled out a red glyph over a teal action
 * (2026-11-23). A screen reader announces it when it appears. A page places it with [RvBannerSlot] (or [RvGutter]'s
 * `banner`), never by itself.
 */
@Composable
internal fun RvBanner(notice: RvNotice, modifier: Modifier = Modifier) {
    RvCard(modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
        Column(Modifier.fillMaxWidth().padding(RvSpace.rowPadding), verticalArrangement = Arrangement.spacedBy(RvSpace.noticeToAction)) {
            val alignment = if (notice.reason == null) Alignment.CenterVertically else Alignment.Top
            Row(Modifier.fillMaxWidth().testTag(RvRowPart.NOTICE_ROW), verticalAlignment = alignment) {
                RvIconDisc(notice.kind.glyph, glyph = RvSize.noticeGlyph)
                Spacer(Modifier.width(RvSpace.rowGap))
                Column(Modifier.weight(1f)) {
                    RvText(notice.title, Rv.type.rowTitle, maxLines = BANNER_TITLE_LINES, keepWords = notice.titleKeepWords, lineBox = true)
                    notice.reason?.let {
                        Spacer(Modifier.height(RvSpace.titleToSubtitle))
                        RvText(it, Rv.type.rowSub, color = Rv.colors.textSecondary, maxLines = BANNER_REASON_LINES, keepWords = notice.reasonKeepWords, lineBox = true)
                    }
                }
            }
            RvSecondaryButton(notice.action, notice.onAction)
        }
    }
}

/**
 * The one banner a page shows when several want its place: the most serious by [RvNoticeKind]'s order (an error before
 * a warning before an info); between two of a kind, the one listed first. Null when every one is null.
 */
internal fun rvFirstNotice(vararg notices: RvNotice?): RvNotice? = notices.filterNotNull().minByOrNull { it.kind.ordinal }

/**
 * Where a page's banner sits, the same on every page: first in the page's body, right under its header (活動's hero
 * and actions, a large title, the tab's header, the picker's title and caption), so it takes the place of the first
 * card, on the gutter, [gapBelow] over what follows: [RvSpace.cardGap] before a card, [RvSpace.none] before a part that brings its
 * own gap above (a footnote's [RvSpace.footnoteAbove], a section title's [RvSpace.sectionAbove]). Nothing without a
 * [banner].
 */
@Composable
internal fun RvBannerSlot(banner: RvNotice?, gapBelow: Dp = RvSpace.cardGap) {
    if (banner != null) RvBanner(banner, Modifier.padding(start = RvSpace.gutter, end = RvSpace.gutter, bottom = gapBelow))
}

/**
 * An empty state where a section is not set up yet (活動 with no subscription), as Revolut's product that is not opened
 * (revolut-ux3 §4): no glyph and no disc, the [title] over one [line], centred, then one card on the caller's gutter
 * whose rows are the [add] row and, with a [second] way in (訂閱's 新增節點 under 新增訂閱, owner q-p5d-empty
 * 2026-11-26), a second add row ([RvAddRow], owner 2026-11-26: every 新增 X entry is that row). The title keeps the row
 * title's step: Revolut's 44 sp is the hero's, not a notice's. For data that is empty inside a card, [RvEmptyCaption].
 */
@Composable
internal fun RvEmptyState(
    title: String,
    line: String,
    add: String,
    onAdd: () -> Unit,
    second: RvAddEntry? = null,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = RvSpace.chartGap), horizontalAlignment = Alignment.CenterHorizontally) {
        RvText(title, Rv.type.rowTitle, align = TextAlign.Center, maxLines = BANNER_TITLE_LINES)
        Spacer(Modifier.height(RvSpace.emptyLine))
        RvText(line, Rv.type.rowSub, color = Rv.colors.textSecondary, align = TextAlign.Center, maxLines = BANNER_REASON_LINES)
        Spacer(Modifier.height(RvSpace.chartGap))
        RvCard {
            RvAddRow(add, onAdd)
            second?.let { RvAddRow(it.label, it.onClick) }
        }
    }
}

/** A second 新增 X row of [RvEmptyState]'s card: its [label], and what a tap opens. */
internal class RvAddEntry(val label: String, val onClick: () -> Unit)

/**
 * An empty state inside a card (連線 with the tunnel off, a search that matches nothing), as Revolut's tile with no data
 * (revolut-ux3 §4): the card holds its [title] and a 17 sp [caption] under it, both at the start on the card's 16 dp
 * padding, and no glyph, disc or action.
 */
@Composable
internal fun RvEmptyCaption(title: String, caption: String, modifier: Modifier = Modifier) {
    RvCard(modifier) {
        Column(Modifier.fillMaxWidth().padding(RvSpace.rowPadding), verticalArrangement = Arrangement.spacedBy(RvSpace.tight / 2)) {
            RvText(title, Rv.type.rowTitle, maxLines = BANNER_TITLE_LINES)
            RvText(caption, Rv.type.rowSub, color = Rv.colors.textSecondary, maxLines = BANNER_REASON_LINES)
        }
    }
}

/** A banner's or an empty state's title wraps to two lines at most, and its reason, line or caption to three. */
private const val BANNER_TITLE_LINES = 2
private const val BANNER_REASON_LINES = 3
