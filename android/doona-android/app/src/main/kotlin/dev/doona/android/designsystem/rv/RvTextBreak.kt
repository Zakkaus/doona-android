package dev.doona.android.designsystem.rv

import android.icu.text.BreakIterator
import androidx.compose.ui.text.TextLayoutResult
import kotlin.math.ceil

// RvText's value line breaks: where a wrapped text may break, and what its last line keeps, as the RvWrap steps take them.

/** A wrapped line ends on fewer letters than this only where nothing better fits. */
internal const val LAST_LINE_LETTERS = 3

internal const val META_SEPARATOR = " · "

/** The letters and digits on the last of [layout]'s lines; none counted when it ends in an ellipsis. */
internal fun rvLastLetters(layout: TextLayoutResult): Int {
    val last = layout.lineCount - 1
    if (layout.isLineEllipsized(last)) return Int.MAX_VALUE
    val line = layout.layoutInput.text.text.substring(layout.getLineStart(last), layout.getLineEnd(last))
    return line.codePoints().filter(Character::isLetterOrDigit).count().toInt()
}

/** Whether a line of [layout] ends inside a word: a Latin letter, digit or sign on both sides of the break, no space. */
internal fun rvTorn(layout: TextLayoutResult): Boolean {
    val text = layout.layoutInput.text.text
    return (0 until layout.lineCount - 1).any { line ->
        val end = layout.getLineEnd(line)
        val before = text.substring(0, end).lastOrNull { it != WORD_JOINER_CHAR }
        val after = text.substring(end).firstOrNull { it != WORD_JOINER_CHAR }
        before != null && after != null && before.inWord() && after.inWord()
    }
}

private fun Char.inWord() = !isWhitespace() && this != ZERO_WIDTH_SPACE && !rvCjk()

/** Whether a line of [layout] but the last ends between two letters or digits (`…androi` over `d`). */
internal fun rvTornInWord(layout: TextLayoutResult): Boolean {
    val text = layout.layoutInput.text.text
    return (0 until layout.lineCount - 1).any { line ->
        val end = layout.getLineEnd(line)
        val before = text.substring(0, end).lastOrNull { it != WORD_JOINER_CHAR && it != ZERO_WIDTH_SPACE }
        val after = text.substring(end).firstOrNull { it != WORD_JOINER_CHAR && it != ZERO_WIDTH_SPACE }
        before != null && after != null && before.isLetterOrDigit() && after.isLetterOrDigit() && !before.rvCjk() && !after.rvCjk()
    }
}

/** [text] with a break allowed after a hyphen between two letters or digits. */
internal fun rvHyphenBreaks(text: String): String = buildString {
    text.forEachIndexed { i, c ->
        append(c)
        if (c == '-' && text.getOrNull(i - 1)?.isLetterOrDigit() == true && text.getOrNull(i + 1)?.isLetterOrDigit() == true) append(ZERO_WIDTH_SPACE)
    }
}

/** [text] with a break allowed after a dot between letters and before a colon that follows a letter and leads a number. */
internal fun rvSoftBreaks(text: String): String = buildString {
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
internal fun rvJoinWord(text: String, start: Int): String {
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
internal fun rvLastLineWidth(layout: TextLayoutResult): Int {
    val last = layout.lineCount - 1
    return ceil(layout.getLineRight(last) - layout.getLineLeft(last)).toInt()
}
