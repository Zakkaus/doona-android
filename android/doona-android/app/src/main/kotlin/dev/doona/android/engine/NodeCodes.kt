package dev.doona.android.engine

/**
 * The display names of the nodes in [groups] by the code the core writes for each in `effectiveConfig`'s text: `n-` and
 * the first 8 hex digits of its id (ARCH D21). For the screen only: text that is exported or shared keeps the codes,
 * because a display name can hold the node's server, port or password.
 */
class NodeCodes(groups: List<Group>) {
    private val names: Map<String, String?> = buildMap {
        groups.flatMap { it.members }.filterNot { it.isGroup }.distinctBy { it.id }.forEach { node ->
            // An id of another shape has no code the core would write.
            val code = codeOf(node.id).takeIf(CODE::matches) ?: return@forEach
            // Two nodes whose ids start alike share a code, which then names neither.
            put(code, if (code in this) null else node.name)
        }
    }

    /** The name of the node [code] stands for; null when [code] is no whole code, or no node or more than one has it. */
    fun name(code: String): String? = names[code]

    /**
     * [text] with each code in its group lines, where the core lists a group's current choice and members, replaced by
     * its node's name, written out so that it cannot start a line of its own. A code that names no single node stays as
     * it is, and so does every other line: a rule's value or a skipped rule's comment can hold the same letters.
     */
    fun onScreen(text: String): String = text.lineSequence().joinToString("\n") { line ->
        if (line.startsWith(GROUP_LINE)) CODE_IN_LINE.replace(line) { code -> name(code.value)?.let(::visible) ?: code.value } else line
    }

    companion object {
        private const val GROUP_LINE = "# group "
        private const val HEX = 16
        private val CODE = Regex("n-[0-9a-f]{8}")
        private val CODE_IN_LINE = Regex("""\bn-[0-9a-f]{8}\b""")

        /** The code the core writes for the node [id]. */
        fun codeOf(id: String): String = "n-" + id.removePrefix("n-").take(8)

        /** [name] with every control character and line or paragraph separator written as an escape, such as `\n`. */
        private fun visible(name: String): String = buildString {
            for (c in name) {
                when {
                    c == '\n' -> append("\\n")
                    c == '\r' -> append("\\r")
                    c == '\t' -> append("\\t")
                    c.breaksOrControls() -> append("\\u").append(c.code.toString(HEX).padStart(4, '0'))
                    else -> append(c)
                }
            }
        }

        /** A control character, or a separator a renderer may start a new line at. */
        private fun Char.breaksOrControls() =
            isISOControl() || category == CharCategory.LINE_SEPARATOR || category == CharCategory.PARAGRAPH_SEPARATOR
    }
}
