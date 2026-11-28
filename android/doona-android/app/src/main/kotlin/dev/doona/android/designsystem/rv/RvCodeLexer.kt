package dev.doona.android.designsystem.rv

import androidx.compose.runtime.Immutable

/**
 * A token kind of honk's configuration text, as honk-config reads it (honk `crates/honk-config/src/parser`, at
 * `cd5a0ce6`). Plain text (values, names, blanks, and the punctuation `{ } ( ) , :`) has none.
 */
internal enum class RvCodeKind {
    /** A section's name before its `{`: `routing`, `dns`, and the nested ones (`upstream`, `request`, `geodata`, …). */
    Block,

    /** A setting's key before its `:`, a name an entry declares before its `{` (`hk {`), or a matcher's prefix. */
    Key,

    /** A matcher before its `(`: `domain`, `dip`, `qname`, `name`, `group`, … */
    Call,

    /**
     * A built-in (`direct`, `block`, `must`, `asis`, `reject`, `accept`, a group policy), or any word in a symbol's place:
     * where a rule sends, or a group's `policy:`.
     */
    Symbol,

    /** A quoted string, a number, a duration, a port range, a hex mark, an IPv4 or IPv6 address with its prefix. */
    Literal,

    /** `->`, `&&`, `!` before an operand, and `|`. */
    Operator,

    /** From a `#` at a token's head to the line's end. */
    Comment,
}

/** One token: its [text] and its [kind] (null for plain text, blanks included). The tokens of a text spell it whole. */
@Immutable
internal data class RvCodeToken(val text: String, val kind: RvCodeKind?)

/**
 * honk text split as honk-config reads it, line by line as its lexer does (`parser/lexer.rs`): blanks; a comment from a
 * `#` at a word's head; a quoted string that opens at a word's head or after `(`, `,`, `:` or another string, honours
 * backslash escapes and, unclosed, runs to the line's end; and words. A word splits into the operators, the punctuation
 * and the atoms between them; an atom is a key (`log_file:`), a prefix (`geosite:` of `geosite:cn`; never `ext:`), a
 * call, a block, a symbol, a literal, or plain. A call is known by the `(` right after it (`dport (443)` is none) and a
 * block by the `{` after it on the line, so a lone line reads as it does in its file; only a group's entries (`hk {`) need the file, as their names may be any
 * word.
 */
internal fun rvCodeTokens(text: String): List<RvCodeToken> = RvCodeLexer(text).tokens()

/** What a token is to the lexer's look back: an atom (a value or a name), a blank within a line, or anything else. */
private enum class Role { Atom, Blank, Other }

private class Lexed(val start: Int, val end: Int, var kind: RvCodeKind?, val role: Role)

private class RvCodeLexer(private val text: String) {
    private val out = ArrayList<Lexed>()

    /** The open blocks: a block's name, or "" for an entry's body. */
    private val blocks = ArrayList<String>()

    /** The open parentheses: the call each belongs to, or "". */
    private val calls = ArrayList<String>()

    /** The next value is a symbol: where a rule sends (after `->` or a [TARGET_KEYS] key), or a group's policy. */
    private var target = false

    fun tokens(): List<RvCodeToken> {
        var start = 0
        while (start <= text.length) {
            val end = text.indexOf('\n', start).let { if (it < 0) text.length else it }
            line(start, end)
            if (end < text.length) emit(end, end + 1, null)
            start = end + 1
        }
        return out.map { RvCodeToken(text.substring(it.start, it.end), it.kind) }
    }

    private fun line(from: Int, to: Int) {
        target = false
        var at = from
        while (at < to) {
            at = when {
                text[at].isWhitespace() -> blank(at, to)
                text[at] == '#' -> emit(at, to, RvCodeKind.Comment)
                else -> word(at, to)
            }
        }
    }

    private fun blank(from: Int, to: Int): Int {
        var end = from
        while (end < to && text[end].isWhitespace()) end++
        return emit(from, end, null, Role.Blank)
    }

    /** A word: the pieces up to the next blank outside a string. */
    private fun word(from: Int, to: Int): Int {
        var at = from
        var opens = true
        while (at < to && !text[at].isWhitespace()) {
            val quote = opens && text[at] in QUOTES
            at = if (quote) quoted(at, to) else piece(at, to)
            opens = quote || text[at - 1] in QUOTE_AFTER
        }
        return at
    }

    private fun quoted(from: Int, to: Int): Int {
        target = false
        var at = from + 1
        while (at < to && text[at] != text[from]) at += if (text[at] == '\\') 2 else 1
        return emit(from, minOf(at + 1, to), RvCodeKind.Literal)
    }

    private fun piece(at: Int, to: Int): Int = when {
        text.startsWith(ARROW, at) -> emit(at, at + ARROW.length, RvCodeKind.Operator).also { target = true }
        text.startsWith(AND, at) -> emit(at, at + AND.length, RvCodeKind.Operator)
        text.startsWith(OR, at) -> emit(at, at + OR.length, null)
        text[at] == '|' -> emit(at, at + 1, RvCodeKind.Operator)
        text[at] == '!' && (at == 0 || text[at - 1].isWhitespace() || text[at - 1] in "(,") -> emit(at, at + 1, RvCodeKind.Operator)
        text[at] in PUNCTUATION -> punctuation(at)
        text[at] == ':' && at > 0 && text[at - 1] in QUOTES -> emit(at, at + 1, null)
        else -> atom(at, to)
    }

    private fun punctuation(at: Int): Int {
        when (text[at]) {
            '{' -> blocks += declare()
            '}' -> blocks.removeLastOrNull()
            '(' -> calls += out.lastOrNull { it.role != Role.Blank }?.takeIf { it.kind == RvCodeKind.Call }?.let { text.substring(it.start, it.end) }.orEmpty()
            ')' -> calls.removeLastOrNull()
        }
        return emit(at, at + 1, null)
    }

    /**
     * Before a `{`: the atoms back to the last other token on the line are the name the entry declares (a group's `hk`,
     * or `Two Words`), keys; the block it opens is the block's name before it, or "" for an entry.
     */
    private fun declare(): String {
        var i = out.lastIndex
        while (i >= 0 && out[i].role != Role.Other) {
            if (out[i].role == Role.Atom) out[i].kind = RvCodeKind.Key
            i--
        }
        return out.getOrNull(i)?.takeIf { it.kind == RvCodeKind.Block && i == out.indexOfLast { t -> t.role != Role.Blank } }
            ?.let { text.substring(it.start, it.end) }.orEmpty()
    }

    private fun atom(from: Int, to: Int): Int {
        var end = from
        var colon = -1
        while (end < to && !endsAtom(end)) {
            if (colon < 0 && text[end] == ':') colon = end
            end++
        }
        when {
            colon > from && colon == end - 1 -> key(from, colon)

            colon > from && text.substring(from, colon) in PREFIXES -> {
                emit(from, colon, RvCodeKind.Key)
                emit(colon, colon + 1, null)
                emit(colon + 1, end, null)
            }

            else -> value(from, end, to)
        }
        return end
    }

    private fun endsAtom(at: Int): Boolean {
        val c = text[at]
        return c.isWhitespace() || c in PUNCTUATION || c == '|' || text.startsWith(ARROW, at) || text.startsWith(AND, at) ||
            (c in QUOTES && text[at - 1] == ':')
    }

    private fun key(from: Int, colon: Int) {
        emit(from, colon, RvCodeKind.Key)
        emit(colon, colon + 1, null)
        target = text.substring(from, colon) in TARGET_KEYS
    }

    private fun value(from: Int, end: Int, to: Int) {
        val word = text.substring(from, end)
        var ahead = end
        while (ahead < to && text[ahead].isWhitespace()) ahead++
        val next = text.getOrNull(ahead).takeIf { ahead < to }
        val kind = when {
            target -> RvCodeKind.Symbol
            end < to && text[end] == '(' && word in CALLS -> RvCodeKind.Call
            next == '{' && word in BLOCKS && blocks.lastOrNull() != GROUP -> RvCodeKind.Block
            word in SYMBOLS -> RvCodeKind.Symbol
            LITERAL.matches(word) && calls.lastOrNull() !in NAME_CALLS -> RvCodeKind.Literal
            else -> null
        }
        target = false
        emit(from, end, kind, if (kind == null || kind == RvCodeKind.Literal) Role.Atom else Role.Other)
    }

    private fun emit(start: Int, end: Int, kind: RvCodeKind?, role: Role = Role.Other): Int {
        if (end > start) out += Lexed(start, end, kind, role)
        return end
    }
}

private const val ARROW = "->"
private const val AND = "&&"
private const val OR = "||"
private const val QUOTES = "'\""
private const val QUOTE_AFTER = "(,:'\""
private const val PUNCTUATION = "{}(),"
private const val GROUP = "group"

/** honk's sections (`parser/cursor.rs`) and the blocks inside dns, assets and experimental. */
private val BLOCKS = setOf(
    "include", "global", "node", GROUP, "subscription", "routing", "dns", "experimental", "assets",
    "upstream", "fixed_domain_ttl", "request", "response",
    "geodata", "ui",
    "clash_api", "native_api", "cache_file", "udp_nfqueue",
)

/** Traffic matchers (`parser/routing.rs`), DNS matchers (`parser/dns.rs`) and group filters (`parser/groups.rs`). */
private val CALLS = setOf(
    "pname", "dip", "sip", "domain", "dport", "sport", "l4proto", "ipversion", "mac", "dscp",
    "qname", "qtype", "subtag", "upstream", "ip",
    "name", GROUP,
)

/** The calls whose arguments are names, so `group(123)` names a group rather than a number. */
private val NAME_CALLS = setOf("pname", "subtag", "upstream", "name", GROUP)

/** The matcher prefixes; `ext:` is none. */
private val PREFIXES = setOf("geosite", "geoip", "suffix", "keyword", "full", "regex", "domain")

/** The built-in outbounds and modifiers, the DNS actions, and the group policies (`parser/groups.rs`). */
private val SYMBOLS = setOf(
    "direct", "block", "must", "asis", "reject", "accept",
    "select", "selector", "fixed", "urltest", "min_moving_avg", "min_avg10", "min_last_delay",
    "roundrobin", "round_robin", "loadbalance", "balance", "fallback", "score",
)

/**
 * The keys whose value is a symbol: where a rule sends, or a group's policy, which honk reads as one whatever the word
 * (`parser/groups.rs` warns of an unknown one and selects), so `policy: min` reads as `policy: min_moving_avg` does.
 */
private val TARGET_KEYS = setOf("default", "fallback", "final", "outbound", "policy")

/** A whole atom that is a hex mark, a port range, an IPv4 or IPv6 address with its prefix, or a number with its unit. */
private val LITERAL = Regex(
    "0x[0-9A-Fa-f]+|[0-9]+-[0-9]+|[0-9]{1,3}(?:\\.[0-9]{1,3}){3}(?:/[0-9]{1,3})?|" +
        "(?=[0-9A-Fa-f:]*:[0-9A-Fa-f]*:)[0-9A-Fa-f:]+(?:/[0-9]{1,3})?|[0-9]+(?:\\.[0-9]+)?[a-zµ]*",
)
