package dev.doona.android.feature.policies

import dev.doona.android.engine.EngineException
import dev.doona.android.engine.ErrorCode
import dev.doona.android.vpn.honk.HonkConfig
import dev.doona.android.vpn.honk.HonkSource
import dev.doona.android.vpn.honk.HonkSourceAt
import dev.doona.android.vpn.honk.daeCode

/**
 * A statement honk reports (a routing or DNS rule) on its line of its file: the edits the rule pages write, each the
 * file and its new text, which honk checks before it takes it. Each keeps the line's indent.
 */
internal class HonkStatementAt(val source: HonkSource, val line: Int) {
    private val lines = source.content.lines()
    private val indent = lines[line - 1].takeWhile(Char::isWhitespace)

    /** The line as written. */
    val text: String get() = lines[line - 1]

    /** The statement as written, its comment dropped. */
    val code: String get() = daeCode(lines[line - 1])

    /** The file with [text] in place of the statement. */
    fun replaced(text: String): Pair<HonkSource, String> = source to lines.mapIndexed { i, it -> if (i == line - 1) indent + text else it }.joinToString("\n")

    /** The file without the statement's line. */
    fun removed(): Pair<HonkSource, String> = source to lines.filterIndexed { i, _ -> i != line - 1 }.joinToString("\n")

    /** The file with [text] on a line of its own before the statement (a new rule goes before the fallback). */
    fun before(text: String): Pair<HonkSource, String> = before(listOf(text))

    /** The file with each of [texts] on a line of its own before the statement, in order. */
    fun before(texts: List<String>): Pair<HonkSource, String> = source to (lines.take(line - 1) + texts.map { indent + it } + lines.drop(line - 1)).joinToString("\n")

    /** The file with the statement paused: its line kept as a comment, which honk does not load. */
    fun paused(): Pair<HonkSource, String> = replaced("# $code")

    /** The file with this statement and [other] (on another line of the same file) in each other's place. */
    fun swapped(other: HonkStatementAt): Pair<HonkSource, String> {
        if (other.source.id != source.id) throw EngineException(ErrorCode.InvalidArgument)
        return source to lines.mapIndexed { i, it ->
            when (i) {
                line - 1 -> indent + other.code
                other.line - 1 -> other.indent + code
                else -> it
            }
        }.joinToString("\n")
    }
}

/**
 * Where [at] says honk read [expression] in [config]. The line must still hold it (spaces aside), else the file changed
 * since honk loaded it: [ErrorCode.Busy]. A null [expression] takes the line as it is.
 */
internal fun statementAt(config: HonkConfig, at: HonkSourceAt?, expression: String?): HonkStatementAt {
    at ?: throw EngineException(ErrorCode.InvalidArgument)
    val source = config.sources.firstOrNull { it.id == at.sourceId } ?: throw EngineException(ErrorCode.Busy)
    if (at.line !in 1..source.content.lines().size) throw EngineException(ErrorCode.Busy)
    val statement = HonkStatementAt(source, at.line)
    if (expression != null && statement.code.filterNot(Char::isWhitespace) != expression.filterNot(Char::isWhitespace)) throw EngineException(ErrorCode.Busy)
    return statement
}

/**
 * The lines a rule written over several lines spans, from [at] on: (line number, text) while the code of those lines,
 * spaces aside, makes up [expression]. Null for a rule on one line, or when the lines no longer hold it.
 */
internal fun statementLines(config: HonkConfig, at: HonkSourceAt?, expression: String): List<Pair<Int, String>>? {
    at ?: return null
    val lines = config.sources.firstOrNull { it.id == at.sourceId }?.content?.lines() ?: return null
    val want = expression.filterNot(Char::isWhitespace)
    var got = ""
    for (i in at.line - 1 until lines.size) {
        got += daeCode(lines[i]).filterNot(Char::isWhitespace)
        if (!want.startsWith(got)) return null
        if (got == want) return if (i == at.line - 1) null else (at.line - 1..i).map { it + 1 to lines[it].trimEnd() }
    }
    return null
}
