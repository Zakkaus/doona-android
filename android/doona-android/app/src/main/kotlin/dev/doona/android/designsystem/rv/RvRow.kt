package dev.doona.android.designsystem.rv

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// The one list row of every card: RvRow, with its line-wise ends and the title it never lets its end cut.

/**
 * A row of a grouped card: a disc at the start (none in a plain list: [leading] null), a title over an optional subtitle,
 * and trailing content at the end.
 *
 * [titleEnd] and [subtitleEnd] put a value at the end of the title's line and of the subtitle's line instead of one
 * trailing column beside both: each line then shares its width only with its own end. The start keeps its width up to
 * [RV_ROW_TITLE_SHARE] of the line, the end takes what it needs of the rest, and the start gets what is left, so a long name
 * (`Hong Kong 02 IPLC`) and a long subtitle (`自動測速 · 48 個節點`) both fit where one trailing column would squeeze the
 * text column to its widest end. [trailing] (a chevron) still follows after both lines.
 *
 * The text follows the one row rule ([RV_ROW_TITLE_LINES], [RV_ROW_SUBTITLE_LINES], [RvSpace.titleToSubtitle] between the lines, each
 * line its role's Latin line box whatever its script: [RvLineBox]);
 * [titleLines] and [titleOverflow] can keep a title to one line cut in the middle, which keeps both ends of a host:port
 * in view; [titleLines] or [subtitleLines] at [Int.MAX_VALUE] with an owner's exception ([titleWhole], [subtitleWhole]) show that text whole
 * ([RvRowPart.WHOLE]: a revision's own name, [RvWhole.RevisionName]); without one, the text is still held to R14, and an exception given to a text with a line limit is an error. [titleKeepWords] and [subtitleKeepWords] false keep a name's or an identifier's breaks the platform's
 * ([RvText]'s `keepWords`: a node's name, an app's label and package). Otherwise no end cuts the text it sits beside, nor is cut itself, nor makes it read badly: an end sits beside
 * its text where the text reads well there ([rvReadsWell]); else the text takes the whole line and the end goes under
 * it, on the line's end (a node's name beside its latency at a large font size; [TextBesideEnd], [RowLine]), never
 * beside a part of the text. A line-wise end, on its own line, also may not make that line wrap where the whole line
 * holds it, so the ends stay on their lines. An end under its text starts where the text's last line ends, with no gap,
 * and the row ends [RvSpace.rowTextInset] under it. While [endBusy] (a
 * latency test runs for the row), a busy bar stands at the row's end in place of [titleEnd] and [subtitleEnd], which
 * keep their room, so the text keeps its width and its lines: centred on the text, or on the line of an end that went
 * under it.
 *
 * [endFirstLine] puts the end of a row with a subtitle on the title's first line (R11): a control's.
 *
 * A [settings] row stands as every settings row has (`RvSettingsRow`): its end on the title's first line with a subtitle
 * or without, in a room as tall as that line (a chip, a remove target overflow it, centred), beside the text at every
 * font size (the text wraps in what the end leaves; it never goes under), and, without a subtitle, its leading disc
 * centred on that first line too, so a title that wraps keeps both on its first line. An [action] (an in-card button,
 * 40 dp as the disc) takes the end's place there: with a subtitle its top is on the title's top, as the disc's, so the
 * two 40 dp ends share one rule; without one it is centred on the first line. A title that is honk text is [titleCode],
 * highlighted as all honk text is ([RvCodeText]) in the code view's own line boxes. A subtitle that says an action
 * failed is [subtitleError].
 */
@Composable
internal fun RvRow(
    leading: (@Composable () -> Unit)?,
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
    subtitleLines: Int = RV_ROW_SUBTITLE_LINES,
    titleWhole: RvWhole? = null,
    subtitleWhole: RvWhole? = null,
    endBusy: Boolean = false,
    endFirstLine: Boolean = false,
    settings: Boolean = false,
    titleCode: Boolean = false,
    subtitleError: Boolean = false,
    action: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    rvRequireWhole(titleWhole, titleLines)
    rvRequireWhole(subtitleWhole, subtitleLines)
    require(action == null || trailing == null) { "a row ends in an action or in a trailing part, not both" }
    // The card clips the layer of its first and last row to its corners.
    val press = onClick?.let { Modifier.rvRowPress(Role.Button, it, onLongClick) } ?: Modifier
    val lineWise = titleEnd != null || subtitleEnd != null
    val end = trailing ?: action
    val beside = end != null && !lineWise
    val minHeight = if (subtitle == null) RvSize.rowOneLine else RvSize.rowTwoLine
    val titleText = rememberRowText(title, Rv.type.rowTitle, titleLines, titleKeepWords, titleWhole, Rv.colors.text, titleCode)
    // A subtitle that says an action failed is in the error hue, as a field's error message (RvFieldMessage).
    val hue = if (subtitleError) Rv.colors.error else Rv.colors.textSecondary
    val subtitleText = subtitle?.let { rememberRowText(it, Rv.type.rowSub, subtitleLines, subtitleKeepWords, subtitleWhole, hue) }
    // LAYOUT-RULES.md R9: with a subtitle the disc's top is the title's top, so the text publishes where its title starts.
    val top = subtitle != null
    val firstLine = if (settings || (endFirstLine && top)) maxOf(RvSize.rowOneLine - RvSpace.rowTextInset * 2, rvOneLine(title, Rv.type.rowTitle, lineBox = !titleCode)) else null
    // A settings row's text publishes its title's top with or without a subtitle: its disc and end stand on its first line.
    val anchored = top || settings
    val linePx = firstLine?.let { with(LocalDensity.current) { it.roundToPx() } } ?: 0
    Row(
        modifier
            .fillMaxWidth()
            .testTag(RvPart.ROW)
            .heightIn(min = minHeight)
            .then(press)
            .padding(horizontal = RvSpace.rowPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // A row of a plain list (a radio list of settings) has no leading visual: its text starts on the row's padding.
        leading?.let {
            when {
                // A settings row's disc is centred on the first line's box (its offset in the box rounded toward zero, as the
                // settings row always placed it); with a subtitle, one as tall as the box or taller has its top on the box's.
                settings -> Box(Modifier.alignBy { part -> if (top && part.measuredHeight >= linePx) linePx / 2 else linePx / 2 - (linePx - part.measuredHeight) / 2 }) { it() }

                top -> Box(Modifier.alignBy { 0 }) { it() }

                else -> it()
            }
            Spacer(Modifier.width(RvSpace.rowGap))
        }
        val text = when {
            settings && leading != null -> Modifier.weight(1f).alignBy(RvFirstLineMiddle)
            top -> Modifier.weight(1f).alignBy(RvTitleTop)
            else -> Modifier.weight(1f)
        }.then(if (settings) Modifier.testTag(RvRowPart.SETTINGS_ROW) else Modifier)
        if (beside) {
            TextBesideEnd(titleText, subtitleText, titleOverflow, EndAt(firstLine, tall = action != null, settings = settings), end, anchored, text, minHeight)
        } else {
            RowTextBlock(titleText, subtitleText, titleOverflow, LineEnds(titleEnd, subtitleEnd, endBusy), minHeight, anchored, text, firstLine.takeIf { settings })
        }
        if (!beside) {
            end?.let {
                Spacer(Modifier.width(RvSpace.trailingGap))
                it()
            }
        }
    }
}

/**
 * Where a row's end beside its text stands: centred on the row, or, with a [line] (the title's first line: [RvRow]'s
 * endFirstLine with a subtitle, or a [settings] row's), centred on that line; a [tall] end (an in-card button, [RvRow]'s
 * action) at least as tall as that line, in a row with a subtitle, has its top on the title's top instead (Table 0). A
 * [settings] row's end stands in a room as tall as its line and never goes under its text.
 */
private class EndAt(val line: Dp?, val tall: Boolean, val settings: Boolean)

/** A line-wise row's ends ([RvRow]'s titleEnd and subtitleEnd), and whether a busy bar stands in for them. */
private class LineEnds(val title: (@Composable () -> Unit)?, val subtitle: (@Composable () -> Unit)?, val busy: Boolean)

/**
 * [RvRow]'s text where no end stands beside it in a trailing column: line-wise with its [ends] ([LineWiseText]), the
 * same under a busy bar that keeps the ends' room while they are busy ([BusyAtEnd]), or title over subtitle, from the
 * title's top with a subtitle ([RowTextColumn]) and centred in the row without one.
 */
@Composable
private fun RowTextBlock(titleText: RowText, subtitleText: RowText?, titleOverflow: TextOverflow, ends: LineEnds, minHeight: Dp, top: Boolean, modifier: Modifier, line: Dp?) {
    val lineWise = ends.title != null || ends.subtitle != null
    if (lineWise && ends.busy) {
        BusyAtEnd(modifier) {
            LineWiseText(titleText, subtitleText, titleOverflow, ends.title?.let(::hidden), ends.subtitle?.let(::hidden), minHeight, top, Modifier)
        }
    } else if (lineWise) {
        LineWiseText(titleText, subtitleText, titleOverflow, ends.title, ends.subtitle, minHeight, top, modifier)
    } else if (top) {
        RowTextColumn(minHeight, modifier, line) {
            titleText.Shown(overflow = titleOverflow)
            subtitleText?.let { UnderTitle { it.Shown() } }
        }
    } else {
        Column(modifier.padding(vertical = RvSpace.rowTextInset)) {
            titleText.Shown(overflow = titleOverflow)
            subtitleText?.let { UnderTitle { it.Shown() } }
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
            UnderTitle { RowLine(subtitleEnd?.let(::onLine), it) { it.Shown() } }
        }
    }
    if (top) RowTextColumn(minHeight, modifier, content = lines) else Column(modifier.padding(vertical = RvSpace.rowTextInset)) { lines() }
}

/**
 * A row's text lines stacked, at least [minHeight] tall with the lines centred in it and [RvSpace.rowTextInset] clear above
 * and below; it publishes where its first line starts ([RvTitleTop]), so the row's disc can stand on it (R9).
 */
@Composable
private fun RowTextColumn(minHeight: Dp, modifier: Modifier, line: Dp? = null, content: @Composable () -> Unit) {
    Layout(content, modifier) { parts, constraints ->
        val inset = RvSpace.rowTextInset.roundToPx()
        val placed = parts.map { it.measure(constraints.copy(minWidth = 0, minHeight = 0)) }
        val block = placed.sumOf { it.height }
        val height = max(minHeight.roundToPx(), block + inset * 2)
        val first = ((height - block) / 2f).roundToInt()
        layout(constraints.maxWidth, height, mapOf(RvTitleTop to first) + firstLineMiddle(line?.roundToPx(), first, block)) {
            var y = first
            placed.forEach {
                it.place(0, y)
                y += it.height
            }
        }
    }
}

/** A row's subtitle line, [RvSpace.titleToSubtitle] under its title's (R12; owner, q-s1125c-titlegap). */
@Composable
private fun UnderTitle(line: @Composable () -> Unit) {
    Spacer(Modifier.height(RvSpace.titleToSubtitle))
    line()
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
private fun TextBesideEnd(titleText: RowText, subtitleText: RowText?, titleOverflow: TextOverflow, endAt: EndAt, trailing: @Composable () -> Unit, top: Boolean, modifier: Modifier, minHeight: Dp) {
    val measurer = rememberTextMeasurer()
    val side = remember { RvEndSide() }
    Layout(
        {
            titleText.Shown(overflow = titleOverflow)
            subtitleText?.Shown()
            // A settings row's end stands in its first line's box, as tall as the line whatever the end draws (a chip, a
            // remove target, taller than the line, overflow it centred), so the row's height stays the text's.
            val room = endAt.line?.takeIf { endAt.settings && !endAt.tall }
            // An in-card button takes its own width, not the row's (a button that fills what it is given).
            val own = when {
                room != null -> Modifier.wrapContentHeight(unbounded = true)
                endAt.tall -> Modifier.wrapContentWidth(unbounded = true)
                else -> Modifier
            }
            Box(Modifier.testTag(RvRowPart.END).then(room?.let { Modifier.height(it) } ?: Modifier), contentAlignment = Alignment.Center) {
                Box(own) { CompositionLocalProvider(LocalRvEndSide provides side, trailing) }
            }
        },
        modifier,
    ) { parts, constraints ->
        val width = constraints.maxWidth
        val inset = RvSpace.rowTextInset.roundToPx()
        val gap = RvSpace.trailingGap.roundToPx()
        val least = minHeight.roundToPx()
        val under = if (subtitleText != null) RvSpace.titleToSubtitle.roundToPx() else 0
        val end = parts.last().measure(Constraints(maxWidth = max(0, width - gap)))
        val beside = max(0, width - gap - end.width)
        // Honk code wraps where it must and is read as code, not prose: beside its end it always reads (a skipped declaration).
        val titleFits = when {
            titleText.code -> true
            titleOverflow == TextOverflow.MiddleEllipsis -> keepsHostEnds(titleText, beside)
            else -> rvReadsWell(measurer.layout(titleText, beside))
        }
        val fits = endAt.settings || (titleFits && (subtitleText == null || rvReadsWell(measurer.layout(subtitleText, beside))))
        // Told before the end places its parts; read unobserved, so this layout does not run again for its own write.
        if (Snapshot.withoutReadObservation { side.under } == fits) side.under = !fits
        val textWidth = if (fits) measurer.besideShown(titleText, subtitleText, beside, width - gap - end.shownWidth()) else width
        val titleLine = parts[0].measure(Constraints(maxWidth = textWidth))
        val subtitleLine = if (subtitleText != null) parts[1].measure(Constraints(maxWidth = textWidth)) else null
        if (fits) {
            val text = titleLine.height + under + (subtitleLine?.height ?: 0) + inset * 2
            val height = maxOf(text, end.height, least)
            val first = Alignment.CenterVertically.align(text, height) + inset
            val box = endAt.line?.roundToPx()
            val middle = firstLineMiddle(box, first, text - inset * 2)
            layout(width, height, (if (top) mapOf(RvTitleTop to first) else emptyMap()) + middle.takeIf { endAt.settings }.orEmpty()) {
                titleLine.place(0, first)
                subtitleLine?.place(0, first + titleLine.height + under)
                // R11: a control stands on the title's first line, as Revolut's switch rows do; a value or chevron is centred.
                // An in-card button as tall as that line or taller stands as the disc does, its top on the title's top.
                val at = box?.let { if (endAt.tall && subtitleText != null && end.height >= it) first else endOnLine(middle.getValue(RvFirstLineMiddle), it, end.height, endAt.settings) } ?: Alignment.CenterVertically.align(end.height, height)
                end.place(width - end.width, at)
            }
        } else {
            val text = titleLine.height + under + (subtitleLine?.height ?: 0)
            layout(width, max(least, inset * 2 + text + end.height), if (top) mapOf(RvTitleTop to inset) else emptyMap()) {
                titleLine.place(0, inset)
                subtitleLine?.place(0, inset + titleLine.height + under)
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

/**
 * The middle of a row's first-line box ([RvFirstLineMiddle]), [box] px tall: its top is the title's top ([first]), or,
 * where the text [block] is shorter than the box (a title of one line), the box holds the text centred, so its top
 * stands above the title's. None without a box.
 */
private fun firstLineMiddle(box: Int?, first: Int, block: Int): Map<AlignmentLine, Int> = box?.let { mapOf<AlignmentLine, Int>(RvFirstLineMiddle to first - max(0, it - block) / 2 + it / 2) }.orEmpty()

/**
 * Where an end [height] px tall starts, centred on the first-line box of [box] px whose middle is [middle]: a settings
 * row's offset in the box is rounded toward zero, as the settings row always placed it.
 */
private fun endOnLine(middle: Int, box: Int, height: Int, settings: Boolean): Int = if (settings) middle - box / 2 + (box - height) / 2 else middle - height / 2

/** The middle of a settings row's first-line box ([firstLineMiddle]): its disc and end are centred on it. */
private val RvFirstLineMiddle = HorizontalAlignmentLine(::min)

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
internal val RvEndUnder = HorizontalAlignmentLine(::min)

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
 * A row end's glyph button ([RvRowEnd]'s glyph: 節點's info mark), [RvSpace.trailingGap] after the value it follows: laid
 * out at its ink, the 22 dp glyph, so it takes from the row's text only what it draws (it took a 48 dp box, 26 dp more:
 * `Hong Kong 02 IPLC` wrapped beside it, s1125c). Its 48 dp target ([RvRowPart.TARGET]) is placed round the glyph past
 * that layout, so it reaches into the row's padding and over the gap; the glyph ends where every trailing ink ends.
 */
@Composable
internal fun RvEndMark(@DrawableRes glyph: Int, description: String, onClick: () -> Unit) {
    Layout({
        Box(
            Modifier
                .size(RvSize.touch)
                .testTag(RvRowPart.TARGET)
                .rvPress(CircleShape, Role.Button, onClick)
                .semantics { contentDescription = description },
            contentAlignment = Alignment.Center,
        ) { RvGlyph(glyph, RvSize.rowGlyph, Rv.colors.control) }
    }) { (target), _ ->
        val box = target.measure(Constraints())
        val gap = RvSpace.trailingGap.roundToPx()
        val ink = RvSize.rowGlyph.roundToPx()
        layout(gap + ink, ink) { box.place(gap - (box.width - ink) / 2, -(box.height - ink) / 2) }
    }
}

/** How many ems of its font size a host:port cut in the middle keeps beside the row's end (about 16 characters) before the end goes under it. */
private const val RV_HOST_MIN_EMS = 8f
