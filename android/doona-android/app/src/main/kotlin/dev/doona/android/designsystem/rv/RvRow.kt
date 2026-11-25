package dev.doona.android.designsystem.rv

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.AlignmentLine
import androidx.compose.ui.layout.HorizontalAlignmentLine
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.layout.VerticalAlignmentLine
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// The one list row of every card: RvRow, with its line-wise ends and the title it never lets its end cut.

/**
 * A row of a grouped card: a disc at the start, a title over an optional subtitle, and trailing content at the end.
 *
 * [titleEnd] and [subtitleEnd] put a value at the end of the title's line and of the subtitle's line instead of one
 * trailing column beside both: each line then shares its width only with its own end. The start keeps its width up to
 * [RV_ROW_TITLE_SHARE] of the line, the end takes what it needs of the rest, and the start gets what is left, so a long name
 * (`Hong Kong 02 IPLC`) and a long subtitle (`自動測速 · 48 個節點`) both fit where one trailing column would squeeze the
 * text column to its widest end. [trailing] (a chevron) still follows after both lines.
 *
 * The text follows the one row rule ([RV_ROW_TITLE_LINES], [RV_ROW_SUBTITLE_LINES], no gap between the lines, each
 * line its role's Latin line box whatever its script: [RvLineBox]);
 * [titleLines] and [titleOverflow] can keep a title to one line cut in the middle, which keeps both ends of a host:port
 * in view. [titleKeepWords] and [subtitleKeepWords] false keep a name's or an identifier's breaks the platform's
 * ([RvText]'s `keepWords`: a node's name, an app's label and package). Otherwise no end cuts the text it sits beside, nor is cut itself, nor makes it read badly: an end sits beside
 * its text where the text reads well there ([rvReadsWell]); else the text takes the whole line and the end goes under
 * it, on the line's end (a node's name beside its latency at a large font size; [TextBesideEnd], [RowLine]), never
 * beside a part of the text. A line-wise end, on its own line, also may not make that line wrap where the whole line
 * holds it, so the ends stay on their lines. An end under its text starts where the text's last line ends, with no gap,
 * as the subtitle starts where the title ends, and the row ends [RvSpace.rowTextInset] under it. While [endBusy] (a
 * latency test runs for the row), a busy bar stands at the row's end in place of [titleEnd] and [subtitleEnd], which
 * keep their room, so the text keeps its width and its lines: centred on the text, or on the line of an end that went
 * under it.
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
    titleKeepWords: Boolean = true,
    subtitleKeepWords: Boolean = true,
    endBusy: Boolean = false,
    endFirstLine: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
) {
    // The card clips the layer of its first and last row to its corners.
    val press = onClick?.let { Modifier.rvRowPress(Role.Button, it, onLongClick) } ?: Modifier
    val lineWise = titleEnd != null || subtitleEnd != null
    val beside = trailing != null && !lineWise
    val minHeight = if (subtitle == null) RvSize.rowOneLine else RvSize.rowTwoLine
    val titleText = rememberRowText(title, Rv.type.rowTitle, titleLines, titleKeepWords)
    val subtitleText = subtitle?.let { rememberRowText(it, Rv.type.rowSub, RV_ROW_SUBTITLE_LINES, subtitleKeepWords) }
    // LAYOUT-RULES.md R9: with a subtitle the disc's top is the title's top, so the text publishes where its title starts.
    val top = subtitle != null
    val firstLine = if (endFirstLine && top) maxOf(RvSize.rowOneLine - RvSpace.rowTextInset * 2, rvOneLine(title, Rv.type.rowTitle, lineBox = true)) else null
    Row(
        modifier
            .fillMaxWidth()
            .testTag(RvPart.ROW)
            .heightIn(min = minHeight)
            .then(press)
            .padding(horizontal = RvSpace.rowPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (top) Box(Modifier.alignBy { 0 }) { leading() } else leading()
        Spacer(Modifier.width(RvSpace.rowGap))
        val text = if (top) Modifier.weight(1f).alignBy(RvTitleTop) else Modifier.weight(1f)
        if (beside) {
            TextBesideEnd(titleText, subtitleText, titleOverflow, minHeight, firstLine, trailing, top, text)
        } else if (lineWise && endBusy) {
            BusyAtEnd(text) {
                LineWiseText(titleText, subtitleText, titleOverflow, titleEnd?.let(::hidden), subtitleEnd?.let(::hidden), minHeight, top, Modifier)
            }
        } else if (lineWise) {
            LineWiseText(titleText, subtitleText, titleOverflow, titleEnd, subtitleEnd, minHeight, top, text)
        } else if (top) {
            RowTextColumn(minHeight, text) {
                titleText.Shown(overflow = titleOverflow)
                subtitleText?.Shown(Rv.colors.textSecondary)
            }
        } else {
            Column(Modifier.weight(1f).padding(vertical = RvSpace.rowTextInset)) {
                titleText.Shown(overflow = titleOverflow)
                subtitleText?.Shown(Rv.colors.textSecondary, subtitle = true)
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

/**
 * A line-wise row's title and subtitle, each with its end ([RowLine]). An end's text stands on its text's line, so it
 * takes its role's Latin line box too ([LocalRvRowLine]): a line's height then depends on no script, nor on whether its
 * end shows (節點's change, mixed, beside a subtitle held to the Latin box).
 */
@Composable
private fun LineWiseText(
    title: RowText,
    subtitle: RowText?,
    titleOverflow: TextOverflow,
    titleEnd: (@Composable () -> Unit)?,
    subtitleEnd: (@Composable () -> Unit)?,
    minHeight: Dp,
    top: Boolean,
    modifier: Modifier,
) {
    val lines: @Composable () -> Unit = {
        RowLine(titleEnd?.let(::onLine), title.takeIf { titleOverflow == TextOverflow.Ellipsis }, figure = true) {
            title.Shown(overflow = titleOverflow)
        }
        subtitle?.let {
            RowLine(subtitleEnd?.let(::onLine), it) { it.Shown(Rv.colors.textSecondary, subtitle = true) }
        }
    }
    if (top) RowTextColumn(minHeight, modifier, lines) else Column(modifier.padding(vertical = RvSpace.rowTextInset)) { lines() }
}

/**
 * A row's text lines stacked, at least [minHeight] tall with the lines centred in it and [RvSpace.rowTextInset] clear above
 * and below; it publishes where its first line starts ([RvTitleTop]), so the row's disc can stand on it (R9).
 */
@Composable
private fun RowTextColumn(minHeight: Dp, modifier: Modifier, content: @Composable () -> Unit) {
    Layout(content, modifier) { parts, constraints ->
        val inset = RvSpace.rowTextInset.roundToPx()
        val placed = parts.map { it.measure(constraints.copy(minWidth = 0, minHeight = 0)) }
        val block = placed.sumOf { it.height }
        val height = max(minHeight.roundToPx(), block + inset * 2)
        val first = ((height - block) / 2f).roundToInt()
        layout(constraints.maxWidth, height, mapOf(RvTitleTop to first)) {
            var y = first
            placed.forEach {
                it.place(0, y)
                y += it.height
            }
        }
    }
}

/** [end] on a row's line: its text in its role's Latin line box ([LocalRvRowLine]). */
private fun onLine(end: @Composable () -> Unit): @Composable () -> Unit = { CompositionLocalProvider(LocalRvRowLine provides true) { Box(Modifier.testTag(RvRowPart.LINE_END)) { end() } } }

/**
 * A name a user gave (a group's, a node's) as the end of a row's title line ([RvRow]'s `titleEnd`), in the row's value
 * type at the line's end. It takes as many lines as a title ([RV_ROW_TITLE_LINES]) with the platform's breaks, as a name
 * keeps them, so a name too long to stand beside its title wraps where it goes under the title rather than being cut.
 */
@Composable
internal fun RvRowEndName(name: String) = RvText(name, Rv.type.rowValue, align = TextAlign.End, maxLines = RV_ROW_TITLE_LINES, keepWords = false)

/** [end] keeping its room, neither drawn nor read out. */
private fun hidden(end: @Composable () -> Unit): @Composable () -> Unit = { Box(Modifier.alpha(0f).clearAndSetSemantics {}) { end() } }

/**
 * A line-wise row's [text] with a busy bar at its end, in place of its hidden ends: on the line of the title's end
 * where that end went under the title ([RvEndUnder]), where its figure stands at rest; else centred on the text.
 */
@Composable
private fun BusyAtEnd(modifier: Modifier, text: @Composable () -> Unit) {
    Layout({
        text()
        RvBusy(Modifier.width(RvSize.busy).testTag(RvPart.BUSY))
    }, modifier) { (textPart, busyPart), constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val shown = textPart.measure(loose)
        val bar = busyPart.measure(loose)
        val size = IntSize(max(constraints.minWidth, max(shown.width, bar.width)), max(constraints.minHeight, max(shown.height, bar.height)))
        val title = shown[RvTitleTop].takeUnless { it == AlignmentLine.Unspecified }
        val at = Alignment.CenterEnd.align(IntSize(shown.width, shown.height), size, layoutDirection)
        layout(size.width, size.height, if (title != null) mapOf(RvTitleTop to at.y + title) else emptyMap()) {
            shown.place(at)
            val line = shown[RvEndUnder]
            val barAt = Alignment.CenterEnd.align(IntSize(bar.width, bar.height), size, layoutDirection)
            bar.place(barAt.x, if (line == AlignmentLine.Unspecified) barAt.y else at.y + line - bar.height / 2)
        }
    }
}

/**
 * A row's title over its subtitle, with the row's [trailing] end beside them, [RvSpace.trailingGap] apart, both centred
 * on the row, at least [minHeight] tall (the row's, so they are centred once). The end never cuts the title nor squeezes
 * the subtitle: when the title or the subtitle does not read well beside the end ([rvReadsWell]; a node's name beside
 * its latency at a large font size, `Trojan ·` over `香港`), both take the whole line and the end goes under them, on the
 * line's end, its top where the text's last line ends ([LocalRvEndSide] tells its parts, which then share its first
 * line). The end is centred on the whole text, so a line that wraps well beside it may (the picker's `Hong Kong 02` over
 * `IPLC`), unless only part of the end shows ([besideShown]).
 */
@Composable
private fun TextBesideEnd(titleText: RowText, subtitleText: RowText?, titleOverflow: TextOverflow, minHeight: Dp, endLine: Dp?, trailing: @Composable () -> Unit, top: Boolean, modifier: Modifier) {
    val measurer = rememberTextMeasurer()
    val side = remember { RvEndSide() }
    Layout(
        {
            titleText.Shown(overflow = titleOverflow)
            subtitleText?.Shown(Rv.colors.textSecondary, subtitle = true)
            Box(Modifier.testTag(RvRowPart.END)) { CompositionLocalProvider(LocalRvEndSide provides side, trailing) }
        },
        modifier,
    ) { parts, constraints ->
        val width = constraints.maxWidth
        val inset = RvSpace.rowTextInset.roundToPx()
        val gap = RvSpace.trailingGap.roundToPx()
        val least = minHeight.roundToPx()
        val end = parts.last().measure(Constraints(maxWidth = max(0, width - gap)))
        val beside = max(0, width - gap - end.width)
        val titleFits = if (titleOverflow == TextOverflow.MiddleEllipsis) keepsHostEnds(titleText, beside) else rvReadsWell(measurer.layout(titleText, beside))
        val fits = titleFits && (subtitleText == null || rvReadsWell(measurer.layout(subtitleText, beside)))
        // Told before the end places its parts; read unobserved, so this layout does not run again for its own write.
        if (Snapshot.withoutReadObservation { side.under } == fits) side.under = !fits
        val textWidth = if (fits) measurer.besideShown(titleText, subtitleText, beside, width - gap - end.shownWidth()) else width
        val titleLine = parts[0].measure(Constraints(maxWidth = textWidth))
        val subtitleLine = if (subtitleText != null) parts[1].measure(Constraints(maxWidth = textWidth)) else null
        if (fits) {
            val text = titleLine.height + (subtitleLine?.height ?: 0) + inset * 2
            val height = maxOf(text, end.height, least)
            val first = Alignment.CenterVertically.align(text, height) + inset
            layout(width, height, if (top) mapOf(RvTitleTop to first) else emptyMap()) {
                titleLine.place(0, first)
                subtitleLine?.place(0, first + titleLine.height)
                // R11: a control stands on the title's first line, as Revolut's switch rows do; a value or chevron is centred.
                val at = endLine?.let { first + it.roundToPx() / 2 - end.height / 2 } ?: Alignment.CenterVertically.align(end.height, height)
                end.place(width - end.width, at)
            }
        } else {
            val text = titleLine.height + (subtitleLine?.height ?: 0)
            layout(width, max(least, inset * 2 + text + end.height), if (top) mapOf(RvTitleTop to inset) else emptyMap()) {
                titleLine.place(0, inset)
                subtitleLine?.place(0, inset + titleLine.height)
                end.place(width - end.width, inset + text)
            }
        }
    }
}

/**
 * Whether a title cut in the middle ([TextOverflow.MiddleEllipsis]: a host:port) keeps enough of both ends beside the row's
 * end: [beside] px hold at least [RV_HOST_MIN_EMS] of the title's font size. Less (the end of a large font size, 「gem...443」)
 * and the end goes under the title, which then takes the whole line.
 */
private fun Density.keepsHostEnds(title: RowText, beside: Int): Boolean = beside >= title.style.fontSize.toPx() * RV_HOST_MIN_EMS

/** Where a row's first text line starts, from the text part's top: the disc of a row with a subtitle stands on it. */
internal val RvTitleTop = HorizontalAlignmentLine(::min)

/** How wide what the end shows is: from where it starts ([RvEndInk], a busy bar in its latency's room) to its end. */
private fun Placeable.shownWidth(): Int = this[RvEndInk].let { if (it == AlignmentLine.Unspecified) width else width - it }

/**
 * The width a text beside an end takes: [beside], the end's room, unless only part of the end shows ([shown] px of the
 * line are left beside that part: a busy bar standing in for a latency) and the title takes one line there but more
 * beside the room. A name whole on one line while a test runs then stays whole (the picker's `Hong Kong 02 IPLC` at 1.0,
 * as before the room was kept), and only such a row changes its height when the test starts.
 */
private fun TextMeasurer.besideShown(title: RowText, subtitle: RowText?, beside: Int, shown: Int): Int {
    if (shown <= beside || layout(title, beside).lineCount == 1) return beside
    val whole = layout(title, shown).lineCount == 1 && (subtitle == null || rvReadsWell(layout(subtitle, shown)))
    return if (whole) shown else beside
}

/**
 * Where a row's end went (set by [TextBesideEnd] as it lays the row out, read by the end's parts as they place
 * themselves): [under] its text, or beside it.
 */
internal class RvEndSide {
    var under by mutableStateOf(false)
}

/** The side of the row's end its parts are in ([RvRowEnd], a latency's busy bar); none outside a row: beside. */
internal val LocalRvEndSide = staticCompositionLocalOf<RvEndSide?> { null }

/**
 * Where an end's first line ends, from its top (a latency's figure over its change): an end under its text puts its
 * busy bar and its glyph ([RvRowEnd]) on that line.
 */
internal val RvEndFirstLine = HorizontalAlignmentLine(::min)

/** Where what an end shows starts, from its start: a busy bar at the end of its latency's room. */
internal val RvEndInk = VerticalAlignmentLine(::max)

/** The middle of the line of a line-wise title's end that went under it ([RowLine]): the busy bar's line ([BusyAtEnd]). */
private val RvEndUnder = HorizontalAlignmentLine(::min)

/** This part's height as its end's first line ([RvEndFirstLine]): a latency's figure. */
internal fun Modifier.rvEndFirstLine(): Modifier = layout { measurable, constraints ->
    val part = measurable.measure(constraints)
    layout(part.width, part.height, mapOf(RvEndFirstLine to part.height)) { part.place(0, 0) }
}

/**
 * A row's end: its [value] and, after it, the [glyph] that belongs to it (節點's info mark). Beside the row's text the two
 * are centred on each other; where the end went under the text ([LocalRvEndSide]) the glyph sits on the value's first
 * line ([RvEndFirstLine]: a latency's figure, or the busy bar standing in for it), so it moves with the end, on its line.
 */
@Composable
internal fun RvRowEnd(value: @Composable () -> Unit, glyph: @Composable () -> Unit) {
    val side = LocalRvEndSide.current
    Layout({
        value()
        glyph()
    }) { (valuePart, glyphPart), constraints ->
        val shown = valuePart.measure(constraints.copy(minWidth = 0, minHeight = 0))
        val mark = glyphPart.measure(constraints.copy(minWidth = 0, minHeight = 0, maxWidth = max(0, constraints.maxWidth - shown.width)))
        val height = max(constraints.minHeight, max(shown.height, mark.height))
        layout(max(constraints.minWidth, shown.width + mark.width), height) {
            val line = shown[RvEndFirstLine]
            if (side?.under == true && line != AlignmentLine.Unspecified) {
                shown.place(0, 0)
                // Centred on the line even where its touch target is taller than the line and reaches above the end.
                mark.place(shown.width, line / 2 - mark.height / 2)
            } else {
                shown.place(0, Alignment.CenterVertically.align(shown.height, height))
                mark.place(shown.width, Alignment.CenterVertically.align(mark.height, height))
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
internal const val RV_ROW_TITLE_SHARE = 0.4f

/** How many ems of its font size a host:port cut in the middle keeps beside the row's end (about 16 characters) before the end goes under it. */
private const val RV_HOST_MIN_EMS = 8f

/**
 * One line of a line-wise [RvRow]: [start] keeps its own width up to [RV_ROW_TITLE_SHARE] of the line, [end] takes what it
 * needs of the rest, and [start] then gets what the end leaves. Where [end] does not fit whole on that line, or [start]
 * ([text], when given) does not read well beside it or would take more lines there than on the whole line, [end] goes
 * under [start] on the line's end and each has the whole line, so neither is cut or squeezed; the middle of its line is
 * then [RvEndUnder] on the title's line, whose end is the row's [figure].
 */
@Composable
private fun RowLine(end: (@Composable () -> Unit)?, text: RowText?, figure: Boolean = false, start: @Composable () -> Unit) {
    val measurer = rememberTextMeasurer()
    Layout({
        start()
        end?.invoke()
    }) { parts, constraints ->
        val width = constraints.maxWidth
        val gap = if (parts.size > 1) RvSpace.trailingGap.roundToPx() else 0
        val keep = min(parts[0].maxIntrinsicWidth(constraints.maxHeight), (width * RV_ROW_TITLE_SHARE).toInt())
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
            val line: Map<AlignmentLine, Int> = endPart?.takeIf { figure }?.let { mapOf(RvEndUnder to startPart.height + it.height / 2) } ?: emptyMap()
            layout(width, startPart.height + (endPart?.height ?: 0), line) {
                startPart.place(0, 0)
                endPart?.let { it.place(width - it.width, startPart.height) }
            }
        }
    }
}

/**
 * The gap between a row's title and its subtitle: none, the line boxes touch (LAYOUT-RULES Table 0). Only a comparison
 * sample provides another value, to show the owner what a gap would look like; no page does.
 */
internal val LocalRvTitleGap = compositionLocalOf { 0.dp } // literal: Table 0, title to subtitle is 0

/**
 * A row's text as it is laid out: its words, its role, how many lines it may take and whether its words are held
 * ([RvText]'s `keepWords`); [words] is the text as its [RvText] takes it ([rvWords]), taken once for the text.
 */
private class RowText(val text: String, val style: TextStyle, val lines: Int, val keepWords: Boolean) {
    val words = rvWords(text, lines, keepWords)

    /** The text in its role's Latin line box, as the row shows it; a [subtitle] stands [LocalRvTitleGap] under the title. */
    @Composable
    fun Shown(color: Color = Rv.colors.text, overflow: TextOverflow = TextOverflow.Ellipsis, subtitle: Boolean = false) =
        RvText(text, style, if (subtitle) Modifier.padding(top = LocalRvTitleGap.current) else Modifier, color, maxLines = lines, overflow = overflow, lineBox = true, keepWords = keepWords)
}

@Composable
private fun rememberRowText(text: String, style: TextStyle, lines: Int, keepWords: Boolean) = remember(text, style, lines, keepWords) { RowText(text, style, lines, keepWords) }

/** [text] laid out [width] px wide as its [RvText] lays it out. */
private fun TextMeasurer.layout(text: RowText, width: Int) = rvLayout(text.words, text.style, text.lines, width)

/**
 * Whether [text] reads well [width] px wide ([rvReadsWell]) on no more lines than it takes [wide] px wide: an end beside a
 * line may not make it wrap (`VLESS ·` over `臺灣`, `Hysteria` over `2`) where the whole line holds it.
 */
private fun TextMeasurer.keeps(text: RowText, width: Int, wide: Int): Boolean {
    val narrow = layout(text, width)
    return rvReadsWell(narrow) && (width >= wide || narrow.lineCount <= layout(text, wide).lineCount)
}
