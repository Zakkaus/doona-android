package dev.doona.android.prototype.rv

import android.icu.text.BreakIterator
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.text.modifiers.TextAutoSizeLayoutScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.toSize
import kotlin.math.ceil

/**
 * One line of text in a role; CJK runs in Noto, the rest in Inter. Cut short, it ends in an ellipsis right after the last
 * word that fits: the platform keeps the space after that word before its ellipsis (`IPLC …`), so the line is laid out
 * once more without it. The shorter text stays while the width does, and the whole text returns when the line gets more
 * room. [maxLines] lets it run to more lines, the last ending in the platform's ellipsis, and [overflow] may put the
 * ellipsis in the middle of a single line instead. Given [autoSize], a line too long for its width shrinks to fit. A text
 * that wraps is laid out again until it reads well ([RvWrap]): a meta line breaks only between its parts, a value
 * breaks at its dots and its port's colon rather than inside a word, and the last line keeps a word, not one or two
 * letters (「當機」, not 「機」; 「不經代理」, not 「代理」).
 */
@Composable
internal fun RvText(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = Rv.colors.text,
    align: TextAlign = TextAlign.Start,
    maxLines: Int = 1,
    overflow: TextOverflow = TextOverflow.Ellipsis,
    autoSize: TextAutoSize? = null,
) {
    var cut by remember(text) { mutableStateOf<RvCut?>(null) }
    var wrap by remember(text) { mutableStateOf(RvWrap()) }
    BasicText(
        scripted(cut?.text ?: wrap.text(text)),
        if (wrap.narrower <= 0) modifier else modifier.padding(end = with(LocalDensity.current) { wrap.narrower.toDp() }),
        style.copy(color = color, textAlign = align),
        onTextLayout = { layout ->
            // The cut at a word is for one line ending in an ellipsis; any other line is the platform's.
            val kept = cut
            when {
                maxLines != 1 -> wrap.next(text, layout)?.let { wrap = it }
                overflow != TextOverflow.Ellipsis || autoSize != null -> Unit
                kept == null -> cut = rvCut(text, layout)
                layout.layoutInput.constraints.maxWidth > kept.width -> cut = null
            }
        },
        overflow = overflow,
        maxLines = maxLines,
        autoSize = autoSize,
    )
}

/** A line cut at a word for [width] px. */
private class RvCut(val width: Int, val text: String)

/** [text] up to the last word that fits and an ellipsis, when the platform's cut of [layout] left a space before its own. */
private fun rvCut(text: String, layout: TextLayoutResult): RvCut? {
    if (!layout.isLineEllipsized(0)) return null
    val end = layout.getLineEnd(0, visibleEnd = true).coerceAtMost(text.length)
    val kept = text.substring(0, end).trimEnd()
    val atSpace = kept.length < end || text.getOrNull(end)?.isWhitespace() == true
    return if (atSpace && kept.isNotEmpty()) RvCut(layout.layoutInput.constraints.maxWidth, kept + ELLIPSIS) else null
}

private const val ELLIPSIS = "\u2026"

/**
 * How a wrapped text is laid out again so it reads well, one step a pass, each step taken once:
 * - [bound]: a meta line, parts joined by ` · `, breaks only after a separator and keeps each part whole (`61.7 / 200 GB ·`
 *   over `3/15 到期`, not `61.7 /` over `200 GB`), unless a part is longer than its line ([unbound]);
 * - [soft]: a value broken inside a word (a host:port, an app's package) may break after a dot between letters and before
 *   a port's colon instead (`gemini.google.com` over `:443`, not `…com:4` over `43`);
 * - [joined]: one letter alone on the last line is joined to the letter before (「當機」, not 「機」);
 * - [word]: a last line of fewer than [LAST_LINE_LETTERS] letters takes the whole word that ended the line before, joined
 *   to it ([word] is where the last line started; 「發生當機」, not 「當機」; 「不經代理」, not 「代理」); -2 once dropped;
 * - [narrower]: failing that, the text is laid out once more narrower by the last line's width, so the line before gives
 *   it what it can; -1 once dropped.
 *
 * A [word] or [narrower] step stays only where it adds no line, cuts nothing and breaks no word ([lines] is the count
 * before it).
 */
private data class RvWrap(
    val bound: Boolean = false,
    val unbound: Boolean = false,
    val soft: Boolean = false,
    val joined: Boolean = false,
    val word: Int = -1,
    val narrower: Int = 0,
    val lines: Int = 0,
) {
    fun text(text: String): String {
        var shown = if (bound) rvBind(text) else text
        if (soft) shown = rvSoftBreaks(shown)
        if (joined) shown = rvJoinLast(shown)
        return if (word >= 0) rvJoinWord(shown, word) else shown
    }

    /** The next step for [layout], the layout of [text] after the steps so far, or null when none is left to take. */
    fun next(text: String, layout: TextLayoutResult): RvWrap? {
        val last = layout.lineCount - 1
        val torn = rvTorn(layout)
        val worse = last + 1 > lines || torn || layout.isLineEllipsized(last) || layout.hasVisualOverflow
        return when {
            narrower > 0 -> if (worse) copy(narrower = -1) else null

            word >= 0 && worse -> copy(word = -2)

            last < 1 -> null

            bound && torn -> copy(bound = false, unbound = true)

            !bound && !unbound && META_SEPARATOR in text -> copy(bound = true)

            torn && !soft && rvSoftBreaks(text) != text -> copy(soft = true)

            narrower < 0 -> null

            else -> when (rvLastLetters(layout)) {
                1 -> if (joined) shorten(layout) else copy(joined = true)
                in 2 until LAST_LINE_LETTERS -> shorten(layout)
                else -> null
            }
        }
    }

    /** The last line is short: bring the word before it down, else narrow the text once. */
    private fun shorten(layout: TextLayoutResult): RvWrap? {
        val shown = layout.layoutInput.text.text
        val start = layout.getLineStart(layout.lineCount - 1)
        return when {
            word == -1 && rvJoinWord(shown, start) != shown -> copy(word = start, lines = layout.lineCount)
            narrower == 0 -> copy(narrower = rvLastLineWidth(layout), lines = layout.lineCount)
            else -> null
        }
    }
}

/** [text] in [style] laid out [width] px wide in at most [maxLines] lines as [RvText] lays it out, its [RvWrap] steps taken. */
internal fun TextMeasurer.rvLayout(text: String, style: TextStyle, maxLines: Int, width: Int): TextLayoutResult {
    var wrap = RvWrap()
    var layout = rvMeasure(wrap, text, style, maxLines, width)
    repeat(WRAP_STEPS) {
        wrap = wrap.next(text, layout) ?: return layout
        layout = rvMeasure(wrap, text, style, maxLines, width)
    }
    return layout
}

private fun TextMeasurer.rvMeasure(wrap: RvWrap, text: String, style: TextStyle, maxLines: Int, width: Int) =
    measure(scripted(wrap.text(text)), style, TextOverflow.Ellipsis, maxLines = maxLines, constraints = Constraints(maxWidth = (width - wrap.narrower.coerceAtLeast(0)).coerceAtLeast(0)))

/** Whether [layout] reads well: whole (no ellipsis, nothing past its box), no word broken, no last line of one or two letters. */
internal fun rvReadsWell(layout: TextLayoutResult): Boolean =
    !layout.hasVisualOverflow && (0 until layout.lineCount).none(layout::isLineEllipsized) && !rvTorn(layout) && (layout.lineCount < 2 || rvLastLetters(layout) >= LAST_LINE_LETTERS)

/** More steps than [RvWrap] can take, which ends each step after one try. */
private const val WRAP_STEPS = 6

/** A wrapped line ends on fewer letters than this only where nothing better fits. */
private const val LAST_LINE_LETTERS = 3

private const val META_SEPARATOR = " · "

/** The letters and digits on the last of [layout]'s lines; none counted when it ends in an ellipsis. */
private fun rvLastLetters(layout: TextLayoutResult): Int {
    val last = layout.lineCount - 1
    if (layout.isLineEllipsized(last)) return Int.MAX_VALUE
    val line = layout.layoutInput.text.text.substring(layout.getLineStart(last), layout.getLineEnd(last))
    return line.codePoints().filter(Character::isLetterOrDigit).count().toInt()
}

/** Whether a line of [layout] ends inside a word: a Latin letter, digit or sign on both sides of the break, no space. */
private fun rvTorn(layout: TextLayoutResult): Boolean {
    val text = layout.layoutInput.text.text
    return (0 until layout.lineCount - 1).any { line ->
        val end = layout.getLineEnd(line)
        val before = text.substring(0, end).lastOrNull { it != WORD_JOINER_CHAR }
        val after = text.substring(end).firstOrNull { it != WORD_JOINER_CHAR }
        before != null && after != null && before.inWord() && after.inWord()
    }
}

private fun Char.inWord() = !isWhitespace() && this != ZERO_WIDTH_SPACE && !rvCjk()

/** [text] with no break inside a meta line's parts or before a separator: a word joiner after each space but the separators'. */
private fun rvBind(text: String): String = text.split(META_SEPARATOR).joinToString(" $WORD_JOINER· ") { it.replace(" ", " $WORD_JOINER") }

/** [text] with a break allowed after a dot between letters and before a colon that follows a letter and leads a number. */
private fun rvSoftBreaks(text: String): String = buildString {
    text.forEachIndexed { i, c ->
        val before = text.getOrNull(i - 1)
        val after = text.getOrNull(i + 1)
        if (c == ':' && before?.isLetter() == true && after?.isDigit() == true) append(ZERO_WIDTH_SPACE)
        append(c)
        if (c == '.' && before?.isLetter() == true && after?.isLetter() == true) append(ZERO_WIDTH_SPACE)
    }
}

/**
 * [text] with word joiners through the word that ends before [start] (the last line's start, spaces skipped) and on to
 * [start], so no break falls inside that word or between it and the last line; [text] as it is when that word starts it.
 * Words are ICU's, a dictionary's for CJK.
 */
private fun rvJoinWord(text: String, start: Int): String {
    var end = start
    while (end > 0 && (text[end - 1].isWhitespace() || text[end - 1] == WORD_JOINER_CHAR)) end--
    if (end == 0) return text
    val words = BreakIterator.getWordInstance()
    words.setText(text)
    val from = words.preceding(end)
    if (from <= 0) return text
    return buildString {
        text.forEachIndexed { i, c ->
            if (i in from + 1..start && text[i - 1] != WORD_JOINER_CHAR && c != WORD_JOINER_CHAR) append(WORD_JOINER_CHAR)
            append(c)
        }
    }
}

/**
 * The width of the last line of [layout], in whole px: a line that much narrower cannot keep the word that ended the
 * line before, since the last line did not fit after it.
 */
private fun rvLastLineWidth(layout: TextLayoutResult): Int {
    val last = layout.lineCount - 1
    return ceil(layout.getLineRight(last) - layout.getLineLeft(last)).toInt()
}

/** [text] with a word joiner before its last letter: no line break can fall between it and the letter before. */
private fun rvJoinLast(text: String): String {
    val last = text.indexOfLast(Char::isLetterOrDigit)
    return if (last > 0) text.substring(0, last) + WORD_JOINER + text.substring(last) else text
}

private const val ZERO_WIDTH_SPACE = '\u200B'
private const val WORD_JOINER_CHAR = '\u2060'
private const val WORD_JOINER = "\u2060"

/**
 * A label that keeps one line in a fixed column (a nav tab's, a round action's): set in [style], or smaller when that
 * does not fit the column, down to half its size; whole, never cut with an ellipsis.
 */
@Composable
internal fun rvShrinkToFit(style: TextStyle): TextAutoSize {
    val size = style.fontSize
    return remember(size) { RvFit(size) }
}

/**
 * A figure kept to one line in its width (a tile's rate): [style]'s size when it and each of [widest] fit at it, else
 * the largest step of [rvShrinkToFit]'s range at which all of them do. [widest] are the widest figures the place shows
 * (each set as the figure is), so a figure that changes every second keeps its size, and figures side by side, fitting
 * the same widest in the same width, take one size.
 */
@Composable
internal fun rvFigureFit(style: TextStyle, widest: List<AnnotatedString>): TextAutoSize {
    val size = style.fontSize
    return remember(size, widest) { RvFit(size, widest) }
}

/**
 * [size] when the label (and each of [widest]) fits at it, else the largest step down to half of it at which all do.
 * Alone, the label takes [TextAutoSize.StepBased]'s step: those count up from the smallest size, so on their own they
 * stop under a size off their grid (19.55 sp at 1.15×) and move a label that fits.
 */
private class RvFit(private val size: TextUnit, private val widest: List<AnnotatedString> = emptyList()) : TextAutoSize {
    private val steps = TextAutoSize.StepBased(size * FIT_MIN, size)

    override fun TextAutoSizeLayoutScope.getFontSize(constraints: Constraints, text: AnnotatedString): TextUnit {
        val fits = { at: TextUnit -> (widest + text).all { rvWhole(performLayout(constraints, it, at)) } }
        val full = size.value
        return when {
            fits(size) -> size
            widest.isEmpty() -> with(steps) { this@getFontSize.getFontSize(constraints, text) }
            else -> rvSteps(full).firstOrNull { it < full && fits(size * (it / full)) }?.let { size * (it / full) } ?: size * FIT_MIN
        }
    }

    override fun equals(other: Any?) = other is RvFit && other.size == size && other.widest == widest

    override fun hashCode() = 31 * size.hashCode() + widest.hashCode()
}

/** Whether [layout] shows its text whole: nothing past its box, no line ending in an ellipsis. */
private fun rvWhole(layout: TextLayoutResult) = !layout.hasVisualOverflow && !layout.isLineEllipsized(layout.lineCount - 1)

/** The sizes a fit tries for a [full] size, largest first: [FIT_STEP] apart, down to [FIT_MIN] of it. */
private fun rvSteps(full: Float): List<Float> {
    val min = full * FIT_MIN
    return (((full - min) / FIT_STEP).toInt() downTo 0).map { min + it * FIT_STEP }
}

/**
 * Holds a label that shrinks to fit ([rvShrinkToFit]) at the height [text] takes on one line in [style] at its full
 * size, centred in it, so a smaller size moves nothing under the label. Compose scales a line's height with the size.
 */
@Composable
internal fun Modifier.rvFullLine(text: String, style: TextStyle): Modifier = height(rvOneLine(text, style)).wrapContentHeight()

/**
 * The height [text] takes on one line in [style] at this font scale. A line in sp does not grow with the font scale as
 * the font does, so at a large size this is the font's own height, past the style's line.
 */
@Composable
internal fun rvOneLine(text: String, style: TextStyle): Dp {
    val measurer = rememberTextMeasurer()
    val px = remember(text, style, measurer) { measurer.measure(scripted(text), style, maxLines = 1).size.height }
    return with(LocalDensity.current) { px.toDp() }
}

/** The smallest a label shrinks to: half its size, at 2× the font size still the default size. */
private const val FIT_MIN = 0.5f

/**
 * The one size a row of [labels] takes (a nav's tabs, a row of round actions), each kept to one line in a column [width]
 * wide: [style] when every label fits at it, else the largest step of [rvShrinkToFit]'s range at which all of them fit,
 * its line height scaled with it as Compose scales an auto size's. Each label set in it fits, so no label of the row
 * shrinks alone.
 */
@Composable
internal fun rvRowFit(labels: List<String>, style: TextStyle, width: Dp): TextStyle {
    val measurer = rememberTextMeasurer()
    val px = with(LocalDensity.current) { width.toPx().toInt() }
    return remember(labels, style, px, measurer) { rvLargestFit(style) { at -> labels.all { rvFitsOneLine(measurer, it, at, px) } } }
}

/**
 * The one size a row of chips takes (a range or an order row), each chip as wide as its label and [pad] on both sides,
 * [gap] apart, the row [width] wide: [style] when the whole row fits at it, else the largest step of [rvShrinkToFit]'s
 * range at which it does, its line height scaled with it. Before the row is measured ([width] 0) it is [style].
 */
@Composable
internal fun rvChipRowFit(labels: List<String>, style: TextStyle, pad: Dp, gap: Dp, width: Dp): TextStyle {
    val measurer = rememberTextMeasurer()
    val room = with(LocalDensity.current) { (width - pad * 2 * labels.size - gap * (labels.size - 1)).toPx() }
    return remember(labels, style, room, measurer) {
        if (width.value == 0f) style else rvLargestFit(style) { at -> labels.sumOf { measurer.measure(scripted(it), at, maxLines = 1).size.width } <= room }
    }
}

/**
 * [style] when it [fits], else the largest step of [FIT_STEP] down to [FIT_MIN] of its size that does, its line height
 * scaled as Compose scales an auto size's; the smallest when none does. Sizes are shares of the full one, so each keeps
 * the style's unit.
 */
private fun rvLargestFit(style: TextStyle, fits: (TextStyle) -> Boolean): TextStyle {
    val full = style.fontSize.value
    val at = { size: Float -> style.copy(fontSize = style.fontSize * (size / full), lineHeight = style.lineHeight * (size / full)) }
    if (fits(style)) return style
    return at(rvSteps(full).firstOrNull { it < full && fits(at(it)) } ?: full * FIT_MIN)
}

/** Whether [text] in [style] fits on one line [width] px wide, whole: no ellipsis, nothing past the edge. */
private fun rvFitsOneLine(measurer: TextMeasurer, text: String, style: TextStyle, width: Int): Boolean {
    val layout = measurer.measure(scripted(text), style, TextOverflow.Ellipsis, maxLines = 1, constraints = Constraints(maxWidth = width))
    return !layout.hasVisualOverflow && !layout.isLineEllipsized(0)
}

/** [TextAutoSize.StepBased]'s default step, so a row's size is one a label shrinking alone could have taken. */
private const val FIT_STEP = 0.25f

/**
 * Moves [text] in [style] to the start by the blank its first glyph keeps before its ink (the side bearing), so the ink
 * starts on the pixel where the text's box starts: on the edge of a control above it, not a pixel or two inside.
 */
@Composable
internal fun Modifier.rvInkStart(text: String, style: TextStyle): Modifier {
    val measurer = rememberTextMeasurer()
    val glyph = if (text.isEmpty()) text else text.substring(0, Character.charCount(text.codePointAt(0)))
    val px = remember(glyph, style, measurer) { if (glyph.isEmpty()) 0 else rvInkLeft(measurer.measure(scripted(glyph), style, maxLines = 1)) }
    return offset { IntOffset(-px, 0) }
}

/** The first pixel column of [layout] with ink in it, the layout drawn alone into a bitmap. */
private fun rvInkLeft(layout: TextLayoutResult): Int {
    val size = layout.size
    if (size.width == 0 || size.height == 0) return 0
    val bitmap = ImageBitmap(size.width, size.height)
    CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(bitmap), size.toSize()) { drawText(layout, Color.Black) }
    val ink = bitmap.toPixelMap()
    return (0 until ink.width).firstOrNull { x -> (0 until ink.height).any { y -> ink[x, y].alpha >= INK_COVER } } ?: 0
}

/** A pixel is ink from a quarter cover on: the faint edge of antialiasing does not read as where a glyph starts. */
private const val INK_COVER = 0.25f
