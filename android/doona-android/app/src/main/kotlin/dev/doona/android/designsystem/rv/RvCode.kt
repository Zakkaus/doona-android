package dev.doona.android.designsystem.rv

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit

/** A coloured token: its [kind] from [start] until [end] (UTF-16, end-exclusive) of the text. */
@Immutable
internal data class RvCodeSpan(val kind: RvCodeKind, val start: Int, val end: Int)

/** The coloured tokens of [text] at their offsets. */
internal fun rvCodeSpans(text: String): List<RvCodeSpan> {
    var at = 0
    return rvCodeTokens(text).mapNotNull { token ->
        val start = at
        at += token.text.length
        token.kind?.let { RvCodeSpan(it, start, at) }
    }
}

/**
 * Where [text] may break inside a token: the offsets, ascending, right after a `(` or `,`, the `:` that ends a key
 * (`geosite:`), or a path's `/` (one not of a `//`: `test/dns-query`, never `https://`), when a character other than a
 * blank, a line feed or `)` follows, outside comments and literals. The platform breaks a line only at a blank or a
 * hyphen, so `domain(geosite:category-games@cn)` would otherwise move whole to the next line, or break mid-name; a
 * number's colons (`00:11:22:33:44:55`) stay together. In a bare address the platform's own rule would break before a
 * hyphen (`…/dns` over `-query`); the path's `/` comes first.
 */
internal fun rvCodeBreaks(text: String, spans: List<RvCodeSpan>): IntArray {
    val whole = spans.filter { it.kind == RvCodeKind.Comment || it.kind == RvCodeKind.Literal }
    val keys = spans.filter { it.kind == RvCodeKind.Key }.map { it.end }.toSet()
    val breaks = ArrayList<Int>()
    var next = 0
    for (i in 0 until text.length - 1) {
        // [whole] is in text order, so the first span not yet ended is the only one that can hold i.
        while (next < whole.size && whole[next].end <= i) next++
        val inside = next < whole.size && whole[next].start <= i
        val after = text[i] in BREAK_AFTER || (text[i] == ':' && i in keys) || pathSlash(text, i)
        if (after && text[i + 1] !in NO_BREAK_BEFORE && !inside) breaks += i + 1
    }
    return breaks.toIntArray()
}

/** Whether [i] is a `/` of a path: not after a blank or the start, and neither half of a `//`. */
private fun pathSlash(text: String, i: Int): Boolean =
    text[i] == '/' && i > 0 && !text[i - 1].isWhitespace() && text[i - 1] != '/' && text[i + 1] != '/'

private const val BREAK_AFTER = "(,"
private const val NO_BREAK_BEFORE = " \t\n)"

/** The break opportunity code text inserts: a zero-width space, which the platform may break at and never draws. */
internal const val RV_CODE_BREAK = '​'

/**
 * The offsets of a text of [length] shown with a [RV_CODE_BREAK] before each of [breaks] (and any characters after its
 * end, as the source field's last joiner): an original offset at a break lands after it.
 */
internal class RvCodeOffsets(private val breaks: IntArray, private val length: Int) : OffsetMapping {
    override fun originalToTransformed(offset: Int): Int = offset + count { breaks[it] <= offset }

    // A break's shown offset is breaks[k] + k, which rises with k as breaks[k] does.
    override fun transformedToOriginal(offset: Int): Int = (offset - count { breaks[it] + it < offset }).coerceAtMost(length)

    /** How many indices of [breaks] pass [before], which holds for a first run of them only: a binary search. */
    private inline fun count(before: (Int) -> Boolean): Int {
        var low = 0
        var high = breaks.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (before(mid)) low = mid + 1 else high = mid
        }
        return low
    }
}

/** [source] as code text shows it: its coloured [spans], its [breaks], the [text] with a break at each, and the [offsets]. */
@Immutable
internal class RvCodeShown(val source: String, val spans: List<RvCodeSpan> = rvCodeSpans(source)) {
    val breaks = rvCodeBreaks(source, spans)
    val offsets = RvCodeOffsets(breaks, source.length)
    val text: String = buildString(source.length + breaks.size) {
        var from = 0
        breaks.forEach { at ->
            append(source, from, at)
            append(RV_CODE_BREAK)
            from = at
        }
        append(source, from, source.length)
    }
}

/** Colours each of [shown]'s spans by its role in [colors], at its place in the shown text. */
internal fun AnnotatedString.Builder.addCode(shown: RvCodeShown, colors: RvCodeColors) {
    // The spans are in text order, so one pass over the breaks places them all.
    val breaks = shown.breaks
    var before = 0
    fun at(offset: Int): Int {
        while (before < breaks.size && breaks[before] <= offset) before++
        return offset + before
    }
    shown.spans.forEach { addStyle(colors.style(it.kind), at(it.start), at(it.end)) }
}

/**
 * [source] as code text: CJK in Noto ([scripted]), each token in its role's ink, breakable inside a long token; [spans]
 * are its own unless it is a line of a longer text, read whole ([rvCodeLines]).
 */
internal fun rvCodeText(source: String, colors: RvCodeColors, spans: List<RvCodeSpan> = rvCodeSpans(source)): AnnotatedString {
    val shown = RvCodeShown(source, spans)
    return buildAnnotatedString {
        append(scripted(shown.text))
        addCode(shown, colors)
    }
}

/**
 * honk text in [style]'s size, highlighted as honk-config reads it ([rvCodeTokens], [rvCodeColors]) in code's own look
 * ([rvCodeStyle]), plain text in ink: every place that shows honk's configuration text uses it, or [RvSourceField].
 * Each line wraps, inside a long token too, and hangs under its own indent ([rvHang]); [maxLines] is per line.
 */
@Composable
internal fun RvCodeText(code: String, style: TextStyle, modifier: Modifier = Modifier, maxLines: Int = Int.MAX_VALUE) {
    val colors = rememberRvCodeColors()
    val shown = style.rvCodeStyle().copy(color = Rv.colors.text)
    val space = rvSpaceWidth(shown, rememberTextMeasurer())
    val lines = remember(code) { rvCodeLines(code) }
    if (lines.size == 1) {
        CodeLine(lines.single(), shown, space, colors, modifier, maxLines)
    } else {
        Column(modifier) { lines.forEach { CodeLine(it, shown, space, colors, Modifier, maxLines) } }
    }
}

/**
 * [this] as code is drawn: Inter's ligatures off ([rvVerbatim]) and the regular weight, so code in a row title (a skipped
 * declaration) is no bolder than in the source field (owner 2026-11-25: no bold anywhere code is drawn).
 */
internal fun TextStyle.rvCodeStyle(): TextStyle = rvVerbatim().copy(fontWeight = FontWeight.Normal)

/** One line of a code text and its coloured tokens, at offsets within the line. */
@Immutable
internal class RvCodeLine(val text: String, val spans: List<RvCodeSpan>)

/**
 * [code]'s lines, each with its spans from [code] read whole: a group's entry (`hk {`) is known only from the file, and
 * no token spans a line feed.
 */
internal fun rvCodeLines(code: String): List<RvCodeLine> {
    val spans = rvCodeSpans(code)
    var next = 0
    var start = 0
    return code.split('\n').map { line ->
        val end = start + line.length
        val own = ArrayList<RvCodeSpan>()
        while (next < spans.size && spans[next].start < end) {
            spans[next].let { own += it.copy(start = it.start - start, end = it.end - start) }
            next++
        }
        RvCodeLine(line, own).also { start = end + 1 }
    }
}

/** One line of code text; a screen reader (and a search by text) gets the line as written, without the inserted breaks. */
@Composable
private fun CodeLine(line: RvCodeLine, style: TextStyle, space: TextUnit, colors: RvCodeColors, modifier: Modifier, maxLines: Int) {
    val hang = style.copy(textIndent = rvHang(rvBlankColumns(line.text), space))
    Box(modifier.clearAndSetSemantics { text = AnnotatedString(line.text) }) {
        BasicText(remember(line, colors) { rvCodeText(line.text, colors, line.spans) }, style = hang, overflow = TextOverflow.Ellipsis, maxLines = maxLines)
    }
}

/**
 * The ink of each code kind in one theme ([rvCodeColors]), all from the theme's colours (owner 2026-11-24: 「要麼就跟隨主題
 * 配色」), in the code's own weight (never bold: owner 2026-11-25). honk's own words, a block and a symbol, keep the accent
 * ([keyword], owner 2026-11-25; the two never meet on a line); a literal is the accent a step lighter on the page; a call
 * the tertiary; a key the colour halfway between; an operator that colour a step darker on the page; a comment the
 * secondary ink. Plain text, its punctuation included, is the text.
 */
@Immutable
internal data class RvCodeColors(
    val comment: Color,
    val keyword: Color,
    val literal: Color,
    val call: Color,
    val key: Color,
    val operator: Color,
) {
    fun style(kind: RvCodeKind): SpanStyle = SpanStyle(
        color = when (kind) {
            RvCodeKind.Comment -> comment
            RvCodeKind.Block, RvCodeKind.Symbol -> keyword
            RvCodeKind.Literal -> literal
            RvCodeKind.Call -> call
            RvCodeKind.Key -> key
            RvCodeKind.Operator -> operator
        },
    )
}

/**
 * Each code kind's ink in [colors]' theme: the accent ([RvColors.positive]) for a block and a symbol and, its Oklab
 * lightness [RvCodeShade.STEP] toward the page, for a literal; the tertiary ([RvColors.up]'s line) for a call; the two
 * [RvCodeShade.MID] apart (in Oklab) for a key and, a step away from the page, for an operator; the secondary ink for a
 * comment. A step keeps the hue and chroma, so the kinds stay apart (`RvCodeTest` measures ΔE*ab). Each, or the first
 * step from it toward black (light) or white (dark), reads at 4.5:1 on the page, the card, and a diff's added and removed
 * lines (their hue at [RvAlpha.STATUS_CHIP] over the card), as [RvColors] makes the latency hues readable.
 */
internal fun rvCodeColors(colors: RvColors): RvCodeColors {
    val tints = listOf(colors.good, colors.error).map { it.copy(alpha = RvAlpha.STATUS_CHIP).compositeOver(colors.card) }
    val grounds = listOf(colors.page, colors.card) + tints
    val toward = if (colors.dark) Color.White else Color.Black
    fun ink(from: Color) = (0..RvContrast.STEPS).map { lerp(from, toward, it.toFloat() / RvContrast.STEPS) }
        .firstOrNull { c -> grounds.all { rvContrast(c, it) >= RvContrast.TEXT_MIN } } ?: toward
    val page = if (colors.dark) -RvCodeShade.STEP else RvCodeShade.STEP
    val tertiary = colors.up.line
    val between = lerp(colors.positive, tertiary, RvCodeShade.MID)
    return RvCodeColors(
        comment = ink(colors.textSecondary),
        keyword = ink(colors.positive),
        literal = ink(colors.positive.lighter(page)),
        call = ink(tertiary),
        key = ink(between),
        operator = ink(between.lighter(-page)),
    )
}

/** This colour with its Oklab lightness moved [by] (lighter when positive), its hue and chroma kept. */
private fun Color.lighter(by: Float): Color {
    val lab = convert(ColorSpaces.Oklab)
    return Color((lab.red + by).coerceIn(0f, 1f), lab.green, lab.blue, alpha, ColorSpaces.Oklab).convert(ColorSpaces.Srgb)
}

/** [rvCodeColors] for the theme in use. */
@Composable
internal fun rememberRvCodeColors(): RvCodeColors {
    val colors = Rv.colors
    return remember(colors) { rvCodeColors(colors) }
}
