package dev.doona.android.prototype.rv.config

import dev.doona.android.prototype.rv.nodes.SEPARATOR
import dev.doona.android.prototype.rv.rvKeepRuns
import dev.doona.android.prototype.rv.rvKeepTail

/**
 * A row's second line from its [parts]: each part's words whole, and the end of the line kept together, so a line that
 * wraps at a large font size breaks between parts or at a space, never inside a word, and never ends on one or two
 * characters ([rvKeepRuns], [rvKeepTail]).
 */
internal fun rvConfigLine(parts: List<String>): String = rvKeepTail(parts.joinToString(SEPARATOR) { rvKeepRuns(it) })
