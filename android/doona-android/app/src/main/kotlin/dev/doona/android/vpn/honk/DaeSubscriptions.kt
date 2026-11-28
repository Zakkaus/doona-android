package dev.doona.android.vpn.honk

import dev.doona.android.engine.EngineException
import dev.doona.android.engine.ErrorCode

/**
 * Subscription [name]'s entry in the `subscription {}` block of honk's configuration [text], or null when the block has
 * no entry of that name. An entry is `tag: 'url'`, the same with a block of options after the URL (`tag: 'url' {`, one
 * key per line, `ua: '...'` among them), or the legacy `'url'(UA)` suffix; a tagless entry is named by its URL's host
 * (honk's `subscription.md`). An entry this does not read, such as a bare URL, is skipped.
 */
internal fun daeSubscription(text: String, name: String): DaeSubscription? {
    val lines = text.split("\n")
    val body = daeBlock(text, "subscription") ?: return null
    var index = body.first
    while (index <= body.last) {
        val code = daeCode(lines[index])
        val close = if (daeOpens(code)) daeClosing(lines, index) else index
        val entry = daeEntry(code)
        if (entry != null && entry.name == name) return DaeSubscription(lines, index, entry, close.takeIf { entry.opens })
        index = close + 1
    }
    return null
}

/** One subscription entry on [lines][index], with its block's closing line [close] when it has one. */
internal class DaeSubscription internal constructor(
    private val lines: List<String>,
    private val index: Int,
    private val entry: DaeEntry,
    private val close: Int?,
) {
    private val indent = lines[index].takeWhile { it.isWhitespace() }

    /** The `ua` the entry sets, in its block or in the legacy suffix; null for honk's default. */
    val userAgent: String? = uaLine()?.let { daeCode(lines[it]).substringAfter(':').trim().unquoted() }
        ?: entry.suffix?.let { daeCode(lines[index]).substring(it.first + 1, it.last).trim().unquoted() }

    /** The whole text with the entry tagged [name]. */
    fun renamed(name: String): String {
        val tag = if (name.matches(BARE_TAG)) name else name.daeQuoted()
        val line = lines[index]
        val renamed = entry.tag?.let { line.replaceRange(indent.length + it.first, indent.length + it.last + 1, tag) }
            ?: line.replaceRange(indent.length, indent.length, "$tag: ")
        return (lines.take(index) + renamed + lines.drop(index + 1)).joinToString("\n")
    }

    /**
     * The whole text with the entry's `ua` set to [userAgent], or taken out when null. The legacy suffix gives way to
     * the block; a one-line entry gains a block to hold the `ua`, and a block left with no option goes.
     */
    fun withUserAgent(userAgent: String?): String {
        val out = lines.toMutableList()
        var head = entry.suffix?.let { out[index].removeRange(indent.length + it.first, indent.length + it.last + 1) } ?: out[index]
        val ua = uaLine()
        when {
            userAgent != null && ua != null -> out[ua] = out[ua].takeWhile { it.isWhitespace() } + "ua: " + userAgent.daeQuoted()

            userAgent != null && close != null -> out.add(index + 1, bodyIndent() + "ua: " + userAgent.daeQuoted())

            userAgent != null -> {
                head = head.replaceRange(codeEnd(head), codeEnd(head), " {")
                out.addAll(index + 1, listOf(bodyIndent() + "ua: " + userAgent.daeQuoted(), "$indent}"))
            }

            ua != null -> {
                out.removeAt(ua)
                if (daeEntries(out.subList(index + 1, close!! - 1)).isEmpty()) {
                    repeat(close - index - 1) { out.removeAt(index + 1) }
                    val end = codeEnd(head)
                    head = indent + head.substring(indent.length, end).removeSuffix("{").trimEnd() + head.substring(end)
                }
            }
        }
        out[index] = head
        return out.joinToString("\n")
    }

    /** The block's `ua` line, when the entry has a block that sets one. */
    private fun uaLine(): Int? = close?.let { (index + 1 until it).firstOrNull { line -> daeCode(lines[line]).let { code -> code.startsWith("ua") && code.drop(2).trimStart().startsWith(":") } } }

    /** The indent of the block's options: that of its first, or four spaces in from the entry. */
    private fun bodyIndent(): String =
        close?.let { (index + 1 until it).firstOrNull { line -> daeCode(lines[line]).isNotEmpty() } }?.let { lines[it].takeWhile(Char::isWhitespace) } ?: "$indent    "

    /** Where [line]'s code ends, before the space and comment after it. */
    private fun codeEnd(line: String): Int = indent.length + daeCode(line).length
}

/**
 * An entry line's code, as positions in it: [tag] and [suffix] (its parentheses included) when present, the [url], and
 * whether a block of options [opens] after it.
 */
internal class DaeEntry(val tag: IntRange?, val tagText: String?, val url: String, val suffix: IntRange?, val opens: Boolean) {
    val name: String get() = tagText ?: url.substringAfter("://").substringBefore('/').substringAfterLast('@').substringBefore(':')
}

/** [code] read as a subscription entry; null when it is none this reads. */
internal fun daeEntry(code: String): DaeEntry? {
    var at = 0
    var tag: IntRange? = null
    var tagText: String? = null
    if (code.firstOrNull() !in QUOTES) {
        val colon = code.indexOf(':').takeIf { it > 0 } ?: return null
        tag = 0 until colon
        tagText = code.substring(0, colon).trim()
        at = colon + 1
    } else {
        val end = code.indexOf(code[0], 1).takeIf { it > 0 } ?: return null
        val after = code.substring(end + 1).trimStart()
        if (after.startsWith(":")) {
            tag = 0..end
            tagText = code.substring(1, end)
            at = code.indexOf(':', end) + 1
        }
    }
    while (at < code.length && code[at].isWhitespace()) at++
    val quote = code.getOrNull(at)?.takeIf { it in QUOTES } ?: return null
    val urlEnd = code.indexOf(quote, at + 1).takeIf { it > 0 } ?: return null
    val url = code.substring(at + 1, urlEnd)
    if ("://" !in url) return null
    var rest = urlEnd + 1
    val suffix = if (code.getOrNull(rest) == '(') (rest..(daeParenEnd(code, rest) ?: return null)) else null
    if (suffix != null) rest = suffix.last + 1
    return when (code.substring(rest).trim()) {
        "" -> DaeEntry(tag, tagText, url, suffix, opens = false)
        "{" -> DaeEntry(tag, tagText, url, suffix, opens = true)
        else -> null
    }
}

/** The index of the `)` closing the `(` at [open] in [code], quotes and nested parentheses kept whole. */
private fun daeParenEnd(code: String, open: Int): Int? {
    var depth = 0
    var quote: Char? = null
    for (i in open until code.length) {
        val c = code[i]
        when {
            quote != null -> if (c == quote) quote = null
            c in QUOTES -> quote = c
            c == '(' -> depth++
            c == ')' -> if (--depth == 0) return i
        }
    }
    return null
}

/** Whether [code] opens a block, as [daeBlock] reads one. */
private fun daeOpens(code: String) = code.endsWith("{") && code.count { it == '{' } == code.count { it == '}' } + 1

/** The line closing the block [lines][open] opens. */
private fun daeClosing(lines: List<String>, open: Int): Int {
    var depth = 0
    for (i in open + 1 until lines.size) {
        val code = daeCode(lines[i])
        if (code == "}") {
            if (depth == 0) return i
            depth--
        } else if (daeOpens(code)) {
            depth++
        }
    }
    return lines.lastIndex
}

/** [this] in single quotes, or double when it holds one; honk's dae has no escape, so one holding both is refused. */
internal fun String.daeQuoted(): String = when {
    '\n' in this || ('\'' in this && '"' in this) -> throw EngineException(ErrorCode.InvalidArgument)
    '\'' in this -> "\"$this\""
    else -> "'$this'"
}

private fun String.unquoted(): String = if (length >= 2 && first() in QUOTES && last() == first()) substring(1, length - 1) else this

private val QUOTES = setOf('\'', '"')

/** A tag honk reads without quotes. */
private val BARE_TAG = Regex("[A-Za-z0-9_-]+")
