package dev.doona.android.prototype.rv

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * A page never places a footnote itself: [dev.doona.android.designsystem.rv.RvFootnote] takes its inset from the
 * column it sits in ([dev.doona.android.designsystem.rv.RvSettingsColumn]), so no `RvFootnote(…)` call in a page file
 * passes `inset`. The samples under `prototype/rv/samples` are the approved images as drawn and are left as they are.
 */
class RvFootnoteInsetGateTest {
    @Test
    fun noPagePassesAFootnoteInset() {
        val found = PAGES.flatMap { root ->
            File(root).walkTopDown().filter { it.extension == "kt" && !it.path.contains("/samples/") }.flatMap { insetFootnotes(it) }
        }
        assertEquals("RvFootnote calls that pass an inset", emptyList<String>(), found)
    }

    @Test
    fun aPlantedInsetIsCaught() {
        val plant = File.createTempFile("RvPlant", ".kt").apply {
            writeText("@Composable\nfun Plant() {\n    RvFootnote(\n        stringResource(R.string.note),\n        inset = RvSpace.none,\n    )\n}\n")
            deleteOnExit()
        }
        assertEquals(1, insetFootnotes(plant).size)
    }

    @Test
    fun aFootnoteWithoutAnInsetPasses() {
        val plant = File.createTempFile("RvPlant", ".kt").apply {
            writeText("@Composable\nfun Plant() {\n    RvFootnote(stringResource(R.string.note, f(1)), whole = true)\n}\n")
            deleteOnExit()
        }
        assertEquals(0, insetFootnotes(plant).size)
    }

    /** `file:line` of each `RvFootnote(` call whose arguments name `inset`, read to its closing parenthesis. */
    private fun insetFootnotes(file: File): List<String> {
        val text = file.readText()
        return CALL.findAll(text).mapNotNull { call ->
            var depth = 1
            var at = call.range.last + 1
            while (at < text.length && depth > 0) {
                when (text[at]) {
                    '(' -> depth++
                    ')' -> depth--
                }
                at++
            }
            val args = text.substring(call.range.last + 1, at)
            if (INSET.containsMatchIn(args)) "${file.name}:${text.take(call.range.first).count { it == '\n' } + 1}" else null
        }.toList()
    }

    private companion object {
        val PAGES = listOf("src/main/kotlin/dev/doona/android/feature", "src/debug/kotlin/dev/doona/android/prototype")
        val CALL = Regex("""\bRvFootnote\(""")
        val INSET = Regex("""\binset\s*=""")
    }
}
