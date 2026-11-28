package dev.doona.android.feature.policies

import dev.doona.android.feature.model.RvMatchKind
import dev.doona.android.feature.model.RvPolicyGroup
import dev.doona.android.feature.model.RvPolicyRule
import dev.doona.android.feature.model.RvRuleEnd
import dev.doona.android.feature.model.RvRuleMatch
import dev.doona.android.feature.model.RvRuleState
import dev.doona.android.honk.HonkRule

/**
 * A rule of honk's `GET /rules` as the pages list it. [groups] holds the profile's groups by name, which is how a rule
 * names its outbound; one it names that is not there is drawn as missing, not as 直連. honk has no per-rule byte count,
 * so every rule shows none.
 */
internal fun rvPolicyRule(rule: HonkRule, groups: Map<String, RvPolicyGroup>): RvPolicyRule =
    rvPolicyRule(if (rule.isFallback) RvRuleMatch.Rest else rvRuleMatch(ruleCondition(rule.expression)), rule.outbound, groups)

/** A rule paused in its file ([HonkPausedRule]), drawn as [rvPolicyRule] draws a loaded one but 已停用. */
internal fun rvPolicyRule(rule: HonkPausedRule, groups: Map<String, RvPolicyGroup>): RvPolicyRule =
    rvPolicyRule(rvRuleMatch(ruleCondition(rule.expression)), rule.outbound, groups, RvRuleState.Off)

private fun rvPolicyRule(match: RvRuleMatch, outbound: String, groups: Map<String, RvPolicyGroup>, state: RvRuleState = RvRuleState.On): RvPolicyRule =
    when (outbound) {
        DIRECT -> RvPolicyRule(match, null, null, state)
        BLOCK -> RvPolicyRule(match, null, null, state, RvRuleEnd.Reject)
        else -> groups[outbound]?.let { RvPolicyRule(match, it, null, state) } ?: RvPolicyRule(match, null, null, state, RvRuleEnd.Missing)
    }

/**
 * What one condition matches, as the pages say it: a single `domain(...)` or `dip(...)` matcher in the words the rule
 * page offers, and anything else (a conjunction, a negation, another matcher) as its text. A `domain(...)` of several
 * values is one rule, named by its first value and the count.
 */
internal fun rvRuleMatch(condition: String): RvRuleMatch {
    val matcher = MATCHER.matchEntire(condition)?.takeIf { topLevel(condition, AND).size == 1 }
        ?: return RvRuleMatch.Value(null, condition)
    val args = topLevel(matcher.groupValues[2], ",").map(::argument)
    val single = args.singleOrNull()
    return when (matcher.groupValues[1]) {
        "domain" -> when {
            single == null -> RvRuleMatch.Domains(args.first().value, args.size)
            single.key == "geosite" -> RvRuleMatch.GeoSite(single.value)
            else -> DOMAIN_KINDS[single.key]?.let { RvRuleMatch.Value(it, single.value) } ?: RvRuleMatch.Value(null, condition)
        }

        "dip" -> when {
            single == null -> RvRuleMatch.Value(null, condition)
            single.key == "geoip" && single.value.equals(PRIVATE, ignoreCase = true) -> RvRuleMatch.Lan
            single.key == "geoip" -> RvRuleMatch.GeoIp(single.value.uppercase())
            else -> RvRuleMatch.Value(RvMatchKind.Cidr, single.value)
        }

        else -> RvRuleMatch.Value(null, condition)
    }
}

/**
 * [condition] as the rule form edits it, or null when the form cannot hold it: a single [domain] matcher (`domain` in
 * routing, `qname` in DNS) of one suffix, full or keyword value, or a single [address] matcher (`dip` in routing, `ip`
 * in a DNS response) of one IPv4 range, `geoip: cn` or `geoip: private`.
 */
internal fun formMatch(condition: String, domain: String = "domain", address: String = "dip"): Pair<RvMatchKind, String>? {
    val matcher = MATCHER.matchEntire(condition)?.takeIf { topLevel(condition, AND).size == 1 } ?: return null
    val single = topLevel(matcher.groupValues[2], ",").singleOrNull()?.let(::argument) ?: return null
    return when (matcher.groupValues[1]) {
        domain -> DOMAIN_KINDS[single.key]?.let { it to single.value }

        address -> when {
            single.key == "geoip" && single.value.equals("cn", ignoreCase = true) -> RvMatchKind.GeoIpCn to ""
            single.key == "geoip" && single.value.equals(PRIVATE, ignoreCase = true) -> RvMatchKind.Private to ""
            single.key == null && RvRuleDraft(RvMatchKind.Cidr, single.value, DIRECT).problem == null -> RvMatchKind.Cidr to single.value
            else -> null
        }

        else -> null
    }
}

/** The honk condition of the form's [kind] and [value], over the [domain] and [address] matchers as [formMatch] reads them. */
internal fun matchText(kind: RvMatchKind, value: String, domain: String = "domain", address: String = "dip"): String = when (kind) {
    RvMatchKind.Full -> "$domain(full: $value)"
    RvMatchKind.Suffix -> "$domain(suffix: $value)"
    RvMatchKind.Keyword -> "$domain(keyword: $value)"
    RvMatchKind.Cidr -> "$address($value)"
    RvMatchKind.GeoIpCn -> "$address(geoip: cn)"
    RvMatchKind.Private -> "$address(geoip: $PRIVATE)"
}

/** The condition of honk's rule text `condition -> outbound`: what comes before its first arrow outside quotes. */
internal fun ruleCondition(expression: String): String = topLevel(expression, "->").first()

/** One matcher argument, `key: value` or a bare value, its quotes dropped. */
internal class Argument(val key: String?, val value: String)

internal fun argument(text: String): Argument {
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
