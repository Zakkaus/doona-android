package dev.doona.android.prototype.rv

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import dev.doona.android.R

/**
 * A mark on a source file: the UTF-8 byte span [start] until [end] (zero-based, end-exclusive, as honk locates a
 * declaration) and the [message] the field shows and announces for it.
 */
@Immutable
internal class RvSourceMark(val start: Int, val end: Int, val message: String)

/**
 * The multiline source field (config plan, source editor): a card holding the file's text in the row subtitle's 17 sp,
 * wrapping (also inside a long token, [rvCodeBreaks]), highlighted as all honk text is ([RvCodeText]'s roles), with
 * each line's number in a gutter at its start, in secondary ink and tabular figures, [RvSpace.trailingGap] before the
 * text. Each of [marks] is tinted in the error hue as [RvStatusChip] tints its label and underlined, so the
 * hue is not the only sign; its line's number turns the error hue. Under the card each mark's message
 * ([RvFieldMessage], led by its line) moves the cursor onto its span when tapped. The caller owns [value], so it can
 * also open the field at a span ([rvSourceSelection]). [name] (the file) names the field for a screen reader, which
 * hears the first mark's message as the field's error.
 */
@Composable
internal fun RvSourceField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    name: String,
    modifier: Modifier = Modifier,
    marks: List<RvSourceMark> = emptyList(),
) {
    val colors = Rv.colors
    val text = value.text
    val ranges = remember(text, marks) { marks.map { rvSourceSelection(text, it) } }
    val shown = remember(text) { RvCodeShown(text) }
    val inks = rememberRvCodeColors()
    val lines = remember(text) { lineStarts(text) }
    val marked = remember(lines, ranges) { ranges.map { lineOf(lines, it.start) }.toSet() }
    val style = rvSourceStyle()
    val numbers = rvLineNumberStyle()
    val measurer = rememberTextMeasurer()
    val space = rvSpaceWidth(style, measurer)
    val gutter = rvLineNumberWidth(measurer, lines.size, numbers)
    // Each number is measured once per count of lines and marks, not on every frame; an edit within a line keeps them.
    val labels = remember(lines.size, marked, numbers, colors, measurer) {
        List(lines.size) { measurer.measure((it + 1).toString(), numbers.copy(color = if (it in marked) colors.error else colors.textSecondary)) }
    }
    val tint = SpanStyle(color = colors.error, background = colors.error.copy(alpha = RvAlpha.STATUS_CHIP), textDecoration = TextDecoration.Underline)
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val focus = remember { FocusRequester() }
    val messageShape = remember { RoundedCornerShape(RvSize.highlightRadius) }
    Column(modifier.fillMaxWidth()) {
        RvCard {
            BasicTextField(
                value,
                onValueChange,
                Modifier
                    .padding(RvSpace.rowPadding)
                    .focusRequester(focus)
                    .semantics {
                        contentDescription = name
                        marks.firstOrNull()?.let { error(it.message) }
                    }
                    .drawWithCache {
                        // Placed once per layout of the text (the cache reads it), then only drawn on each frame.
                        val placed = layout?.let { placeLineNumbers(it, LineAnchors(lines, shown.offsets), labels, gutter.toPx()) }.orEmpty()
                        onDrawBehind { placed.forEach { (number, at) -> drawText(number, topLeft = at) } }
                    },
                textStyle = style.copy(color = colors.text),
                visualTransformation = SourceMarks(shown, ranges, tint, space, inks),
                onTextLayout = { layout = it },
                cursorBrush = SolidColor(colors.positive),
                decorationBox = { field ->
                    Row {
                        Spacer(Modifier.width(gutter + RvSpace.trailingGap))
                        Box(Modifier.weight(1f)) { field() }
                    }
                },
            )
        }
        marks.forEachIndexed { i, mark ->
            val line = lineOf(lines, ranges[i].start) + 1
            val jump = Modifier.rvPress(messageShape, Role.Button, onClick = {
                onValueChange(value.copy(selection = ranges[i]))
                focus.requestFocus()
            })
            RvFieldMessage(stringResource(R.string.rv_source_line, line, mark.message), jump)
        }
    }
}

/** Where each line starts in the source ([starts]) and where the shown text, with its breaks, has it ([offsets]). */
private class LineAnchors(val starts: IntArray, val offsets: RvCodeOffsets)

/**
 * Where each line's measured number ([labels], a line holding a mark in the marked ink) is drawn: ending at [right] and
 * on the baseline of the line's first row in [layout] (a row with CJK in Noto sits lower in its line box), so a
 * wrapped line keeps one number.
 */
private fun placeLineNumbers(layout: TextLayoutResult, lines: LineAnchors, labels: List<TextLayoutResult>, right: Float): List<Pair<TextLayoutResult, Offset>> =
    lines.starts.zip(labels) { start, number ->
        val row = layout.getLineForOffset(lines.offsets.originalToTransformed(start).coerceAtMost(layout.layoutInput.text.length))
        number to Offset(right - number.size.width, layout.getLineBaseline(row) - number.firstBaseline)
    }

/**
 * Source text: the row subtitle's 17 sp with Inter's contextual alternates and ligatures off, so `->` stays two
 * characters and does not become Inter's arrow. Every line takes the full 24 sp line box, untrimmed, so a line set
 * apart (a diff's row) is as tall with CJK in Noto, whose ascent and descent are taller than Inter's, as without.
 */
@Composable
internal fun rvSourceStyle(): TextStyle = Rv.type.rowSub.rvVerbatim().copy(lineHeightStyle = FULL_LINE)

/** [this] with Inter's contextual alternates and ligatures off, for any text that quotes source (`->` stays `->`). */
internal fun TextStyle.rvVerbatim(): TextStyle = copy(fontFeatureSettings = VERBATIM)

/** A line number: the row subtitle's 17 sp in tabular figures, so every number of a gutter ends on one edge. */
@Composable
internal fun rvLineNumberStyle(): TextStyle = Rv.type.rowSub.copy(fontFeatureSettings = RvTypeScale.TABULAR, lineHeightStyle = FULL_LINE)

/** A line box that keeps its whole height on the first and last line, its text centred in it. */
private val FULL_LINE = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None)

/** The width of the gutter for [count] lines: the widest number in tabular figures, all its digits the same width. */
@Composable
internal fun rvLineNumberWidth(measurer: TextMeasurer, count: Int, style: TextStyle): Dp {
    val widest = remember(count) { NINE.repeat(count.coerceAtLeast(1).toString().length) }
    val px = remember(widest, style, measurer) { measurer.measure(widest, style).size.width }
    return with(LocalDensity.current) { px.toDp() }
}

/**
 * The source as the field draws it ([shown]): CJK runs in Noto, as [RvText] sets them, a break opportunity inside each
 * long token, each token in its role's ink ([addCode]), then each marked span tinted over it, and each line a paragraph of its own that
 * hangs by its indent ([rvHang]; [space] is one blank's width). A paragraph that ends in a line feed shows an empty line
 * after it, so the feed is drawn as a word joiner, which has no width and keeps its offset; a text that ends in a feed
 * gets one more joiner for its last, empty line. [RvCodeOffsets] maps the cursor past the inserted breaks.
 */
private data class SourceMarks(val shown: RvCodeShown, val ranges: List<TextRange>, val tint: SpanStyle, val space: TextUnit, val inks: RvCodeColors) :
    VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val source = text.text
        val view = if (shown.source == source) shown else RvCodeShown(source)
        val at = view.offsets::originalToTransformed
        val closed = source.endsWith('\n')
        val total = source.length + if (closed) 1 else 0
        val drawn = buildAnnotatedString {
            append(scripted(view.text.replace('\n', LINE_END) + if (closed) LINE_END.toString() else ""))
            addCode(view, inks)
            ranges.forEach { addStyle(tint, at(it.start.coerceAtMost(source.length)), at(it.end.coerceAtMost(source.length))) }
            var from = 0
            while (from < total) {
                val end = source.indexOf('\n', from).let { if (it < 0 || it + 1 >= total) total else it + 1 }
                addStyle(ParagraphStyle(textIndent = rvHang(rvBlankColumns(source.substring(from, end.coerceAtMost(source.length))), space)), at(from), at(end))
                from = end
            }
        }
        return TransformedText(drawn, view.offsets)
    }
}

private const val LINE_END = '\u2060'

/** The width of one blank in [style]: what a column of a line's indent is. */
@Composable
internal fun rvSpaceWidth(style: TextStyle, measurer: TextMeasurer): TextUnit {
    val density = LocalDensity.current
    return remember(style, measurer, density) {
        val px = measurer.measure("a a", style).size.width - measurer.measure("aa", style).size.width
        with(density) { px.toSp() }
    }
}

/** How many columns of blanks [line] begins with: a space is one, a tab four. */
internal fun rvBlankColumns(line: CharSequence): Int {
    var columns = 0
    for (c in line) {
        columns += when (c) {
            ' ' -> 1
            '\t' -> TAB_COLUMNS
            else -> return columns
        }
    }
    return columns
}

/**
 * The indent a wrapped line's continuation takes: the line's own [columns] of leading blanks, so it hangs under its
 * first character of text and not under the margin, where it reads as the next line of the file.
 */
internal fun rvHang(columns: Int, space: TextUnit): TextIndent = if (columns == 0) TextIndent.None else TextIndent(restLine = space * columns)

private const val TAB_COLUMNS = 4

/** The selection in [text] of [mark]'s byte span: where the field puts the cursor to open at it. */
internal fun rvSourceSelection(text: String, mark: RvSourceMark): TextRange = TextRange(rvUtf16Index(text, mark.start), rvUtf16Index(text, mark.end))

/**
 * The UTF-16 index in [text] of the zero-based UTF-8 byte offset [byte]: honk counts bytes, Compose counts UTF-16
 * units, and the two part at the first character outside ASCII. A byte inside a character lands on that character's
 * start; an offset past the end lands at the end.
 */
internal fun rvUtf16Index(text: String, byte: Int): Int {
    var bytes = 0
    var i = 0
    while (i < text.length) {
        val point = text.codePointAt(i)
        bytes += utf8Length(point)
        if (bytes > byte) return i
        i += Character.charCount(point)
    }
    return text.length
}

private fun utf8Length(point: Int) = when {
    point < UTF8_ONE -> 1
    point < UTF8_TWO -> 2
    point < UTF8_THREE -> 3
    else -> 4
}

/** The UTF-16 start of each line of [text]: 0, then the index after each line feed. */
private fun lineStarts(text: String): IntArray {
    val starts = ArrayList<Int>().apply { add(0) }
    text.forEachIndexed { i, c -> if (c == '\n') starts.add(i + 1) }
    return starts.toIntArray()
}

/** The zero-based line of [lines] that holds UTF-16 index [index]. */
private fun lineOf(lines: IntArray, index: Int): Int {
    val found = lines.binarySearch(index)
    return if (found >= 0) found else -found - 2
}

/** The first code points that take two, three and four bytes in UTF-8. */
private const val UTF8_ONE = 0x80
private const val UTF8_TWO = 0x800
private const val UTF8_THREE = 0x10000
private const val NINE = "9"
private const val VERBATIM = "calt 0, liga 0"
