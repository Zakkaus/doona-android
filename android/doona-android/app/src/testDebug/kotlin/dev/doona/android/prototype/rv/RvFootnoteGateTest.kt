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
 * name or through a local `val` of the file that holds it (`val secondary = Rv.colors.textSecondary`).
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

    private companion object {
        val PAGES = listOf("src/debug/kotlin/dev/doona/android/prototype/rv", "src/main/kotlin/dev/doona/android/feature")
        const val ALLOWLIST = "src/testDebug/kotlin/dev/doona/android/prototype/rv/rv-footnote-gate.txt"
    }
}

/** The calls of [file] that draw secondary text in the footnote's role: `path<TAB>call`, the call's words on one line. */
internal fun rvFootnoteLike(file: File): List<String> {
    val source = file.readText()
    // The file's own names for the secondary colour: a `val` whose value holds it.
    val names = Regex("""\bval\s+(\w+)\s*=[^\n]*\btextSecondary\b""").findAll(source).map { Regex("""\b${it.groupValues[1]}\b""") }.toList()
    val calls = Regex("""\b(RvText|Text|BasicText)\(""").findAll(source).mapNotNull { m ->
        var depth = 1
        var i = m.range.last + 1
        while (depth > 0 && i < source.length) {
            when (source[i]) {
                '(' -> depth++
                ')' -> depth--
            }
            i++
        }
        val call = source.substring(m.range.first, i).split(Regex("\\s+")).joinToString(" ")
        val secondary = "textSecondary" in call || names.any { it.containsMatchIn(call) }
        val role = "type.rowSub" in call || m.groupValues[1] != "RvText"
        call.takeIf { secondary && role }
    }
    val path = file.path.substringAfter("kotlin/dev/doona/android/")
    return calls.map { "$path\t${it.take(CALL)}" }.toList()
}

private const val CALL = 120
