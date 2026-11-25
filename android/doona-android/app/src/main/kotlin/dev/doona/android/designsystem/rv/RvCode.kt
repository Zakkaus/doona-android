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
import androidx.compose.ui.platform.testTag
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
 * (`geosite:`), or a bare address's `/` ([addressSlash]: `test/dns-query`, never `https://`), when a character other
 * than a blank, a line feed or `)` follows, outside comments and literals. The platform breaks a line only at a blank
 * or a hyphen, so `domain(geosite:category-games@cn)` would otherwise move whole to the next line, or break mid-name; a
 * number's colons (`00:11:22:33:44:55`) stay together. In a bare address the platform's own rule would break before a
 * hyphen (`…/dns` over `-query`); the path's `/` comes first.
 */
internal fun rvCodeBreaks(text: String, spans: List<RvCodeSpan>): IntArray {
    val keys = spans.filter { it.kind == RvCodeKind.Key }.map { it.end }.toSet()
    return marks(text, spans) { i -> text[i] in BREAK_AFTER || (text[i] == ':' && i in keys) || addressSlash(text, i) == true }
}

/**
 * Where [text] must not break although the platform would: right after a `/` inside a word that is not an address's
 * ([addressSlash] false: `Fixture/1.0`), outside comments and literals. The platform breaks after any `/`, which put a
 * product token's version alone on a line.
 */
internal fun rvCodeJoins(text: String, spans: List<RvCodeSpan>): IntArray = marks(text, spans) { i -> addressSlash(text, i) == false }

/** The offsets right after each [i] for which [after] holds, when a character other than a blank, a line feed or `)` follows, outside comments and literals. */
private inline fun marks(text: String, spans: List<RvCodeSpan>, after: (Int) -> Boolean): IntArray {
    val whole = spans.filter { it.kind == RvCodeKind.Comment || it.kind == RvCodeKind.Literal }
    val found = ArrayList<Int>()
    var next = 0
    for (i in 0 until text.length - 1) {
        // [whole] is in text order, so the first span not yet ended is the only one that can hold i.
        while (next < whole.size && whole[next].end <= i) next++
        val inside = next < whole.size && whole[next].start <= i
        if (!inside && text[i + 1] !in NO_BREAK_BEFORE && after(i)) found += i + 1
    }
    return found.toIntArray()
}

/**
 * For a `/` at [i] inside a word (not its first character, neither half of a `//`): whether it is a bare address's,
 * in a word that opens with a scheme (`https://`), a host (`dns.example.test/`) or a `/` (`/var/share`). A product
 * token's `/` (`Fixture/1.0`) is not. Null for any other character.
 */
private fun addressSlash(text: String, i: Int): Boolean? {
    if (text[i] != '/' || i == 0 || text[i - 1] in WORD_END || text[i - 1] == '/' || text[i + 1] == '/') return null
    var start = i
    while (start > 0 && text[start - 1] !in WORD_END) start--
    val head = text.substring(start, i).substringBefore('/')
    val scheme = head.endsWith(':') && text.startsWith("//", start + head.length)
    return scheme || head.isEmpty() || ('.' in head && head.any { it.isLetter() })
}

/** The characters around a word of code: blanks, the line feed, the breaking punctuation and quotes. */
private const val WORD_END = " \t\n(,'\""

private const val BREAK_AFTER = "(,"
private const val NO_BREAK_BEFORE = " \t\n)"

/** The break opportunity code text inserts: a zero-width space, which the platform may break at and never draws. */
internal const val RV_CODE_BREAK = '​'

/** What code text inserts where it must not break ([rvCodeJoins]): a word joiner, which has no width either. */
internal const val RV_CODE_JOIN = '\u2060'

/**
 * The offsets of a text of [length] shown with one inserted character ([RV_CODE_BREAK] or [RV_CODE_JOIN]) before each
 * of [breaks] (and any characters after its end, as the source field's last joiner): an original offset at one lands
 * after it.
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

/**
 * [source] as code text shows it: its coloured [spans], its [breaks] and [joins], the [marks] they make (ascending), the
 * [text] with a [RV_CODE_BREAK] or [RV_CODE_JOIN] at each, and the [offsets].
 */
@Immutable
internal class RvCodeShown(val source: String, val spans: List<RvCodeSpan> = rvCodeSpans(source)) {
    val breaks = rvCodeBreaks(source, spans)
    val joins = rvCodeJoins(source, spans)
    val marks: IntArray = (breaks + joins).also { it.sort() }
    val offsets = RvCodeOffsets(marks, source.length)
    val text: String = buildString(source.length + marks.size) {
        var from = 0
        var join = 0
        marks.forEach { at ->
            append(source, from, at)
            val joined = join < joins.size && joins[join] == at
            if (joined) join++
            append(if (joined) RV_CODE_JOIN else RV_CODE_BREAK)
            from = at
        }
        append(source, from, source.length)
    }
}

/** Colours each of [shown]'s spans by its role in [colors], at its place in the shown text. */
internal fun AnnotatedString.Builder.addCode(shown: RvCodeShown, colors: RvCodeColors) {
    // The spans are in text order, so one pass over the marks places them all.
    val breaks = shown.marks
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
    val tagged = modifier.testTag(RvRowPart.CODE)
    if (lines.size == 1) {
        CodeLine(lines.single(), shown, space, colors, tagged, maxLines)
    } else {
        Column(tagged) { lines.forEach { CodeLine(it, shown, space, colors, Modifier, maxLines) } }
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
 * 配色」), in the code's own weight (never bold: owner 2026-11-25). A block and a symbol keep the accent ([keyword], owner
 * 2026-11-25); a call is honk's own word too, in the accent's hue; a literal is that hue a step toward the text; a key the
 * tertiary; an operator the colour halfway between; a comment the secondary ink. Plain text, its punctuation included, is
 * the text.
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
 * Each code kind's ink in [colors]' theme. A block and a symbol take the accent ([RvColors.positive]) and a comment the
 * secondary ink, each or the first step from it toward black (light) or white (dark) that reads at 4.5:1 on the page,
 * the card, and a diff's added and removed lines (their hue at [RvAlpha.STATUS_CHIP] over the card), as [RvColors] makes
 * the latency hues readable. The other kinds tell themselves from the text by tone: each keeps its hue's Oklab hue and
 * chroma at the lightness farthest from the text's that still reads at 4.5:1 on those grounds ([far]): a call the accent,
 * a literal the accent [RvCodeShade.STEP] back toward the text, a key the tertiary ([RvColors.up]'s line), an operator
 * the two [RvCodeShade.MID] apart (in Oklab). Stepping toward black or white, as the old inks did, moved them onto the
 * text's own tone (hl-fix1); `RvCodeTest` measures ΔE*ab against the text and between kinds that meet.
 */
internal fun rvCodeColors(colors: RvColors): RvCodeColors {
    val tints = listOf(colors.good, colors.error).map { it.copy(alpha = RvAlpha.STATUS_CHIP).compositeOver(colors.card) }
    val grounds = listOf(colors.page, colors.card) + tints
    fun reads(c: Color) = grounds.all { rvContrast(c, it) >= RvContrast.TEXT_MIN }
    val toward = if (colors.dark) Color.White else Color.Black
    fun ink(from: Color) = (0..RvContrast.STEPS).map { lerp(from, toward, it.toFloat() / RvContrast.STEPS) }.firstOrNull(::reads) ?: toward

    // The text is dark on a light theme and light on a dark one, so its far side is light, or dark.
    fun far(from: Color, step: Float = 0f): Color {
        val lab = from.convert(ColorSpaces.Oklab)
        fun at(l: Float) = Color(l.coerceIn(0f, 1f), lab.green, lab.blue, 1f, ColorSpaces.Oklab).convert(ColorSpaces.Srgb)
        val levels = (0..RvCodeShade.LEVELS).map { it.toFloat() / RvCodeShade.LEVELS }
        val l = (if (colors.dark) levels else levels.asReversed()).firstOrNull { reads(at(it)) } ?: return ink(from)
        return at(if (colors.dark) l + step else l - step)
    }
    val tertiary = colors.up.line
    return RvCodeColors(
        comment = ink(colors.textSecondary),
        keyword = ink(colors.positive),
        literal = far(colors.positive, RvCodeShade.STEP),
        call = far(colors.positive),
        key = far(tertiary),
        operator = far(lerp(colors.positive, tertiary, RvCodeShade.MID)),
    )
}

/** [rvCodeColors] for the theme in use. */
@Composable
internal fun rememberRvCodeColors(): RvCodeColors {
    val colors = Rv.colors
    return remember(colors) { rvCodeColors(colors) }
}
