package dev.doona.android.designsystem.rv

/**
 * Line-break control for the text of a row or a banner that wraps in a column of a few characters (a large font size),
 * where the platform breaks between any two CJK characters: it splits a word (「目」 over 「前」) and leaves two characters
 * alone on the last line. [RvText] already keeps one letter off the last line and a wrapped text's words whole
 * ([dev.doona.android.designsystem.rv.rvKeepWords]); these two keep what the caller names together, a whole run or a
 * tail, with a word joiner (U+2060, no break before or after it), which no script draws and a screen reader skips.
 */
private const val WORD_JOINER = '⁠'

/** How many letters of a text's tail [rvKeepTail] keeps together: the shortest last line the library allows. */
private const val TAIL_LETTERS = 3

/**
 * [text] with each run of Han, kana or Hangul characters kept whole, and a number kept with the unit that follows it
 * ("36 行", "3 項已略過") and the 第 before it ("第 9"): a line then breaks at another space or after a mark, never inside a word or between a count
 * and what it counts. A run wider than the line still breaks inside, as the platform's does, so pass a phrase or a
 * segment of a longer text, not a sentence that is one run.
 */
internal fun rvKeepRuns(text: String): String = buildString(text.length + text.length / 2) {
    var before = -1
    var i = 0
    while (i < text.length) {
        val point = text.codePointAt(i)
        val next = if (i + 1 < text.length) text.codePointAt(i + 1) else -1
        when {
            rvCjkLetter(before) && rvCjkLetter(point) -> append(WORD_JOINER).appendCodePoint(point)
            point == SPACE && joinsAcrossSpace(before, next) -> append(NO_BREAK_SPACE)
            else -> appendCodePoint(point)
        }
        before = point
        i += Character.charCount(point)
    }
}

/**
 * [text] with its last [letters] letters or digits kept together, spaces and marks between them included, so a wrapped
 * text ends on a line of at least that many ("範圍外", not 「圍外」 alone; "12 行", not 「行」). A line may still break
 * before the first of them. A text with fewer letters keeps all of it together.
 */
internal fun rvKeepTail(text: String, letters: Int = TAIL_LETTERS): String {
    var from = text.length
    var seen = 0
    while (from > 0 && seen < letters) {
        val point = text.codePointBefore(from)
        from -= Character.charCount(point)
        if (Character.isLetterOrDigit(point)) seen++
    }
    return text.substring(0, from) + unbreakable(text.substring(from))
}

/** [tail] with its spaces no-break spaces, and a word joiner between neighbours where either is not ASCII (a break could fall there). */
private fun unbreakable(tail: String): String = buildString(tail.length * 2) {
    var before = -1
    tail.codePoints().forEach { point ->
        if (point == SPACE) {
            append(NO_BREAK_SPACE)
        } else {
            val glue = before >= 0 && before != SPACE && before != WORD_JOINER.code && (before > ASCII || point > ASCII)
            if (glue && point != WORD_JOINER.code) append(WORD_JOINER)
            appendCodePoint(point)
        }
        before = point
    }
}

private const val SPACE = ' '.code
private const val ORDINAL = '第'.code
private const val ASCII = 0x7f
private const val NO_BREAK_SPACE = '\u00a0'

/** Whether the space between [before] and [next] is inside a count: a number and its unit, or 第 and its number. */
private fun joinsAcrossSpace(before: Int, next: Int): Boolean {
    val unit = Character.isDigit(before) && rvCjkLetter(next)
    val ordinal = before == ORDINAL && Character.isDigit(next)
    return unit || ordinal
}

/** Separator between the fields of a summary. */
internal const val SEPARATOR = " / "
