package dev.doona.android.designsystem.rv

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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

/**
 * A token class of honk's `.dae` text, as doona web's highlighter names them (`daeTokens.ts`): [Quoted] is its
 * `string`, [PropertyName] a call such as `domain(…)`, [VariableName] a key before its colon. Plain text has none.
 */
internal enum class RvCodeKind { Comment, Quoted, Keyword, Number, PropertyName, VariableName, Punctuation, Operator }

/** One token: its [text] and its [kind] (null for plain text, blanks included). The tokens of a text spell it whole. */
@Immutable
internal data class RvCodeToken(val text: String, val kind: RvCodeKind?)

/**
 * honk text split as doona web highlights it (`daeTokens.ts`, ported rule for rule and in its order), line by line: a
 * comment from `#` to the line's end; a quoted string; `->` an operator, and the next word a keyword (where the rule
 * sends); at a line's start, after blanks, a section's name (`global`, `dns`, `upstream`, `routing`, `request`,
 * `response`, `subscription`, `node`, `group`, `include`, `fallback`) a keyword, unless a `(` follows (then it is a
 * call, as `upstream(cloudflare)`); a number with its dots, prefix length and unit (`10.0.0.0/24`, `10ms`); a word
 * before `(` a property name, before `:` a variable name; any other word plain, so the digits of `sg-01` are not a
 * number; and each of `{ } ( ) & | ! , :` punctuation. Every other character, and each line feed, is plain.
 */
internal fun rvCodeTokens(text: String): List<RvCodeToken> {
    val tokens = ArrayList<RvCodeToken>()
    val lines = text.split('\n')
    val state = CodeState()
    lines.forEachIndexed { index, line ->
        var pos = 0
        while (pos < line.length) {
            val (end, kind) = codeToken(line, pos, state)
            tokens += RvCodeToken(line.substring(pos, end), kind)
            pos = end
        }
        if (index < lines.size - 1) tokens += RvCodeToken("\n", null)
    }
    return tokens
}

/** doona's `DaeState`: whether the line has just had `->`, so its next word is where the rule sends. */
private class CodeState {
    var afterArrow = false
}

/** doona's `daeToken`, first half: the end of the token at [pos] of [line] and its kind. */
private fun codeToken(line: String, pos: Int, state: CodeState): Pair<Int, RvCodeKind?> {
    if (pos == 0) state.afterArrow = false
    SPACE.end(line, pos)?.let { return it to null }
    if (line[pos] == '#') return line.length to RvCodeKind.Comment
    QUOTED.end(line, pos)?.let { return it to RvCodeKind.Quoted }
    if (line.startsWith(ARROW, pos)) {
        state.afterArrow = true
        return pos + ARROW.length to RvCodeKind.Operator
    }
    if (state.afterArrow) {
        TARGET.end(line, pos)?.let {
            state.afterArrow = false
            return it to RvCodeKind.Keyword
        }
    }
    return wordToken(line, pos)
}

/** doona's `daeToken`, second half: a section, a number, a call, a key, a plain word, punctuation, or one character. */
private fun wordToken(line: String, pos: Int): Pair<Int, RvCodeKind?> {
    if (line.substring(0, pos).isBlank()) SECTION.end(line, pos)?.let { return it to RvCodeKind.Keyword }
    NUMBER.end(line, pos)?.let { return it to RvCodeKind.Number }
    CALL.end(line, pos)?.let { return it to RvCodeKind.PropertyName }
    KEY.end(line, pos)?.let { return it to RvCodeKind.VariableName }
    WORD.end(line, pos)?.let { return it to null }
    return pos + 1 to RvCodeKind.Punctuation.takeIf { line[pos] in PUNCTUATION }
}

/** Where this pattern's match at [pos] of [line] ends, or null when it does not match there. */
private fun Regex.end(line: String, pos: Int): Int? = matchAt(line, pos)?.let { pos + it.value.length }

// doona's patterns in Java's syntax with JavaScript's classes spelled out (`\w`, `\d`, `\s`, `\b`), so the device's ICU
// regex, whose `\w` takes every script, reads them as the tests do.
private const val WS = """[\t\x0B\f\r \u00A0\u1680\u2000-\u200A\u2028\u2029\u202F\u205F\u3000\uFEFF]"""
private const val ARROW = "->"
private const val PUNCTUATION = "{}()&|!,:"
private val SPACE = Regex("$WS+")
private val QUOTED = Regex("""'[^']*'|"[^"]*"""")
private val TARGET = Regex("""[A-Za-z0-9_.-]+""")
private val SECTION = Regex("""(?:global|dns|upstream|routing|request|response|subscription|node|group|include|fallback)(?![A-Za-z0-9_])(?!$WS*\()""")
private val NUMBER = Regex("""[0-9]+(?:\.[0-9]+)*(?:/[0-9]+)?[a-z]*""")
private val CALL = Regex("""[A-Za-z0-9_.-]+(?=$WS*\()""")
private val KEY = Regex("""[A-Za-z0-9_.-]+(?=$WS*:)""")
private val WORD = Regex("""[A-Za-z_][A-Za-z0-9_.-]*""")

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
 * Where [text] may break inside a token: the offsets, ascending, right after a `(` or `,`, or the `:` that ends a key
 * (`geosite:`), when a character other than a blank, a line feed or `)` follows, outside comments and quoted strings.
 * The platform breaks a line only at a blank or a hyphen, so `domain(geosite:category-games@cn)` would otherwise move
 * whole to the next line, or break mid-name; a number's colons (`00:11:22:33:44:55`) stay together.
 */
internal fun rvCodeBreaks(text: String, spans: List<RvCodeSpan>): IntArray {
    val whole = spans.filter { it.kind == RvCodeKind.Comment || it.kind == RvCodeKind.Quoted }
    val keys = spans.filter { it.kind == RvCodeKind.VariableName }.map { it.end }.toSet()
    val breaks = ArrayList<Int>()
    var next = 0
    for (i in 0 until text.length - 1) {
        // [whole] is in text order, so the first span not yet ended is the only one that can hold i.
        while (next < whole.size && whole[next].end <= i) next++
        val inside = next < whole.size && whole[next].start <= i
        val after = text[i] in BREAK_AFTER || (text[i] == ':' && i in keys)
        if (after && text[i + 1] !in NO_BREAK_BEFORE && !inside) breaks += i + 1
    }
    return breaks.toIntArray()
}

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
internal class RvCodeShown(val source: String) {
    val spans = rvCodeSpans(source)
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

/** [source] as code text: CJK in Noto ([scripted]), each token in its role's ink, breakable inside a long token. */
internal fun rvCodeText(source: String, colors: RvCodeColors): AnnotatedString {
    val shown = RvCodeShown(source)
    return buildAnnotatedString {
        append(scripted(shown.text))
        addCode(shown, colors)
    }
}

/**
 * honk text in [style], highlighted as doona web highlights it ([rvCodeTokens], [rvCodeColors]) with Inter's ligatures
 * off ([rvVerbatim]), plain text in ink: every place that shows honk's configuration text uses it, or [RvSourceField].
 * Each line wraps, inside a long token too, and hangs under its own indent ([rvHang]); [maxLines] is per line.
 */
@Composable
internal fun RvCodeText(code: String, style: TextStyle, modifier: Modifier = Modifier, maxLines: Int = Int.MAX_VALUE) {
    val colors = rememberRvCodeColors()
    val shown = style.rvVerbatim().copy(color = Rv.colors.text)
    val space = rvSpaceWidth(shown, rememberTextMeasurer())
    val lines = remember(code) { code.split('\n') }
    if (lines.size == 1) {
        CodeLine(code, shown, space, colors, modifier, maxLines)
    } else {
        Column(modifier) { lines.forEach { CodeLine(it, shown, space, colors, Modifier, maxLines) } }
    }
}

/** One line of code text; a screen reader (and a search by text) gets the line as written, without the inserted breaks. */
@Composable
private fun CodeLine(line: String, style: TextStyle, space: TextUnit, colors: RvCodeColors, modifier: Modifier, maxLines: Int) {
    val hang = style.copy(textIndent = rvHang(rvBlankColumns(line), space))
    Box(modifier.clearAndSetSemantics { text = AnnotatedString(line) }) {
        BasicText(remember(line, colors) { rvCodeText(line, colors) }, style = hang, overflow = TextOverflow.Ellipsis, maxLines = maxLines)
    }
}

/**
 * The ink of each code role in one theme ([rvCodeColors]), as doona's roles go: a comment muted, a keyword the accent
 * and bold, a string, a number, a call (function), a key in the text colour, an operator and punctuation subtle.
 */
@Immutable
internal data class RvCodeColors(
    val comment: Color,
    val keyword: Color,
    val quoted: Color,
    val number: Color,
    val function: Color,
    val key: Color,
    val subtle: Color,
) {
    fun style(kind: RvCodeKind): SpanStyle = when (kind) {
        RvCodeKind.Comment -> SpanStyle(color = comment)
        RvCodeKind.Keyword -> SpanStyle(color = keyword, fontWeight = FontWeight.Bold)
        RvCodeKind.Quoted -> SpanStyle(color = quoted)
        RvCodeKind.Number -> SpanStyle(color = number)
        RvCodeKind.PropertyName -> SpanStyle(color = function)
        RvCodeKind.VariableName -> SpanStyle(color = key)
        RvCodeKind.Punctuation, RvCodeKind.Operator -> SpanStyle(color = subtle)
    }
}

/**
 * Each code role's ink in [colors]' theme: a comment the secondary ink (doona's muted), a keyword the accent, a string
 * the notice amber ([RvColors.fair]), a number and a call their [RvCodeHue], a key the text, an operator and punctuation
 * the secondary ink [RvCodeShade.SUBTLE] of the way to the text; each, or the first step from it toward black (light) or
 * white (dark), that reads at 4.5:1 on the page, the card, and a diff's added and removed lines (their hue at
 * [RvAlpha.STATUS_CHIP] over the card), as [RvColors] makes the latency hues readable.
 */
internal fun rvCodeColors(colors: RvColors): RvCodeColors {
    val tints = listOf(colors.good, colors.error).map { it.copy(alpha = RvAlpha.STATUS_CHIP).compositeOver(colors.card) }
    val grounds = listOf(colors.page, colors.card) + tints
    val toward = if (colors.dark) Color.White else Color.Black
    fun ink(from: Color) = (0..RvContrast.STEPS).map { lerp(from, toward, it.toFloat() / RvContrast.STEPS) }
        .firstOrNull { c -> grounds.all { rvContrast(c, it) >= RvContrast.TEXT_MIN } } ?: toward
    val hues = if (colors.dark) RvCodeHue.dark else RvCodeHue.light
    return RvCodeColors(
        comment = ink(colors.textSecondary),
        keyword = ink(colors.positive),
        quoted = ink(colors.fair),
        number = ink(hues.number),
        function = ink(hues.function),
        key = ink(colors.text),
        subtle = ink(lerp(colors.textSecondary, colors.text, RvCodeShade.SUBTLE)),
    )
}

/** [rvCodeColors] for the theme in use. */
@Composable
internal fun rememberRvCodeColors(): RvCodeColors {
    val colors = Rv.colors
    return remember(colors) { rvCodeColors(colors) }
}
