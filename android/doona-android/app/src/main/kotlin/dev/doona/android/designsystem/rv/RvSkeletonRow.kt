package dev.doona.android.designsystem.rv

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import dev.doona.android.R
import kotlin.math.max
import kotlin.math.min

// A list row's skeleton: RvRow's disc, words and ends as bars, laid out by RvRow's own rules.

/**
 * What a skeleton row has at its end, each on the lines its row's end takes: the [first] line's kind of text, at the row
 * value's size, and the [second]'s under it, at the subtitle's. A [lineWise] end puts each on its own line of the row,
 * as [RvRow]'s `titleEnd` and `subtitleEnd`; any other stands beside the row's text, as its trailing end.
 */
internal enum class RvSkeletonEnd(val first: RvSkeletonText?, val second: RvSkeletonText?, val lineWise: Boolean = false) {
    None(null, null),

    /** One value centred on the row: a rule's bytes. */
    Value(RvSkeletonText.Figures, null),

    /** A latency over its change since the test before (`32 毫秒` over `▼ 3 毫秒`). */
    Latency(RvSkeletonText.Mixed, RvSkeletonText.Mixed),

    /** A group's node on the title's line and its latency on the subtitle's (`Taipei 04`, `26 毫秒`). */
    Node(RvSkeletonText.Figures, RvSkeletonText.Mixed, lineWise = true),

    /** A node's latency on the title's line and its change on the subtitle's (節點's rows: `32 毫秒`, `▼ 3 毫秒`). */
    LatencyLines(RvSkeletonText.Mixed, RvSkeletonText.Mixed, lineWise = true),
}

/**
 * [RvRow]'s skeleton, row [index] of its card: the disc, the title's bar over the subtitle's of [subtitle]'s kind (or the
 * title's alone), the [end]'s bars and the room of the row's chevron when it has one ([chevron]) or of the info mark a
 * line-wise end ends in ([infoMark], [RvRowEnd]'s glyph: a touch target right after the end), laid out as [RvRow] lays
 * out its row, in its heights, insets and gaps. Words the page knows before its data ([title], [subtitleText], the
 * [endText] of a line-wise end's first line: 出站模式 over the mode, a node's name, a group's node) take their own lines
 * ([RvSkeletonLabel]), so the row is as tall as the row they wrap in, and where they do not read well beside the end, the
 * end goes under them as the row's does. A figure is a bar on its text's line ([RvSkeletonLine]). Beside an end of two
 * lines a title's bar keeps to the room a title keeps beside its end ([besideEnd]), so it does not reach under a long end
 * (a group's `Hong Kong 02 IPLC`) as the content fades in. Past [LocalRvContentFloor] the row keeps its place undrawn.
 * [titleKeepWords] and [subtitleKeepWords] are the row's ([RvRow]'s), false for a name.
 */
@Composable
internal fun RvSkeletonRow(
    index: Int,
    subtitle: RvSkeletonText? = RvSkeletonText.Words,
    end: RvSkeletonEnd = RvSkeletonEnd.None,
    chevron: Boolean = false,
    title: String? = null,
    subtitleText: String? = null,
    endText: String? = null,
    titleKeepWords: Boolean = true,
    subtitleKeepWords: Boolean = true,
    infoMark: Boolean = false,
) {
    val minHeight = if (subtitle != null) RvSize.rowTwoLine else RvSize.rowOneLine
    val room = rememberEndRoom()
    val titleRoom = if (title == null && end.second != null) Modifier.besideEnd(room.figure) else Modifier
    val words = SkeletonWords(index, title, subtitle, subtitleText, titleRoom, titleKeepWords, subtitleKeepWords)
    Row(
        Modifier
            .fillMaxWidth()
            .testTag(RvPart.ROW)
            .rvAboveFloor()
            .heightIn(min = minHeight)
            .padding(horizontal = RvSpace.rowPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RvSkeletonDisc()
        Spacer(Modifier.width(RvSpace.rowGap))
        when {
            end.lineWise -> {
                LineWise(words, end, endText, room, Modifier.weight(1f))
                if (chevron) ChevronRoom()
                if (infoMark) Spacer(Modifier.width(RvSize.touch))
            }

            end != RvSkeletonEnd.None || chevron -> BesideEnd(words, minHeight, Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    EndBars(end, room)
                    if (chevron && end.first != null) Spacer(Modifier.width(RvSpace.trailingGap))
                    if (chevron) Spacer(Modifier.size(RvChevronSize.box))
                }
            }

            else -> Column(Modifier.weight(1f).padding(vertical = RvSpace.rowTextInset)) {
                words.Title()
                words.Subtitle()
            }
        }
    }
}

/**
 * A skeleton row's title and subtitle: their words where the page knows them, else bars of row [index]'s widths, in the
 * row text's line boxes; [titleWords] and [subtitleWords] are those words as the row lays them out ([rvWords]).
 */
private class SkeletonWords(
    val index: Int,
    val title: String?,
    val subtitle: RvSkeletonText?,
    val subtitleText: String?,
    val titleRoom: Modifier,
    private val titleKeepWords: Boolean,
    private val subtitleKeepWords: Boolean,
) {
    @Composable
    fun titleWords(): RowWords? = title?.let { RowWords(remember(it) { rvWords(it, RV_ROW_TITLE_LINES, titleKeepWords) }, Rv.type.rowTitle, RV_ROW_TITLE_LINES) }

    @Composable
    fun subtitleWords(): RowWords? = subtitleText?.let { RowWords(remember(it) { rvWords(it, RV_ROW_SUBTITLE_LINES, subtitleKeepWords) }, Rv.type.rowSub, RV_ROW_SUBTITLE_LINES) }

    @Composable
    fun Title() {
        if (title != null) {
            RvSkeletonLabel(title, Rv.type.rowTitle, maxLines = RV_ROW_TITLE_LINES, lineBox = true, keepWords = titleKeepWords)
        } else {
            RvSkeletonLine(Rv.type.rowTitle, RvSkeletonSize.titles[index % RvSkeletonSize.titles.size], titleRoom, lineBox = true)
        }
    }

    @Composable
    fun Subtitle() {
        val kind = subtitle ?: return
        if (subtitleText != null) {
            RvSkeletonLabel(subtitleText, Rv.type.rowSub, maxLines = RV_ROW_SUBTITLE_LINES, lineBox = true, keepWords = subtitleKeepWords)
        } else {
            RvSkeletonLine(Rv.type.rowSub, RvSkeletonSize.subtitles[index % RvSkeletonSize.subtitles.size], kind = kind, lineBox = true)
        }
    }
}

/**
 * [RvRow]'s text beside its trailing [end], laid out by its rule: the end beside the text where the known words read well
 * there ([rvReadsWell]), both centred on the row, at least [minHeight] tall; else the text takes the whole line and the
 * end goes under it, on the line's end.
 */
@Composable
private fun BesideEnd(words: SkeletonWords, minHeight: Dp, modifier: Modifier, end: @Composable () -> Unit) {
    val measurer = rememberTextMeasurer()
    val title = words.titleWords()
    val subtitle = words.subtitleWords()?.takeIf { words.subtitle != null }
    Layout(
        {
            words.Title()
            words.Subtitle()
            Box { end() }
        },
        modifier,
    ) { parts, constraints ->
        val width = constraints.maxWidth
        val inset = RvSpace.rowTextInset.roundToPx()
        val gap = RvSpace.trailingGap.roundToPx()
        val least = minHeight.roundToPx()
        val endPart = parts.last().measure(Constraints(maxWidth = max(0, width - gap)))
        val beside = max(0, width - gap - endPart.width)
        val fits = measurer.readsWell(title, beside) && measurer.readsWell(subtitle, beside)
        val textWidth = if (fits) beside else width
        val titleLine = parts[0].measure(Constraints(maxWidth = textWidth))
        val subtitleLine = if (parts.size > 2) parts[1].measure(Constraints(maxWidth = textWidth)) else null
        val text = titleLine.height + (subtitleLine?.height ?: 0)
        if (fits) {
            val height = maxOf(text + inset * 2, endPart.height, least)
            layout(width, height) {
                val top = Alignment.CenterVertically.align(text + inset * 2, height) + inset
                titleLine.place(0, top)
                subtitleLine?.place(0, top + titleLine.height)
                endPart.place(width - endPart.width, Alignment.CenterVertically.align(endPart.height, height))
            }
        } else {
            layout(width, max(least, inset * 2 + text + endPart.height)) {
                titleLine.place(0, inset)
                subtitleLine?.place(0, inset + titleLine.height)
                endPart.place(width - endPart.width, inset + text)
            }
        }
    }
}

/** A trailing end's bars in their figures' [room]: a value's centred on the row, or a latency's over its change's, at the row's end. */
@Composable
private fun EndBars(end: RvSkeletonEnd, room: EndRoom) {
    val first = end.first ?: return
    val second = end.second ?: return RvSkeletonLine(Rv.type.rowValue, 1f, Modifier.width(room.figure), first)
    Column(horizontalAlignment = Alignment.End) {
        RvSkeletonLine(Rv.type.rowValue, 1f, Modifier.width(room.figure), first)
        RvSkeletonLine(Rv.type.rowSub, 1f, Modifier.width(room.change), second)
    }
}

/** The room of the figures an end stands for ([RvSkeletonSize.FIGURE]): at the row value's size, at the subtitle's, and a change's. */
private class EndRoom(val figure: Dp, val small: Dp, val change: Dp)

@Composable
private fun rememberEndRoom(): EndRoom {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val figure = stringResource(R.string.latency_ms, RvSkeletonSize.FIGURE)
    val change = stringResource(R.string.latency_ms, RvSkeletonSize.CHANGE)
    val value = Rv.type.rowValue
    val sub = Rv.type.rowSub
    return remember(figure, change, value, sub, measurer, density) {
        fun wide(text: String, style: TextStyle) = with(density) { measurer.measure(scripted(text), style, maxLines = 1).size.width.toDp() }
        EndRoom(wide(figure, value), wide(figure, sub), RvHomeSize.delta + RvHomeSize.deltaGap + wide(change, sub))
    }
}

/**
 * [RvRow]'s line-wise text: the title with the [end]'s first line (its [endText] where the page knows it) at the end of
 * its line, the subtitle with the second at the end of its, each line laid out by [RvRow]'s rule ([WithEnd]), the ends
 * in the line boxes of the row's text, as the row's own ends.
 */
@Composable
private fun LineWise(words: SkeletonWords, end: RvSkeletonEnd, endText: String?, room: EndRoom, modifier: Modifier) {
    Column(modifier.padding(vertical = RvSpace.rowTextInset)) {
        WithEnd(words.titleWords(), { words.Title() }) {
            val first = end.first ?: RvSkeletonText.Figures
            if (endText != null) {
                RvSkeletonLabel(endText, Rv.type.rowValue.copy(textAlign = TextAlign.End), maxLines = RV_ROW_TITLE_LINES, lineBox = true)
            } else {
                RvSkeletonLine(Rv.type.rowValue, 1f, Modifier.width(room.figure), first, lineBox = true)
            }
        }
        val second = end.second
        if (words.subtitle != null) {
            WithEnd(words.subtitleWords(), { words.Subtitle() }) {
                val wide = if (end == RvSkeletonEnd.Node) room.small else room.change
                if (second != null) RvSkeletonLine(Rv.type.rowSub, 1f, Modifier.width(wide), second, lineBox = true)
            }
        }
    }
}

/**
 * One line of a line-wise row, by [RvRow]'s rule for it: [start] keeps its own width up to [RV_ROW_TITLE_SHARE] of the line
 * and [end] takes what it needs of the rest; where [end] does not fit whole there, or the known words ([text]) would not
 * read well beside it or would take more lines there than on the whole line, [end] goes under [start] on the line's end.
 */
@Composable
private fun WithEnd(text: RowWords?, start: @Composable () -> Unit, end: @Composable () -> Unit) {
    val measurer = rememberTextMeasurer()
    Layout({
        start()
        Box { end() }
    }) { (startPart, endPart), constraints ->
        val width = constraints.maxWidth
        val gap = RvSpace.trailingGap.roundToPx()
        val endWidth = endPart.maxIntrinsicWidth(constraints.maxHeight)
        // A line with nothing at its end (a subtitle whose end has no second line) has the whole line.
        val gapped = if (endWidth > 0) gap else 0
        val keep = min(startPart.maxIntrinsicWidth(constraints.maxHeight), (width * RV_ROW_TITLE_SHARE).toInt())
        val room = max(0, width - keep - gapped)
        val beside = endWidth <= room && (text == null || measurer.keeps(text, max(0, width - endWidth - gapped), width))
        val endLine = endPart.measure(Constraints(maxWidth = if (beside) room else width))
        val startLine = startPart.measure(Constraints(maxWidth = if (beside) max(0, width - endLine.width - gapped) else width))
        if (beside) {
            val height = max(startLine.height, endLine.height)
            layout(width, height) {
                startLine.place(0, (height - startLine.height) / 2)
                endLine.place(width - endLine.width, (height - endLine.height) / 2)
            }
        } else {
            layout(width, startLine.height + endLine.height) {
                startLine.place(0, 0)
                endLine.place(width - endLine.width, startLine.height)
            }
        }
    }
}

/** The room of a line-wise row's chevron, after its text. */
@Composable
private fun ChevronRoom() {
    Spacer(Modifier.width(RvSpace.trailingGap))
    Spacer(Modifier.size(RvChevronSize.box))
}

/** Words a skeleton row knows, as its [RvRow] lays them out: the words ([rvWords]), their role and how many lines they may take. */
private class RowWords(val text: String, val style: TextStyle, val lines: Int)

private fun TextMeasurer.readsWell(words: RowWords?, width: Int) = words == null || rvReadsWell(rvLayout(words.text, words.style, words.lines, width))

/** Whether [words] read well [width] px wide on no more lines than they take [wide] px wide, as [RvRow]'s line-wise ends ask. */
private fun TextMeasurer.keeps(words: RowWords, width: Int, wide: Int): Boolean {
    val narrow = rvLayout(words.text, words.style, words.lines, width)
    return rvReadsWell(narrow) && (width >= wide || narrow.lineCount <= rvLayout(words.text, words.style, words.lines, wide).lineCount)
}

/**
 * The room a row's title keeps beside its line-wise end, as [RvRow] gives it: [RV_ROW_TITLE_SHARE] of the line (this
 * column, the gap and the end's [end] wide room) less the gap, or the column where that is narrower.
 */
private fun Modifier.besideEnd(end: Dp): Modifier = layout { measurable, constraints ->
    val gap = RvSpace.trailingGap.roundToPx()
    val room = ((constraints.maxWidth + gap + end.roundToPx()) * RV_ROW_TITLE_SHARE).toInt() - gap
    val placeable = measurable.measure(constraints.copy(minWidth = 0, maxWidth = room.coerceIn(0, constraints.maxWidth)))
    layout(placeable.width, placeable.height) { placeable.place(0, 0) }
}
