package dev.doona.android.feature.policies

import dev.doona.android.feature.model.RvMatchKind
import dev.doona.android.feature.model.RvPolicyGroup
import dev.doona.android.feature.model.RvPolicyRule
import dev.doona.android.feature.model.RvRuleEnd
import dev.doona.android.feature.model.RvRuleMatch
import dev.doona.android.vpn.HonkRule

/**
 * A rule of honk's `GET /rules` as the pages list it. [groups] holds the profile's groups by name, which is how a rule
 * names its outbound; one it names that is not there is drawn as missing, not as 直連. honk has no per-rule byte count,
 * so every rule shows none.
 */
internal fun rvPolicyRule(rule: HonkRule, groups: Map<String, RvPolicyGroup>): RvPolicyRule {
    val match = if (rule.isFallback) RvRuleMatch.Rest else rvRuleMatch(ruleCondition(rule.expression))
    return when (rule.outbound) {
        DIRECT -> RvPolicyRule(match, null, 0L)
        BLOCK -> RvPolicyRule(match, null, 0L, end = RvRuleEnd.Reject)
        else -> groups[rule.outbound]?.let { RvPolicyRule(match, it, 0L) } ?: RvPolicyRule(match, null, 0L, end = RvRuleEnd.Missing)
    }
}

/**
 * What one condition matches, as the pages say it: a single `domain(...)` or `dip(...)` matcher in the words the rule
 * page offers, and anything else (a conjunction, a negation, another matcher) as its text. A `domain(...)` of several
 * values is one rule, named by its first value and the count.
 */
internal fun rvRuleMatch(condition: String): RvRuleMatch {
    val matcher = MATCHER.matchEntire(condition)?.takeIf { topLevel(condition, AND).size == 1 }
        ?: return RvRuleMatch.Value(RvMatchKind.Full, condition)
    val args = topLevel(matcher.groupValues[2], ",").map(::argument)
    val single = args.singleOrNull()
    return when (matcher.groupValues[1]) {
        "domain" -> when {
            single == null -> RvRuleMatch.Domains(args.first().value, args.size)
            single.key == "geosite" -> RvRuleMatch.GeoSite(single.value)
            else -> RvRuleMatch.Value(DOMAIN_KINDS[single.key] ?: RvMatchKind.Full, single.value)
        }

        "dip" -> when {
            single == null -> RvRuleMatch.Value(RvMatchKind.Full, condition)
            single.key == "geoip" && single.value.equals(PRIVATE, ignoreCase = true) -> RvRuleMatch.Lan
            single.key == "geoip" -> RvRuleMatch.GeoIp(single.value.uppercase())
            else -> RvRuleMatch.Value(RvMatchKind.Cidr, single.value)
        }

        else -> RvRuleMatch.Value(RvMatchKind.Full, condition)
    }
}

/** The condition of honk's rule text `condition -> outbound`: what comes before its first arrow outside quotes. */
internal fun ruleCondition(expression: String): String = topLevel(expression, "->").first()

/** One matcher argument, `key: value` or a bare value, its quotes dropped. */
private class Argument(val key: String?, val value: String)

private fun argument(text: String): Argument {
    val key = KEY.find(text)?.takeIf { it.groupValues[1] in KEYS }
    return Argument(key?.groupValues?.get(1), unquote(key?.let { text.substring(it.range.last + 1).trim() } ?: text))
}

private fun unquote(text: String): String =
    if (text.length >= 2 && text.first() == text.last() && text.first() in QUOTES) text.substring(1, text.length - 1) else text

/**
 * [text] split at each [separator] outside quotes and parentheses, each part trimmed. honk quotes protect every byte up
 * to the closing quote, `(`, `,`, `&&` and `->` included.
 */
internal fun topLevel(text: String, separator: String): List<String> {
    val parts = mutableListOf<String>()
    var depth = 0
    var quote: Char? = null
    var start = 0
    var i = 0
    while (i < text.length) {
        val c = text[i]
        when {
            quote != null -> if (c == quote) quote = null

            c in QUOTES -> quote = c

            c == '(' -> depth++

            c == ')' -> depth--

            depth == 0 && text.startsWith(separator, i) -> {
                parts += text.substring(start, i).trim()
                i += separator.length
                start = i
                continue
            }
        }
        i++
    }
    parts += text.substring(start).trim()
    return parts
}

private const val DIRECT = "direct"
private const val BLOCK = "block"
private const val AND = "&&"
private const val PRIVATE = "private"
private const val QUOTES = "'\""
private val MATCHER = Regex("""([a-z0-9_]+)\((.*)\)""", RegexOption.DOT_MATCHES_ALL)
private val KEY = Regex("""^([a-z]+)\s*:""")
private val KEYS = setOf("suffix", "keyword", "full", "regex", "geosite", "geoip")

/** A bare `domain(...)` value is a suffix, as honk reads it. */
private val DOMAIN_KINDS = mapOf(null to RvMatchKind.Suffix, "suffix" to RvMatchKind.Suffix, "full" to RvMatchKind.Full, "keyword" to RvMatchKind.Keyword)
