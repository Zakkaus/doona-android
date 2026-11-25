package dev.doona.android.prototype.rv

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * The footnote's component gate (LAYOUT-RULES.md R20; owner, q-s1125c-footgap): a page draws a line of secondary text
 * in the footnote's role only through [dev.doona.android.designsystem.rv.RvFootnote]. Every text call of a page file
 * (`RvText`, `Text`, `BasicText`) in the secondary colour at the row subtitle's step, or a raw `Text`/`BasicText` in the
 * secondary colour, is listed in `rv-footnote-gate.txt` with why it is not a footnote (a value inside a card's row, a
 * centred header line); any other fails, and so does a listed one that is gone. The secondary colour is found by its
 * name or through a local `val` of the file that holds it (`val secondary = Rv.colors.textSecondary`). Comments and
 * string literals are read as blanks, so a `{` or `(` in them neither opens a block nor a call.
 */
class RvFootnoteGateTest {
    @Test
    fun pagesDrawFootnotesOnlyThroughRvFootnote() {
        val found = PAGES.flatMap { root -> File(root).walkTopDown().filter { it.extension == "kt" }.flatMap { rvFootnoteLike(it) } }.sorted()
        val listed = File(ALLOWLIST).readLines().filter { it.isNotBlank() && !it.startsWith("#") }.map { it.substringBeforeLast('\t') }.sorted()
        assertEquals("secondary text in the footnote's role outside RvFootnote (left) vs $ALLOWLIST", listed, found)
    }

    @Test
    fun thePlantedFootnoteIsCaught() {
        val plant = File.createTempFile("RvPlant", ".kt").apply {
            writeText("@Composable\nfun Plant() {\n    RvCard { }\n    RvText(note, Rv.type.rowSub, Modifier.padding(start = RvSpace.gutter), color = Rv.colors.textSecondary)\n}\n")
            deleteOnExit()
        }
        assertEquals(1, rvFootnoteLike(plant).size)
    }

    @Test
    fun aFootnoteInTheSecondaryColourByAnotherNameIsCaught() {
        val plant = File.createTempFile("RvPlant", ".kt").apply {
            writeText("@Composable\nfun Plant() {\n    val quiet = Rv.colors.textSecondary\n    RvCard { }\n    RvText(note, Rv.type.rowSub, color = quiet)\n}\n")
            deleteOnExit()
        }
        assertEquals(1, rvFootnoteLike(plant).size)
    }

    @Test
    fun aTypedValIsResolved() = caught(1, "@Composable\nfun Plant() {\n    val quiet: Color = Rv.colors.textSecondary\n    RvText(note, Rv.type.rowSub, color = quiet)\n}\n")

    @Test
    fun aVarIsResolved() = caught(1, "@Composable\nfun Plant() {\n    var quiet = Rv.colors.textSecondary\n    RvText(note, Rv.type.rowSub, color = quiet)\n}\n")

    @Test
    fun aDefaultParameterIsResolved() = caught(1, "@Composable\nfun Plant(note: String, tint: Color = Rv.colors.textSecondary) {\n    RvText(note, Rv.type.rowSub, color = tint)\n}\n")

    @Test
    fun aValOverTwoLinesIsResolved() = caught(1, "@Composable\nfun Plant(error: Boolean) {\n    val hue =\n        if (error) Rv.colors.error else Rv.colors.textSecondary\n    RvText(note, Rv.type.rowSub, color = hue)\n}\n")

    @Test
    fun anAliasOfAnAliasIsResolved() = caught(1, "@Composable\nfun Plant() {\n    val quiet = Rv.colors.textSecondary\n    val note = quiet\n    RvText(text, Rv.type.rowSub, color = note)\n}\n")

    @Test
    fun aFileLevelValIsResolvedWhereItIsUsed() = caught(1, "private val quiet = Rv.colors.textSecondary\n\n@Composable\nfun Plant() {\n    RvText(note, Rv.type.rowSub, color = quiet)\n}\n")

    /** A file-level alias named `color` made every later `color = ...` call match by the argument's name. */
    @Test
    fun aFileLevelValDoesNotCatchOtherTexts() =
        caught(0, "private val color = Rv.colors.textSecondary\n\n@Composable\nfun Plant() {\n    RvText(note, Rv.type.rowSub, color = Rv.colors.text)\n    RvText(other, Rv.type.rowSub, Modifier.padding(RvSpace.rowPadding))\n}\n")

    /** One function's alias does not reach into another, where the same name holds another colour. */
    @Test
    fun anAliasStaysInItsScope() =
        caught(0, "@Composable\nfun One() {\n    val quiet = Rv.colors.textSecondary\n    RvCard { }\n}\n\n@Composable\nfun Two() {\n    val quiet = Rv.colors.text\n    RvText(note, Rv.type.rowSub, color = quiet)\n}\n")

    /** An expression-bodied composable has no `{`: its parameter's default holds the colour in the expression. */
    @Test
    fun anExpressionBodysDefaultParameterIsResolved() =
        caught(1, "@Composable\nfun Plant(note: String, tint: Color = Rv.colors.textSecondary) = RvText(note, Rv.type.rowSub, color = tint)\n")

    /** The expression's scope ends with it: the next function's text in a colour of the same name is not caught. */
    @Test
    fun anExpressionBodysParameterStaysInItsExpression() {
        val found = calls(
            "@Composable\nfun Plant(note: String, tint: Color = Rv.colors.textSecondary) =\n    RvText(note, Rv.type.rowSub, color = tint)\n\n" +
                "@Composable\nfun Other(tint: Color) {\n    RvCard { }\n    RvText(other, Rv.type.rowSub, color = tint)\n}\n",
        )
        assertEquals(listOf("RvText(note, Rv.type.rowSub, color = tint)"), found)
    }

    /** A `{` in a comment paired with a later `}`, so the alias's block reached the file's end and the next function. */
    @Test
    fun aBraceInACommentOpensNoBlock() =
        caught(
            0,
            "@Composable\nfun One() {\n    val quiet = Rv.colors.textSecondary\n    Column {\n        // (see {below\n    }\n}\n\n" +
                "@Composable\nfun Two(quiet: Color) {\n    RvText(note, Rv.type.rowSub, color = quiet)\n}\n",
        )

    /** The same through a string literal. */
    @Test
    fun aBraceInAStringOpensNoBlock() =
        caught(
            0,
            "@Composable\nfun One() {\n    val quiet = Rv.colors.textSecondary\n    Column {\n        val hint = \"(see {below\"\n    }\n}\n\n" +
                "@Composable\nfun Two(quiet: Color) {\n    RvText(note, Rv.type.rowSub, color = quiet)\n}\n",
        )

    /** A `(` in a comment inside a call does not run the call past its `)`, and a call in a comment is no call. */
    @Test
    fun aParenthesisInACommentKeepsTheCallsEnd() {
        val found = calls(
            "@Composable\nfun Plant() {\n    // RvText(old, Rv.type.rowSub, color = Rv.colors.textSecondary)\n" +
                "    RvText(\n        note, // (see {below\n        Rv.type.rowSub,\n        color = Rv.colors.textSecondary,\n    )\n    RvCard { }\n}\n",
        )
        assertEquals(listOf("RvText( note, // (see {below Rv.type.rowSub, color = Rv.colors.textSecondary, )"), found)
    }

    /** The calls [source] is caught on, each as the allowlist writes it (after the path). */
    private fun calls(source: String): List<String> {
        val plant = File.createTempFile("RvPlant", ".kt").apply {
            writeText(source)
            deleteOnExit()
        }
        return rvFootnoteLike(plant).map { it.substringAfter('\t') }
    }

    private fun caught(count: Int, source: String) {
        val plant = File.createTempFile("RvPlant", ".kt").apply {
            writeText(source)
            deleteOnExit()
        }
        assertEquals(source, count, rvFootnoteLike(plant).size)
    }

    private companion object {
        val PAGES = listOf("src/debug/kotlin/dev/doona/android/prototype/rv", "src/main/kotlin/dev/doona/android/feature")
        const val ALLOWLIST = "src/testDebug/kotlin/dev/doona/android/prototype/rv/rv-footnote-gate.txt"
    }
}

/** The calls of [file] that draw secondary text in the footnote's role: `path<TAB>call`, the call's words on one line. */
internal fun rvFootnoteLike(file: File): List<String> {
    val source = file.readText()
    val code = rvCode(source)
    val colours = RvColourNames(code)
    val calls = Regex("""\b(RvText|Text|BasicText)\(""").findAll(code).mapNotNull { m ->
        val close = closing(code, m.range.last)
        val args = arguments(code.substring(m.range.last + 1, close))
        val call = source.substring(m.range.first, close + 1).split(Regex("\\s+")).joinToString(" ")
        // An argument's value, never its name: `color = Rv.colors.text` is not the colour of an alias named `color`.
        val secondary = args.any { colours.secondary(it.value, m.range.first) }
        val role = args.any { "type.rowSub" in it.value } || m.groupValues[1] != "RvText"
        call.takeIf { secondary && role }
    }
    val path = file.path.substringAfter("kotlin/dev/doona/android/")
    return calls.map { "$path\t${it.take(CALL)}" }.toList()
}

/**
 * The names a file gives the secondary colour, each in its scope: a `val` or `var` (typed or not, its value on one
 * line or more), a parameter's default, at file level or in a block, and an alias of an alias. A name holds the colour
 * from its declaration to the end of the block it is declared in (a parameter, in its function's body); the innermost
 * declaration of a name wins, so one function's alias does not reach another's same name. [source] is the file's
 * [rvCode].
 */
private class RvColourNames(private val source: String) {
    private class Name(val name: String, val from: Int, val to: Int, val secondary: Boolean)

    private val blocks = braces(source)
    private val names = mutableListOf<Name>()

    init {
        val found = (declared() + parameters()).sortedBy { it.first }
        found.forEach { (at, name, value, scope) -> names += Name(name, scope.first, scope.last, secondary(value, at)) }
    }

    /** Whether [value], an expression at [at], holds the secondary colour: by its name, or through a name in scope. */
    fun secondary(value: String, at: Int): Boolean =
        "textSecondary" in value || IDENTIFIER.findAll(value).any { id -> names.filter { it.name == id.value && at in it.from..it.to }.maxByOrNull { it.from }?.secondary == true }

    private fun declared() = DECLARATION.findAll(source).map { m ->
        val start = m.range.last + 1
        Quad(m.range.first, m.groupValues[1], source.substring(start, valueEnd(start)), m.range.first..blockEnd(m.range.first))
    }.toList()

    private fun parameters() = PARAMETER.findAll(source).mapNotNull { m ->
        val start = m.range.last + 1
        val end = argumentEnd(start)
        // The function's body: an expression after `=` (an expression body has no block), else the first block after the
        // parameter list closes.
        val listEnd = closingOfOpen(source, m.range.first)
        val expression = EXPRESSION_BODY.matchAt(source, listEnd + 1)
        val scope = if (expression != null) {
            expression.range.last..valueEnd(expression.range.last + 1)
        } else {
            val body = source.indexOf('{', listEnd).takeIf { it >= 0 } ?: return@mapNotNull null
            body..(blocks[body] ?: source.length)
        }
        Quad(m.range.first, m.groupValues[1], source.substring(start, end), scope)
    }.toList()

    /** Where a declaration's value ends: the line's end at depth 0, unless the next line goes on with the expression. */
    private fun valueEnd(start: Int): Int {
        var depth = 0
        var i = start
        while (i < source.length) {
            when (source[i]) {
                '(', '[', '{' -> depth++
                ')', ']', '}' -> if (depth == 0) return i else depth--
                ';' -> if (depth == 0) return i
                '\n' -> if (depth == 0 && source.substring(start, i).isNotBlank() && !continues(i + 1)) return i
            }
            i++
        }
        return i
    }

    private fun continues(at: Int) = CONTINUATION.matchesAt(source, at)

    /** Where a parameter's default ends: its `,` or the list's `)`. */
    private fun argumentEnd(start: Int): Int {
        var depth = 0
        var i = start
        while (i < source.length) {
            when (source[i]) {
                '(', '[', '{' -> depth++
                ')', ']', '}' -> if (depth == 0) return i else depth--
                ',' -> if (depth == 0) return i
            }
            i++
        }
        return i
    }

    /** The end of the innermost block holding [at], or the file's end at file level. */
    private fun blockEnd(at: Int) = blocks.entries.filter { (open, close) -> open < at && at < close }.minOfOrNull { it.value } ?: source.length

    private data class Quad(val first: Int, val second: String, val third: String, val fourth: IntRange)

    private companion object {
        val DECLARATION = Regex("""\b(?:val|var)\s+(\w+)\s*(?::\s*[\w.?<>, ]+?)?\s*=(?!=)""")
        val PARAMETER = Regex("""[(,]\s*(?:@\w+\s+)*(?:(?:private|internal)\s+)?(?:val\s+|var\s+)?(\w+)\s*:\s*[\w.?<>]+\s*=(?!=)""")
        val EXPRESSION_BODY = Regex("""\s*(?::\s*[\w.?<>, ]+?)?\s*=(?!=)""")
        val CONTINUATION = Regex("""\s*(else\b|\?:|\.|&&|\|\||\+|-|\*|/)""")
        val IDENTIFIER = Regex("""(?<![.\w])[A-Za-z_]\w*""")
    }
}

/**
 * [source] with every comment and string literal (its quotes and templates too) turned to spaces, its line breaks kept,
 * so each offset stays where it was and only code is left to read for braces, calls and arguments.
 */
internal fun rvCode(source: String): String {
    val out = source.toCharArray()
    var i = 0
    while (i < source.length) {
        val end = when {
            source.startsWith("//", i) -> source.indexOf('\n', i).let { if (it < 0) source.length else it }
            source.startsWith("/*", i) -> commentEnd(source, i)
            source[i] == '"' -> stringEnd(source, i)
            source[i] == '\'' -> charEnd(source, i)
            else -> -1
        }
        if (end < 0) {
            i++
        } else {
            for (k in i until end) if (out[k] != '\n') out[k] = ' '
            i = end
        }
    }
    return String(out)
}

/** The end of the block comment at [at]: Kotlin's block comments nest. */
private fun commentEnd(source: String, at: Int): Int {
    var depth = 0
    var i = at
    while (i < source.length) {
        when {
            source.startsWith("/*", i) -> depth++

            source.startsWith("*/", i) -> depth--

            else -> {
                i++
                continue
            }
        }
        i += 2
        if (depth == 0) return i
    }
    return source.length
}

/** The end of the string literal at [at], plain or raw, its templates' code and strings included. */
private fun stringEnd(source: String, at: Int): Int {
    val raw = source.startsWith("\"\"\"", at)
    var i = at + if (raw) 3 else 1
    while (i < source.length) {
        when {
            source.startsWith("\${", i) -> i = templateEnd(source, i + 2)
            raw -> if (source.startsWith("\"\"\"", i)) return (i + 3 until source.length).firstOrNull { source[it] != '"' } ?: source.length else i++
            source[i] == '\\' -> i += 2
            source[i] == '"' -> return i + 1
            source[i] == '\n' -> return i
            else -> i++
        }
    }
    return source.length
}

/** The end of a string template's code that starts at [at], after its `${`: its own `}`, past any string inside it. */
private fun templateEnd(source: String, at: Int): Int {
    var depth = 1
    var i = at
    while (i < source.length && depth > 0) {
        when (source[i]) {
            '"' -> i = stringEnd(source, i) - 1
            '{' -> depth++
            '}' -> depth--
        }
        i++
    }
    return i
}

/** The end of the character literal at [at] (`'{'`, `'\''`). */
private fun charEnd(source: String, at: Int): Int {
    val close = source.indexOf('\'', at + if (source.getOrNull(at + 1) == '\\') 3 else 2)
    return if (close < 0) at + 1 else close + 1
}

/** Each `{` of [source] with its matching `}`. */
private fun braces(source: String): Map<Int, Int> {
    val open = ArrayDeque<Int>()
    val pairs = mutableMapOf<Int, Int>()
    source.forEachIndexed { i, c ->
        when (c) {
            '{' -> open.addLast(i)
            '}' -> open.removeLastOrNull()?.let { pairs[it] = i }
        }
    }
    return pairs
}

/** The `)` that closes the `(` at [open]. */
private fun closing(source: String, open: Int): Int {
    var depth = 1
    var i = open + 1
    while (depth > 0 && i < source.length) {
        when (source[i]) {
            '(' -> depth++
            ')' -> depth--
        }
        i++
    }
    return i - 1
}

/** The `)` that closes the parameter list [at] stands in. */
private fun closingOfOpen(source: String, at: Int): Int {
    var depth = 0
    var i = at + 1
    while (i < source.length) {
        when (source[i]) {
            '(' -> depth++
            ')' -> if (depth == 0) return i else depth--
        }
        i++
    }
    return i
}

/** A call's argument: its name if it is named, and its value. */
private class RvArgument(val name: String?, val value: String)

/** The top-level arguments of a call's [inside]. */
private fun arguments(inside: String): List<RvArgument> {
    val parts = mutableListOf<String>()
    var depth = 0
    var from = 0
    inside.forEachIndexed { i, c ->
        when (c) {
            '(', '[', '{' -> depth++

            ')', ']', '}' -> depth--

            ',' -> if (depth == 0) {
                parts += inside.substring(from, i)
                from = i + 1
            }
        }
    }
    parts += inside.substring(from)
    return parts.map { part ->
        val named = NAMED.find(part)
        if (named != null) RvArgument(named.groupValues[1], part.substring(named.range.last + 1)) else RvArgument(null, part)
    }
}

private val NAMED = Regex("""^\s*(\w+)\s*=(?!=)""")

private const val CALL = 120
