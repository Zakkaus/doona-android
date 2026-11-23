package dev.doona.android.prototype.rv

import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.TextUnit

/**
 * One line of text in a role; CJK runs in Noto, the rest in Inter. Cut short, it ends in an ellipsis right after the last
 * word that fits: the platform keeps the space after that word before its ellipsis (`IPLC …`), so the line is laid out
 * once more without it. The shorter text stays while the width does, and the whole text returns when the line gets more
 * room. [maxLines] lets it run to more lines, the last ending in the platform's ellipsis, and [overflow] may put the
 * ellipsis in the middle of a single line instead. Given [autoSize], a line too long for its width shrinks to fit. A text
 * that wraps never leaves one letter alone on its last line: laid out once more with that letter joined to the one
 * before it, it carries both (「當機」, not 「機」).
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
    var joined by remember(text) { mutableStateOf(false) }
    BasicText(
        scripted(cut?.text ?: if (joined) rvJoinLast(text) else text),
        modifier,
        style.copy(color = color, textAlign = align),
        onTextLayout = { layout ->
            // The cut at a word is for one line ending in an ellipsis; any other line is the platform's.
            val kept = cut
            when {
                maxLines != 1 -> if (!joined && rvWidow(layout)) joined = true
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

/** Whether the last of two or more lines of [layout] holds one letter alone (「機」, or 「機。」 with its stop). */
private fun rvWidow(layout: TextLayoutResult): Boolean {
    val last = layout.lineCount - 1
    if (last < 1 || layout.isLineEllipsized(last)) return false
    val line = layout.layoutInput.text.text.substring(layout.getLineStart(last), layout.getLineEnd(last))
    return line.codePoints().filter(Character::isLetterOrDigit).count() == 1L
}

/** [text] with a word joiner before its last letter: no line break can fall between it and the letter before. */
private fun rvJoinLast(text: String): String {
    val last = text.indexOfLast(Char::isLetterOrDigit)
    return if (last > 0) text.substring(0, last) + WORD_JOINER + text.substring(last) else text
}

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
 * [size] when the label fits at it, else the largest step of [TextAutoSize.StepBased] down to half of it that fits. The
 * steps count up from the smallest size, so on their own they stop under a size off their grid (19.55 sp at 1.15×)
 * and move a label that fits.
 */
private class RvFit(private val size: TextUnit) : TextAutoSize {
    private val steps = TextAutoSize.StepBased(size * FIT_MIN, size)

    override fun TextAutoSizeLayoutScope.getFontSize(constraints: Constraints, text: AnnotatedString): TextUnit {
        val whole = performLayout(constraints, text, size)
        val fits = !whole.hasVisualOverflow && !whole.isLineEllipsized(whole.lineCount - 1)
        return if (fits) size else with(steps) { this@getFontSize.getFontSize(constraints, text) }
    }

    override fun equals(other: Any?) = other is RvFit && other.size == size

    override fun hashCode() = size.hashCode()
}

/**
 * Holds a label that shrinks to fit ([rvShrinkToFit]) at the height [text] takes on one line in [style] at its full
 * size, centred in it, so a smaller size moves nothing under the label. Compose scales a line's height with the size.
 */
@Composable
internal fun Modifier.rvFullLine(text: String, style: TextStyle): Modifier {
    val measurer = rememberTextMeasurer()
    val px = remember(text, style, measurer) { measurer.measure(scripted(text), style, maxLines = 1).size.height }
    return height(with(LocalDensity.current) { px.toDp() }).wrapContentHeight()
}

/** The smallest a label shrinks to: half its size, at 2× the font size still the default size. */
private const val FIT_MIN = 0.5f
