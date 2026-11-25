package dev.doona.android.prototype.rv.config

import dev.doona.android.designsystem.rv.rvKeepWords
import dev.doona.android.prototype.rv.nodes.SEPARATOR
import dev.doona.android.prototype.rv.rvKeepRuns
import dev.doona.android.prototype.rv.rvKeepTail

/**
 * A row's second line from its [parts]: each part's words whole, and the end of the line kept together, so a line that
 * wraps at a large font size breaks between parts or at a space, never inside a word, and never ends on one or two
 * characters ([rvKeepRuns], [rvKeepTail]). The separator's dot stays with the part before it, so a wrapped line never
 * starts with it.
 */
internal fun rvConfigLine(parts: List<String>): String = rvKeepTail(parts.joinToString(KEPT_SEPARATOR) { rvKeepRuns(it) })

/**
 * [rvConfigLine] for parts that are phrases rather than labels (a template's one line, a note): each part's words
 * kept whole ([rvKeepWords]) rather than its whole run, which would break anywhere once it is wider than the line.
 */
internal fun rvConfigPhrase(parts: List<String>): String = rvKeepTail(parts.joinToString(KEPT_SEPARATOR) { rvKeepWords(it) })

/**
 * [SEPARATOR] with a word joiner (U+2060: no break on either side, no width) between its first space and its dot,
 * so a line breaks only after the dot. A no-break space would do the same, but after a CJK part it is set in Inter
 * where a space is set in Noto, and it would widen the gap.
 */
private val KEPT_SEPARATOR = SEPARATOR.replaceFirst(" ", " \u2060")
