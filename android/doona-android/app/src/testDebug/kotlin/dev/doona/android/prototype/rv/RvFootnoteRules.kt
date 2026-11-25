package dev.doona.android.prototype.rv

import androidx.compose.ui.test.junit4.ComposeContentTestRule
import dev.doona.android.designsystem.rv.RvPart
import dev.doona.android.designsystem.rv.RvSpace
import java.io.File
import kotlin.math.abs

// The footnote check of every golden: each footnote's box is the text column of the card above it, on both sides.

/** One footnote as measured: its box's start and end, and those of the text column of the card above it, in dp. */
internal class RvFootnoteRead(val text: String, val start: Float, val end: Float, val wantStart: Float, val wantEnd: Float) {
    val off get() = abs(start - wantStart) > TOLERANCE || abs(end - wantEnd) > TOLERANCE

    fun line(golden: String) = listOf(golden, text.replace(Regex("\\s+"), " ").take(LABEL), dp(start), dp(end), dp(wantStart), dp(wantEnd)).joinToString("\t")
}

/**
 * The footnotes ([RvPart.FOOTER]) of the tree [nodes]: each measured against the card above it, the nearest card that
 * ends over it and shares its width. Its box is that card's text column, [RvSpace.rowPadding] in from the card's start
 * and from its end (LAYOUT-RULES.md R20, owner q-s1125c-footgap 19:05: both sides at 32 dp), whatever the footnote
 * tokens say, so a box that keeps to the tokens but not to the column (the 32..396 of 811cda31) fails; a footnote with
 * no card above it, against the page's gutter.
 */
internal fun rvFootnotes(nodes: List<RvNode>): List<RvFootnoteRead> {
    val root = nodes.first().box
    val cards = nodes.filter { it.tag == RvPart.CARD }
    // A footnote scrolled out of the window has no box to measure.
    return nodes.filter { it.tag == RvPart.FOOTER && it.box.width > 0f && it.box.height > 0f }.map { f ->
        val card = cards.filter { it.box.bottom <= f.box.top + 1f && it.box.left < f.box.right && it.box.right > f.box.left }.maxByOrNull { it.box.bottom }
        val left = card?.box?.left ?: (root.left + RvSpace.gutter.value)
        val right = card?.box?.right ?: (root.right - RvSpace.gutter.value)
        RvFootnoteRead(f.text.orEmpty(), f.box.left, f.box.right, left + RvSpace.rowPadding.value, right - RvSpace.rowPadding.value)
    }
}

/**
 * The check for golden [name]: every footnote's box is its card's text column, within half a dp, or the golden fails.
 * What it measured goes to `build/footnotes/<name>.txt` (golden, text, start, end, wanted start, wanted end).
 */
internal fun ComposeContentTestRule.rvFootnoteRules(name: String) {
    val read = rvFootnotes(rvRowNodes())
    if (read.isEmpty()) return
    File("build/footnotes").apply { mkdirs() }.resolve("$name.txt").writeText(read.joinToString("") { it.line(name) + "\n" })
    val off = read.filter { it.off }
    if (off.isEmpty()) return
    throw AssertionError(
        "$name: a footnote's box is not the card's text column (LAYOUT-RULES.md R20; text, start, end, wanted start, wanted end):\n" +
            off.joinToString("") { "  ${it.line(name)}\n" },
    )
}

private fun dp(v: Float) = "%.1f".format(java.util.Locale.ROOT, v)

private const val TOLERANCE = 0.5f
private const val LABEL = 24
