package dev.doona.android.prototype.rv

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import kotlin.math.max
import kotlin.math.min

// The one list row of every card: RvRow, with its line-wise ends and the title it never lets its end cut.

/**
 * A row of a grouped card: a disc at the start, a title over an optional subtitle, and trailing content at the end.
 *
 * [titleEnd] and [subtitleEnd] put a value at the end of the title's line and of the subtitle's line instead of one
 * trailing column beside both: each line then shares its width only with its own end. The start keeps its width up to
 * [TITLE_SHARE] of the line, the end takes what it needs of the rest, and the start gets what is left, so a long name
 * (`Hong Kong 02 IPLC`) and a long subtitle (`自動測速 · 48 個節點`) both fit where one trailing column would squeeze the
 * text column to its widest end. [trailing] (a chevron) still follows after both lines.
 *
 * The text follows the one row rule ([RV_ROW_TITLE_LINES], [RV_ROW_SUBTITLE_LINES], no gap between the lines);
 * [titleLines] and [titleOverflow] can keep a title to one line cut in the middle, which keeps both ends of a host:port
 * in view. Otherwise no end cuts the text it sits beside, nor is cut itself, nor makes it read badly: an end sits beside
 * its text where the text reads well there ([rvReadsWell]); else the text takes the whole line and the end goes under
 * it, on the line's end (a node's name beside its latency at a large font size; [TextBesideEnd], [RowLine]), never
 * beside a part of the text. A line-wise end, on its own line, also may not make that line wrap where the whole line
 * holds it, so the ends stay on their lines. While [endBusy] (a latency test runs for the row), a busy bar stands at the row's end
 * in place of [titleEnd] and [subtitleEnd], which keep their room, so the text keeps its width and its lines.
 */
@Composable
internal fun RvRow(
    leading: @Composable () -> Unit,
    title: String,
    subtitle: String?,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    titleEnd: (@Composable () -> Unit)? = null,
    subtitleEnd: (@Composable () -> Unit)? = null,
    titleLines: Int = RV_ROW_TITLE_LINES,
    titleOverflow: TextOverflow = TextOverflow.Ellipsis,
    endBusy: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
) {
    // The card clips the layer of its first and last row to its corners.
    val press = onClick?.let { Modifier.rvRowPress(Role.Button, it, onLongClick) } ?: Modifier
    val lineWise = titleEnd != null || subtitleEnd != null
    val beside = trailing != null && !lineWise && titleOverflow == TextOverflow.Ellipsis
    val minHeight = if (subtitle == null) RvSize.rowOneLine else RvSize.rowTwoLine
    Row(
        modifier
            .fillMaxWidth()
            .testTag(RvPart.ROW)
            .heightIn(min = minHeight)
            .then(press)
            .padding(horizontal = RvSpace.rowPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading()
        Spacer(Modifier.width(RvSpace.rowGap))
        if (beside) {
            TextBesideEnd(title, subtitle, titleLines, minHeight, trailing, Modifier.weight(1f))
        } else if (lineWise && endBusy) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                LineWiseText(title, subtitle, titleLines, titleOverflow, titleEnd?.let(::hidden), subtitleEnd?.let(::hidden), Modifier)
                RvBusy(Modifier.width(RvSize.busy))
            }
        } else if (lineWise) {
            LineWiseText(title, subtitle, titleLines, titleOverflow, titleEnd, subtitleEnd, Modifier.weight(1f))
        } else {
            Column(Modifier.weight(1f).padding(vertical = RvSpace.rowTextInset)) {
                RvText(title, Rv.type.rowTitle, maxLines = titleLines, overflow = titleOverflow)
                subtitle?.let { RvText(it, Rv.type.rowSub, color = Rv.colors.textSecondary, maxLines = RV_ROW_SUBTITLE_LINES) }
            }
        }
        if (!beside) {
            trailing?.let {
                Spacer(Modifier.width(RvSpace.trailingGap))
                it()
            }
        }
    }
}

/** A line-wise row's title and subtitle, each with its end ([RowLine]). */
@Composable
private fun LineWiseText(
    title: String,
    subtitle: String?,
    titleLines: Int,
    titleOverflow: TextOverflow,
    titleEnd: (@Composable () -> Unit)?,
    subtitleEnd: (@Composable () -> Unit)?,
    modifier: Modifier,
) {
    Column(modifier.padding(vertical = RvSpace.rowTextInset)) {
        RowLine(titleEnd, RowText(title, Rv.type.rowTitle, titleLines).takeIf { titleOverflow == TextOverflow.Ellipsis }) {
            RvText(title, Rv.type.rowTitle, maxLines = titleLines, overflow = titleOverflow)
        }
        subtitle?.let {
            RowLine(subtitleEnd, RowText(it, Rv.type.rowSub, RV_ROW_SUBTITLE_LINES)) {
                RvText(it, Rv.type.rowSub, color = Rv.colors.textSecondary, maxLines = RV_ROW_SUBTITLE_LINES)
            }
        }
    }
}

/** [end] keeping its room, neither drawn nor read out. */
private fun hidden(end: @Composable () -> Unit): @Composable () -> Unit = { Box(Modifier.alpha(0f).clearAndSetSemantics {}) { end() } }

/**
 * A row's title over its subtitle, with the row's [trailing] end beside them, [RvSpace.trailingGap] apart, both centred
 * on the row, at least [minHeight] tall (the row's, so they are centred once). The end never cuts the title nor squeezes
 * the subtitle: when the title or the subtitle does not read well beside the end ([rvReadsWell]; a node's name beside
 * its latency at a large font size, `Trojan ·` over `香港`), both take the whole line and the end goes under them, on the
 * line's end. The end is centred on the whole text, so a line that wraps well beside it may (the picker's
 * `Hong Kong 02` over `IPLC`).
 */
@Composable
private fun TextBesideEnd(title: String, subtitle: String?, titleLines: Int, minHeight: Dp, trailing: @Composable () -> Unit, modifier: Modifier) {
    val measurer = rememberTextMeasurer()
    val titleStyle = Rv.type.rowTitle
    val subtitleStyle = Rv.type.rowSub
    Layout(
        {
            RvText(title, titleStyle, maxLines = titleLines)
            subtitle?.let { RvText(it, subtitleStyle, color = Rv.colors.textSecondary, maxLines = RV_ROW_SUBTITLE_LINES) }
            Box { trailing() }
        },
        modifier,
    ) { parts, constraints ->
        val width = constraints.maxWidth
        val inset = RvSpace.rowTextInset.roundToPx()
        val gap = RvSpace.trailingGap.roundToPx()
        val least = minHeight.roundToPx()
        val end = parts.last().measure(Constraints(maxWidth = max(0, width - gap)))
        val beside = max(0, width - gap - end.width)
        val fits = rvReadsWell(measurer.layout(RowText(title, titleStyle, titleLines), beside)) &&
            (subtitle == null || rvReadsWell(measurer.layout(RowText(subtitle, subtitleStyle, RV_ROW_SUBTITLE_LINES), beside)))
        val titleLine = parts[0].measure(Constraints(maxWidth = if (fits) beside else width))
        val subtitleLine = if (subtitle != null) parts[1].measure(Constraints(maxWidth = if (fits) beside else width)) else null
        if (fits) {
            val text = titleLine.height + (subtitleLine?.height ?: 0) + inset * 2
            val height = maxOf(text, end.height, least)
            layout(width, height) {
                val top = Alignment.CenterVertically.align(text, height) + inset
                titleLine.place(0, top)
                subtitleLine?.place(0, top + titleLine.height)
                end.place(width - end.width, Alignment.CenterVertically.align(end.height, height))
            }
        } else {
            val text = titleLine.height + (subtitleLine?.height ?: 0)
            layout(width, max(least, inset * 2 + text + end.height)) {
                titleLine.place(0, inset)
                subtitleLine?.place(0, inset + titleLine.height)
                end.place(width - end.width, inset + text)
            }
        }
    }
}

/**
 * The one row rule, every list row's ([RvRow] and the settings rows), as Revolut's measured rows (`07-set-interface.xml`,
 * `07-privacy.xml`): the title wraps to two lines and the subtitle to three, the last ending in an ellipsis; the
 * subtitle's line box starts where the title's ends.
 */
internal const val RV_ROW_TITLE_LINES = 2
internal const val RV_ROW_SUBTITLE_LINES = 3

/** The share of a line a row's title keeps, if it needs it, before the end of the line takes the rest. */
private const val TITLE_SHARE = 0.4f

/**
 * One line of a line-wise [RvRow]: [start] keeps its own width up to [TITLE_SHARE] of the line, [end] takes what it
 * needs of the rest, and [start] then gets what the end leaves. Where [end] does not fit whole on that line, or [start]
 * ([text], when given) does not read well beside it or would take more lines there than on the whole line, [end] goes
 * under [start] on the line's end and each has the whole line, so neither is cut or squeezed.
 */
@Composable
private fun RowLine(end: (@Composable () -> Unit)?, text: RowText?, start: @Composable () -> Unit) {
    val measurer = rememberTextMeasurer()
    Layout({
        start()
        end?.invoke()
    }) { parts, constraints ->
        val width = constraints.maxWidth
        val gap = if (parts.size > 1) RvSpace.trailingGap.roundToPx() else 0
        val keep = min(parts[0].maxIntrinsicWidth(constraints.maxHeight), (width * TITLE_SHARE).toInt())
        val room = max(0, width - keep - gap)
        val endWidth = parts.getOrNull(1)?.maxIntrinsicWidth(constraints.maxHeight)
        val beside = endWidth == null || (endWidth <= room && (text == null || measurer.keeps(text, max(0, width - endWidth - gap), width)))
        val endPart = parts.getOrNull(1)?.measure(Constraints(maxWidth = if (beside) room else width))
        val startPart = parts[0].measure(Constraints(maxWidth = if (beside) max(0, width - (endPart?.width ?: 0) - gap) else width))
        if (beside) {
            val height = max(startPart.height, endPart?.height ?: 0)
            layout(width, height) {
                startPart.place(0, (height - startPart.height) / 2)
                endPart?.let { it.place(width - it.width, (height - it.height) / 2) }
            }
        } else {
            layout(width, startPart.height + (endPart?.height ?: 0)) {
                startPart.place(0, 0)
                endPart?.let { it.place(width - it.width, startPart.height) }
            }
        }
    }
}

/** A row's text as it is laid out: its words, its role and how many lines it may take. */
private class RowText(val text: String, val style: TextStyle, val lines: Int)

/** [text] laid out [width] px wide as its [RvText] lays it out. */
private fun TextMeasurer.layout(text: RowText, width: Int) = rvLayout(text.text, text.style, text.lines, width)

/**
 * Whether [text] reads well [width] px wide ([rvReadsWell]) on no more lines than it takes [wide] px wide: an end beside a
 * line may not make it wrap (`VLESS ·` over `臺灣`, `Hysteria` over `2`) where the whole line holds it.
 */
private fun TextMeasurer.keeps(text: RowText, width: Int, wide: Int): Boolean {
    val narrow = layout(text, width)
    return rvReadsWell(narrow) && (width >= wide || narrow.lineCount <= layout(text, wide).lineCount)
}
