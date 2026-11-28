package dev.doona.android.designsystem.rv

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.doona.android.R

/**
 * A row's second line from its [parts]: each part's words whole, and the end of the line kept together, so a line that
 * wraps at a large font size breaks between parts or at a space, never inside a word, and never ends on one or two
 * characters ([rvKeepRuns], [rvKeepTail]).
 */
@Composable
internal fun rvConfigLine(parts: List<String>): String = rvKeepTail(parts.joinToString(stringResource(R.string.rv_comma)) { rvKeepRuns(it) })

/**
 * [rvConfigLine] for parts that are phrases rather than labels (a template's one line, a note): each part's words
 * kept whole ([rvKeepWords]) rather than its whole run, which would break anywhere once it is wider than the line.
 */
@Composable
internal fun rvConfigPhrase(parts: List<String>): String = rvKeepTail(parts.joinToString(stringResource(R.string.rv_comma)) { rvKeepWords(it) })
