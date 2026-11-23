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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import dev.doona.android.R

/**
 * A mark on a source file: the UTF-8 byte span [start] until [end] (zero-based, end-exclusive, as honk locates a
 * declaration) and the [message] the field shows and announces for it.
 */
@Immutable
internal class RvSourceMark(val start: Int, val end: Int, val message: String)

/**
 * The multiline source field (config plan, source editor): a card holding the file's text in the row subtitle's 17 sp,
 * wrapping, with each line's number in a gutter at its start, in secondary ink and tabular figures, [RvSpace.trailingGap]
 * before the text. Each of [marks] is tinted in the error hue as [RvStatusChip] tints its label and underlined, so the
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
    val lines = remember(text) { lineStarts(text) }
    val marked = remember(lines, ranges) { ranges.map { lineOf(lines, it.start) }.toSet() }
    val style = rvSourceStyle()
    val numbers = rvLineNumberStyle()
    val measurer = rememberTextMeasurer()
    val gutter = rvLineNumberWidth(measurer, lines.size, numbers)
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
                    .drawBehind {
                        val shown = layout ?: return@drawBehind
                        val ink = SourceInk(numbers.copy(color = colors.textSecondary), numbers.copy(color = colors.error))
                        drawLineNumbers(measurer, shown, lines, marked, ink, gutter.toPx())
                    },
                textStyle = style.copy(color = colors.text),
                visualTransformation = SourceMarks(ranges, tint),
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

/** The two inks of the gutter: a line's number, and the number of a line that holds a mark. */
private class SourceInk(val plain: TextStyle, val marked: TextStyle)

/**
 * Each line's number, ending at [right] and on the baseline of the line's first row in [layout] (a row with CJK in Noto
 * sits lower in its line box), so a wrapped line keeps one number; a line holding a mark in the marked ink.
 */
private fun DrawScope.drawLineNumbers(
    measurer: TextMeasurer,
    layout: TextLayoutResult,
    lines: IntArray,
    marked: Set<Int>,
    ink: SourceInk,
    right: Float,
) {
    lines.forEachIndexed { i, start ->
        val row = layout.getLineForOffset(start.coerceAtMost(layout.layoutInput.text.length))
        val number = measurer.measure((i + 1).toString(), if (i in marked) ink.marked else ink.plain)
        drawText(number, topLeft = Offset(right - number.size.width, layout.getLineBaseline(row) - number.firstBaseline))
    }
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

/** The source as the field draws it: CJK runs in Noto, as [RvText] sets them, and each marked span tinted. */
private data class SourceMarks(val ranges: List<TextRange>, val tint: SpanStyle) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val shown = buildAnnotatedString {
            append(scripted(text.text))
            ranges.forEach { addStyle(tint, it.start.coerceAtMost(text.length), it.end.coerceAtMost(text.length)) }
        }
        return TransformedText(shown, OffsetMapping.Identity)
    }
}

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
