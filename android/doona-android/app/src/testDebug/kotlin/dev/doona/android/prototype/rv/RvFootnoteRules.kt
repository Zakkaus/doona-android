package dev.doona.android.prototype.rv

import androidx.compose.ui.test.junit4.ComposeContentTestRule
import dev.doona.android.designsystem.rv.RvPart
import dev.doona.android.designsystem.rv.RvSpace
import java.io.File
import kotlin.math.abs

// The footnote check of every golden: each footnote's box is the text column of the card above it, on both sides.

/**
 * One footnote as measured: its box's start and end, and those of the text column of the card above it, in dp; its
 * box's top under that card's bottom ([above], null with no card above it); and whether its last line is cut
 * ([RvFootnote][dev.doona.android.designsystem.rv.RvFootnote] holds three lines).
 */
internal class RvFootnoteRead(val text: String, val start: Float, val end: Float, val wantStart: Float, val wantEnd: Float, val cut: Boolean = false, val above: Float? = null) {
    /** Off the card's text column on either side, or not [RV_FOOTNOTE_ABOVE] under the card (R20). */
    val off get() = abs(start - wantStart) > TOLERANCE || abs(end - wantEnd) > TOLERANCE || above?.let { abs(it - RV_FOOTNOTE_ABOVE) > TOLERANCE } == true

    fun line(golden: String) = listOf(golden, text.replace(Regex("\\s+"), " ").take(LABEL), dp(start), dp(end), dp(wantStart), dp(wantEnd), above?.let(::dp) ?: "-", dp(RV_FOOTNOTE_ABOVE)).joinToString("\t")
}

/** R20's gap from the card's bottom to its footnote, in dp: the rule's value, not the token, so a token change fails. */
internal const val RV_FOOTNOTE_ABOVE = 12f

/**
 * The footnotes ([RvPart.FOOTER]) of the tree [nodes]: each measured against the card above it, the nearest card that
 * ends over it and shares its width. Its box is that card's text column, [RvSpace.rowPadding] in from the card's start
 * and from its end (LAYOUT-RULES.md R20, owner q-s1125c-footgap 19:05: both sides at 32 dp), whatever the footnote
 * tokens say, so a box that keeps to the tokens but not to the column (the 32..396 of 811cda31) fails; a footnote with
 * no card above it, against the page's gutter. Its top stands [RV_FOOTNOTE_ABOVE] under that card's bottom. A footnote
 * whose card is not wholly in the window is skipped: the card's box would be its clipped part.
 */
internal fun rvFootnotes(nodes: List<RvNode>): List<RvFootnoteRead> {
    val root = nodes.first().box
    val cards = nodes.filter { it.tag == RvPart.CARD }
    // A footnote scrolled out of the window has no box to measure; one whose card the window cuts has no card to
    // measure against (the card's box is only its part in the window).
    return nodes.filter { it.tag == RvPart.FOOTER && it.box.width > 0f && it.box.height > 0f }.mapNotNull { f ->
        val card = cards.filter { it.box.bottom <= f.box.top + 1f && it.box.left < f.box.right && it.box.right > f.box.left }.maxByOrNull { it.box.bottom }
        if (card?.cut == true) return@mapNotNull null
        val left = card?.box?.left ?: (root.left + RvSpace.gutter.value)
        val right = card?.box?.right ?: (root.right - RvSpace.gutter.value)
        RvFootnoteRead(f.text.orEmpty(), f.box.left, f.box.right, left + RvSpace.rowPadding.value, right - RvSpace.rowPadding.value, f.line?.cut == true, card?.let { f.box.top - it.box.bottom })
    }
}

/**
 * The check for golden [name]: every footnote's box is its card's text column and stands 12 dp under the card, within
 * half a dp, and no footnote is
 * cut (its last line ellipsized, `isLineEllipsized`, or past its box: a footnote takes three lines at most, so a longer
 * one would lose its end), or the golden fails. What it measured goes to `build/footnotes/<name>.txt` (golden, text,
 * start, end, wanted start, wanted end, gap above, wanted gap).
 */
internal fun ComposeContentTestRule.rvFootnoteRules(name: String) {
    val read = rvFootnotes(rvRowNodes())
    if (read.isEmpty()) return
    File("build/footnotes").apply { mkdirs() }.resolve("$name.txt").writeText(read.joinToString("") { it.line(name) + "\n" })
    val off = read.filter { it.off }
    val cut = read.filter { it.cut }
    if (off.isEmpty() && cut.isEmpty()) return
    val where = if (off.isEmpty()) "" else "$name: a footnote's box is not the card's text column 12 dp under it (LAYOUT-RULES.md R20; text, start, end, wanted start, wanted end, gap above, wanted gap):\n" + off.joinToString("") { "  ${it.line(name)}\n" }
    val lost = if (cut.isEmpty()) "" else "$name: a footnote is cut at its third line (LAYOUT-RULES.md R20):\n" + cut.joinToString("") { "  ${it.line(name)}\n" }
    throw AssertionError(where + lost)
}

private fun dp(v: Float) = "%.1f".format(java.util.Locale.ROOT, v)

private const val TOLERANCE = 0.5f
private const val LABEL = 24
