package dev.doona.android.vpn.honk

/**
 * The 0-based line indices of the body of the block at [path] in honk's configuration [text] (`routing`, or `dns`,
 * `routing`, `request`), between its opening and closing lines; null when the text has no such block. A block opens on
 * a line ending in `name {` and closes on a line of its own `}`; braces opened and closed on one line, as in
 * `upstream { a: 'x' }`, nest nothing.
 */
internal fun daeBlock(text: String, vararg path: String): IntRange? {
    val open = mutableListOf<String>()
    var start = -1
    text.lines().forEachIndexed { index, line ->
        val code = daeCode(line)
        when {
            code == "}" -> {
                if (start >= 0 && open == path.toList()) return start until index
                open.removeLastOrNull()
            }

            code.endsWith("{") && code.count { it == '{' } == code.count { it == '}' } + 1 -> {
                open += code.removeSuffix("{").trim()
                if (open == path.toList()) start = index + 1
            }
        }
    }
    return null
}

/** The entries of [lines] as honk reads them: each line's code, trimmed, with comments and blank lines left out. */
internal fun daeEntries(lines: List<String>): List<String> = lines.map(::daeCode).filter { it.isNotEmpty() }

/** [line] without its comment (from a `#` outside quotes), trimmed. */
internal fun daeCode(line: String): String {
    var quote: Char? = null
    line.forEachIndexed { index, char ->
        when {
            quote != null -> if (char == quote) quote = null
            char == '\'' || char == '"' -> quote = char
            char == '#' -> return line.substring(0, index).trim()
        }
    }
    return line.trim()
}

/**
 * The blocks directly inside the block at [path] of [text] (each group of `group`), by name, with the 0-based indices of
 * their lines from the `name {` line to their closing `}`; empty when the text has no such block.
 */
internal fun daeChildren(text: String, vararg path: String): List<Pair<String, IntRange>> {
    val body = daeBlock(text, *path) ?: return emptyList()
    val lines = text.lines()
    val children = mutableListOf<Pair<String, IntRange>>()
    var depth = 0
    var open: Pair<String, Int>? = null
    for (index in body) {
        val code = daeCode(lines[index])
        when {
            code == "}" -> if (--depth == 0) open?.let { children += it.first to (it.second..index) }

            code.endsWith("{") && code.count { it == '{' } == code.count { it == '}' } + 1 -> {
                if (depth++ == 0) open = code.removeSuffix("{").trim() to index
            }
        }
    }
    return children
}
