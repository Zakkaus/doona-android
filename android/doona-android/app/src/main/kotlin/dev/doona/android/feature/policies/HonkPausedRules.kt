package dev.doona.android.feature.policies

import dev.doona.android.engine.EngineException
import dev.doona.android.engine.ErrorCode
import dev.doona.android.honk.HonkConfig
import dev.doona.android.honk.HonkRule
import dev.doona.android.honk.HonkSourceAt
import dev.doona.android.honk.daeCode

/**
 * A rule paused on 全部規則: its line kept as the comment `# condition -> outbound` in a top-level `routing` block,
 * which honk does not load, so its `GET /rules` does not list it (owner, q-rule-paused 2026-11-26).
 */
internal class HonkPausedRule(val source: HonkSourceAt, val expression: String) {
    val outbound: String get() = topLevel(expression, "->").last()
}

/** One row of 全部規則: a rule honk runs, or one paused in its file. */
internal sealed interface HonkRuleEntry {
    val at: HonkSourceAt?

    class Loaded(val rule: HonkRule) : HonkRuleEntry {
        override val at: HonkSourceAt? get() = rule.source
    }

    class Paused(val rule: HonkPausedRule) : HonkRuleEntry {
        override val at: HonkSourceAt get() = rule.source
    }
}

/**
 * Every paused rule in [config]'s files, file by file in line order: a comment line directly inside a top-level
 * `routing { }` whose text reads as a rule (a matcher call, an arrow, an outbound name). Plain comments stay out.
 */
internal fun pausedRules(config: HonkConfig): List<HonkPausedRule> = config.files.flatMap { source ->
    val found = mutableListOf<HonkPausedRule>()
    var depth = 0
    var routing = false
    source.content.lines().forEachIndexed { i, line ->
        val code = daeCode(line)
        if (routing && depth == 1 && code.isEmpty()) {
            pausedText(line)?.let { found += HonkPausedRule(HonkSourceAt(source.path, source.id, i + 1, null), it) }
        }
        if (depth == 0 && ROUTING_OPEN.matches(code)) routing = true
        depth += braces(code)
        if (depth <= 0) {
            depth = 0
            routing = false
        }
    }
    found
}

/**
 * [rules] (honk's order, the fallback last) with [paused] put back where they are written: before the first rule of
 * the same file below it, else after the last one above it, else before the fallback.
 */
internal fun withPaused(rules: List<HonkRule>, paused: List<HonkPausedRule>): List<HonkRuleEntry> {
    val entries = rules.map<HonkRule, HonkRuleEntry> { HonkRuleEntry.Loaded(it) }.toMutableList()
    fun isFallback(entry: HonkRuleEntry) = entry is HonkRuleEntry.Loaded && entry.rule.isFallback
    paused.forEach { rule ->
        val same = entries.indices.filter { !isFallback(entries[it]) && entries[it].at?.sourceId == rule.source.sourceId }
        val below = same.firstOrNull { entries[it].at!!.line > rule.source.line }
        val above = same.lastOrNull { entries[it].at!!.line < rule.source.line }
        val fallback = entries.indexOfFirst(::isFallback).takeIf { it >= 0 } ?: entries.size
        entries.add(below ?: above?.plus(1) ?: fallback, HonkRuleEntry.Paused(rule))
    }
    return entries
}

/** Where [rule] is paused in [config]; the line must still be its comment, else the file changed: [ErrorCode.Busy]. */
internal fun pausedAt(config: HonkConfig, rule: HonkPausedRule): HonkStatementAt {
    val statement = statementAt(config, rule.source, null)
    if (pausedText(statement.text) != rule.expression) throw EngineException(ErrorCode.Busy)
    return statement
}

/** The rule text of a comment line `# condition -> outbound`, or null when the comment is not a rule. */
private fun pausedText(line: String): String? {
    val text = line.trim().takeIf { it.startsWith("#") }?.removePrefix("#")?.trim() ?: return null
    val parts = topLevel(text, "->")
    return text.takeIf { parts.size == 2 && CONDITION.matches(parts[0]) && OUTBOUND.matches(parts[1]) }
}

/** How much [code] opens braces, outside quotes. */
private fun braces(code: String): Int {
    var quote: Char? = null
    var count = 0
    code.forEach {
        when {
            quote != null -> if (it == quote) quote = null
            it == '\'' || it == '"' -> quote = it
            it == '{' -> count++
            it == '}' -> count--
        }
    }
    return count
}

private val ROUTING_OPEN = Regex("""routing\s*\{""")
private val CONDITION = Regex("""!?[a-z_][a-z0-9_]*\s*\(.*\).*""", RegexOption.DOT_MATCHES_ALL)
private val OUTBOUND = Regex("""[A-Za-z0-9_-]+""")
