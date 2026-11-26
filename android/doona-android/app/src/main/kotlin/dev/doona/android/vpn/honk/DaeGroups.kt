package dev.doona.android.vpn.honk

/**
 * Group [name]'s block in the `group {}` block of honk's configuration [text], or null when the text has none (honk's
 * `groups.md`: each group is a named subsection, `name { ... }`, of `group { ... }`).
 */
internal fun daeGroup(text: String, name: String): DaeGroup? = daeBlock(text, "group", name)?.let { DaeGroup(text.split("\n"), it) }

/**
 * [text] with each group of [joins] that it holds taking the node names listed for it, a `filter: name('…')` line each;
 * groups the text does not hold are left to the text that does.
 */
internal fun daeJoined(text: String, joins: Map<String, List<String>>): String =
    joins.entries.fold(text) { current, (group, names) -> daeGroup(current, group)?.withNodes(names) ?: current }

/** One group's block: its body's [lines][body], between the line that opens it and the one that closes it. */
internal class DaeGroup internal constructor(private val lines: List<String>, private val body: IntRange) {
    private val filters = body.filter { daeCode(lines[it]).let { code -> code.startsWith("filter") && code.drop(FILTER.length).trimStart().startsWith(":") } }

    /**
     * Whether the group takes every node: one with neither node filters nor nested groups does (`groups.md`, Filter
     * resolution, rule 5), so a name filter would shrink it to that one node.
     */
    val takesEvery: Boolean get() = filters.isEmpty()

    /** The whole text with a `filter: name('…')` line for each of [names] after the group's last filter. */
    fun withNodes(names: List<String>): String {
        val after = filters.lastOrNull() ?: (body.first - 1)
        val like = filters.lastOrNull() ?: body.firstOrNull { daeCode(lines[it]).isNotEmpty() }
        val indent = like?.let { lines[it].takeWhile(Char::isWhitespace) } ?: (lines[body.first - 1].takeWhile(Char::isWhitespace) + "    ")
        val added = names.map { "${indent}filter: name(${it.daeQuoted()})" }
        return (lines.take(after + 1) + added + lines.drop(after + 1)).joinToString("\n")
    }

    private companion object {
        const val FILTER = "filter"
    }
}
