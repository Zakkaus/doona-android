package dev.doona.android.engine

/**
 * The display names of the nodes in [groups] by the code the core writes for each in `effectiveConfig`'s text: `n-` and
 * the first 8 hex digits of its id (ARCH D21). For the screen only: text that is exported or shared keeps the codes,
 * because a display name can hold the node's server, port or password.
 */
class NodeCodes(groups: List<Group>) {
    private val names: Map<String, String?> = buildMap {
        groups.flatMap { it.members }.filterNot { it.isGroup }.distinctBy { it.id }.forEach { node ->
            val code = codeOf(node.id)
            // Two nodes whose ids start alike share a code, which then names neither.
            put(code, if (code in this) null else node.name)
        }
    }

    /** The name of the node [code] stands for; null when no node, or more than one, has that code. */
    fun name(code: String): String? = names[code]

    /**
     * [text] with each code in its group lines, where the core lists a group's current choice and members, replaced by
     * its node's name. A code that names no single node stays as it is, and so does every other line: a rule's value or
     * a skipped rule's comment can hold the same letters.
     */
    fun onScreen(text: String): String = text.lineSequence().joinToString("\n") { line ->
        if (line.startsWith(GROUP_LINE)) CODE.replace(line) { name(it.value) ?: it.value } else line
    }

    companion object {
        private const val GROUP_LINE = "# group "
        private val CODE = Regex("""\bn-\w+""")

        /** The code the core writes for the node [id]. */
        fun codeOf(id: String): String = "n-" + id.removePrefix("n-").take(8)
    }
}
