package dev.doona.android.prototype.rv

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.text.TextLayoutResult
import dev.doona.android.designsystem.rv.RvPart
import dev.doona.android.designsystem.rv.RvRowPart
import java.io.File
import kotlin.math.abs

// The row rules gate: every golden's rows measured against docs/LAYOUT-RULES.md, its breaks held to the allowlist.

/** One part of a golden's unmerged semantics tree: its bounds in dp, test tag, text and the text's first line. */
internal class RvNode(val parent: Int, val box: Rect, val tag: String?, val text: String?, val line: RvFirstLine?) {
    val kids = mutableListOf<Int>()
}

/** A text's first line, from the text's top, in dp, and how many lines it takes. */
internal class RvFirstLine(val top: Float, val bottom: Float, val lines: Int)

/** The kinds of row the rules know (LAYOUT-RULES.md, "Row kinds"). */
internal enum class RvRowKind { List, Settings, Notice, Menu }

/** A row as the gate reads it: its bounds, leading visual, title, subtitles, trailing part (their union) and text column x. */
internal class RvRowRead(
    val kind: RvRowKind,
    val row: Rect,
    val lead: RvNode?,
    val title: RvNode,
    val subtitles: List<RvNode>,
    val end: Rect?,
    val endTags: Set<String>,
    val card: Rect?,
) {
    val textLeft get() = title.box.left
    val oneLine get() = subtitles.isEmpty() && (title.line?.lines ?: 1) == 1

    /** The text block: the title's top to the last subtitle's bottom (or the title's bottom). */
    val textTop get() = title.box.top
    val textBottom get() = (subtitles.lastOrNull() ?: title).box.bottom
}

/** A rule a row breaks: the rule's id (LAYOUT-RULES.md), the row's title and what was measured. */
internal data class RvRowBreak(val rule: String, val row: String, val measured: String)

/** The golden's semantics tree, unmerged, as [RvNode]s in dp; the root is the first. */
internal fun ComposeContentTestRule.rvRowNodes(): List<RvNode> {
    val root = onRoot(useUnmergedTree = true).fetchSemanticsNode()
    val density = root.layoutInfo.density.density
    val out = mutableListOf<RvNode>()
    fun walk(n: SemanticsNode, parent: Int) {
        val b = n.boundsInRoot
        val text = n.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }
        val node = RvNode(parent, Rect(b.left / density, b.top / density, b.right / density, b.bottom / density), n.config.getOrNull(SemanticsProperties.TestTag), text, text?.let { n.firstLine(density) })
        val at = out.size
        out += node
        if (parent >= 0) out[parent].kids += at
        n.children.forEach { walk(it, at) }
    }
    walk(root, -1)
    return out
}

private fun SemanticsNode.firstLine(density: Float): RvFirstLine? {
    val layouts = mutableListOf<TextLayoutResult>()
    val get = config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action ?: return null
    val layout = layouts.takeIf { get(it) }?.firstOrNull()?.takeIf { it.lineCount > 0 } ?: return null
    return RvFirstLine(layout.getLineTop(0) / density, layout.getLineBottom(0) / density, layout.lineCount)
}

/** Every row of the tree: the library's rows ([RvPart.ROW]) and the row-like parts it tags (a notice, a menu item). */
internal fun rvRowsOf(nodes: List<RvNode>): List<RvRowRead> = nodes.indices.mapNotNull { i ->
    val kind = when (nodes[i].tag) {
        RvPart.ROW -> if (nodes.subtree(i).any { nodes[it].tag == RvRowPart.SETTINGS_ROW }) RvRowKind.Settings else RvRowKind.List
        RvRowPart.NOTICE_ROW -> RvRowKind.Notice
        RvRowPart.MENU_ROW -> RvRowKind.Menu
        else -> null
    }
    kind?.let { nodes.readRow(i, it) }
}

private val LEADS = setOf(RvPart.DISC, RvRowPart.ICON_DISC, RvRowPart.FLAG, RvRowPart.APP_DISC, RvRowPart.GLYPH)

/** The 40 dp leading visuals of the iconstyle rule (R4); a bare glyph is not one. */
private val DISCS = setOf(RvPart.DISC, RvRowPart.ICON_DISC, RvRowPart.FLAG, RvRowPart.APP_DISC)

private fun List<RvNode>.subtree(i: Int): List<Int> {
    val out = mutableListOf<Int>()
    val stack = ArrayDeque(listOf(i))
    while (stack.isNotEmpty()) stack.removeLast().let {
        out += it
        stack.addAll(this[it].kids)
    }
    return out
}

private fun RvNode.shown() = box.width > 0f && box.height > 0f

/**
 * Row [i] read: its leading visual is the leftmost outermost lead-tagged part left of every text in the row; the text
 * column starts at the leftmost text after it; the title is the column's top text, the subtitles the column's texts
 * under it; the trailing part is every outermost shown part right of the column that holds no column text. A row
 * without text (a skeleton) is not read.
 */
private fun List<RvNode>.readRow(i: Int, kind: RvRowKind): RvRowRead? {
    val inRow = subtree(i).drop(1)
    val texts = inRow.filter { this[it].text != null && this[it].shown() }
    if (texts.isEmpty()) return null
    val firstText = texts.minOf { this[it].box.left }
    val lead = leadOf(inRow).firstOrNull { this[it].box.right <= firstText + HALF }
    // A row cut by its window (a scrolled page, a sheet's edge) is not a whole row: its disc is a sliver.
    if (lead != null && this[lead].box.let { minOf(it.width, it.height) < DISC - HALF && maxOf(it.width, it.height) > DISC - HALF }) return null
    val from = lead?.let { this[it].box.right - HALF } ?: this[i].box.left
    val after = texts.filter { this[it].box.left >= from }.map { this[it] }
    if (after.isEmpty()) return null
    val left = after.minOf { it.box.left }
    val column = after.filter { abs(it.box.left - left) <= TOLERANCE }.sortedBy { it.box.top }
    val title = column.first()
    val subtitles = column.drop(1).filter { it.box.top >= title.box.bottom - TOLERANCE }
    val leadParts = lead?.let { subtree(it).toSet() }.orEmpty()
    val end = endOf(inRow, column, leadParts)
    val tags = end?.let { r -> inRow.filter { this[it].tag != null && this[it].box.left >= r.left - HALF && this[it].box.right <= r.right + HALF }.mapNotNull { this[it].tag }.toSet() }.orEmpty()
    return RvRowRead(kind, this[i].box, lead?.let { this[it] }, title, subtitles, end, tags, cardOf(i))
}

/** The bounds of the card [i] stands in, if it stands in one. */
private fun List<RvNode>.cardOf(i: Int): Rect? {
    var at = this[i].parent
    while (at >= 0) {
        if (this[at].tag == RvPart.CARD) return this[at].box
        at = this[at].parent
    }
    return null
}

/** The row's outermost lead-tagged parts, leftmost first. */
private fun List<RvNode>.leadOf(inRow: List<Int>): List<Int> {
    val tagged = inRow.filter { this[it].tag in LEADS && this[it].shown() }
    val inner = tagged.flatMap { subtree(it).drop(1) }.toSet()
    return tagged.filterNot { it in inner }.sortedBy { this[it].box.left }
}

/** The union of the row's outermost shown parts right of its text [column] that hold no column text. */
private fun List<RvNode>.endOf(inRow: List<Int>, column: List<RvNode>, lead: Set<Int>): Rect? {
    val right = column.maxOf { it.box.right }
    val holders = inRow.filter { j -> subtree(j).any { this[it] in column } }.toSet()
    val parts = inRow.filter { it !in lead && it !in holders && this[it] !in column && this[it].shown() && this[it].box.left >= right - HALF }
    val inner = parts.flatMap { subtree(it).drop(1) }.toSet()
    return parts.filterNot { it in inner }.map { this[it].box }.reduceOrNull { a, b -> Rect(minOf(a.left, b.left), minOf(a.top, b.top), maxOf(a.right, b.right), maxOf(a.bottom, b.bottom)) }
}

/** The rules every row of [rows] breaks, in the rows' order, then the card columns' (R7) and the line boxes' (R13). */
internal fun rvRowBreaks(rows: List<RvRowRead>): List<RvRowBreak> {
    val sorted = rows.sortedWith(compareBy({ it.row.top }, { it.row.left }))
    return sorted.flatMap { it.breaks() } + rvColumnBreaks(sorted) + rvLineBoxBreaks(sorted)
}

private fun RvRowRead.breaks(): List<RvRowBreak> =
    when (kind) {
        RvRowKind.Menu -> listOfNotNull(leadY())

        // A notice's row is its disc and text inside the card's padding: no row height, padding or trailing part of its own.
        RvRowKind.Notice -> listOfNotNull(leadKind(), textX(), leadY(), subtitleGap(), lines())

        else -> listOfNotNull(leadKind(), leadX(), textX(), leadY(), endY(), endX(), textToEnd(), subtitleGap(), lines(), padding(), height())
    }

/** R4: a row's leading visual is the 40 dp disc, flag or app disc (owner, iconstyle). */
private fun RvRowRead.leadKind(): RvRowBreak? {
    val lead = lead ?: return null
    val disc = lead.tag in DISCS && abs(lead.box.width - DISC) <= HALF && abs(lead.box.height - DISC) <= HALF
    return if (disc) null else broken("R4", "${lead.tag} ${dp(lead.box.width)}x${dp(lead.box.height)}")
}

/** R5: the leading visual starts 16 dp inside the row (a row's box is the card's width). */
private fun RvRowRead.leadX(): RvRowBreak? {
    val lead = lead ?: return null
    return off("R5", lead.box.left - row.left - INSET)
}

/** R6: the text column starts 16 dp after the leading visual, or 16 dp inside a row without one. */
private fun RvRowRead.textX(): RvRowBreak? = off("R6", textLeft - (lead?.box?.right?.plus(GAP) ?: (row.left + INSET)))

/** R8 and R9: one line, the leading visual centred on the row; with a subtitle, its top on the title's top. */
private fun RvRowRead.leadY(): RvRowBreak? {
    val lead = lead ?: return null
    return when {
        subtitles.isNotEmpty() -> off("R9", lead.box.top - title.box.top)
        oneLine -> off("R8", lead.box.center.y - row.center.y)
        else -> null
    }
}

/**
 * R10 and R11: one line, the trailing part centred on the row; with a subtitle, a settings row's on the title's first
 * line (its box at least a one-line row's 26 dp), or its top on the title's top where it is as tall as the disc (an
 * in-card button). A list row's trailing part with a subtitle waits for the owner (LAYOUT-RULES.md, R11).
 */
private fun RvRowRead.endY(): RvRowBreak? {
    val end = end ?: return null
    val control = endTags.any { it in CONTROLS }
    return when {
        oneLine -> off("R10", end.center.y - row.center.y)
        subtitles.isEmpty() -> null
        end.height >= TALL_END -> off("R11", end.top - title.box.top)
        control || kind == RvRowKind.Settings -> off("R11", end.center.y - (title.box.top + maxOf(FIRST_LINE, title.firstLineHeight()) / 2))
        else -> off("R11l", end.center.y - row.center.y)
    }
}

/** R16: the trailing part ends at the end of the row's content, 16 dp inside the card. */
private fun RvRowRead.endX(): RvRowBreak? = end?.let { off("R16", it.right - (row.right - INSET)) }

/** R17: the trailing part keeps 12 dp from the text it follows. */
private fun RvRowRead.textToEnd(): RvRowBreak? {
    val end = end ?: return null
    val band = (listOf(title) + subtitles).filter { it.box.bottom > end.top + HALF && it.box.top < end.bottom - HALF }
    val right = band.maxOfOrNull { it.box.right } ?: return null
    return if (end.left - right < GAP_TRAILING - TOLERANCE) broken("R17", signed(end.left - right - GAP_TRAILING)) else null
}

/** R14: a title takes up to two lines, a subtitle up to three. */
private fun RvRowRead.lines(): RvRowBreak? {
    val t = title.line?.lines ?: 1
    val s = subtitles.maxOfOrNull { it.line?.lines ?: 1 } ?: 1
    return when {
        t > TITLE_LINES -> broken("R14", "title $t lines")
        s > SUBTITLE_LINES -> broken("R14", "subtitle $s lines")
        else -> null
    }
}

/** R18: the text keeps its row's padding above and below (16 dp or more, equal unless the row's minimum height is the larger). */
private fun RvRowRead.padding(): RvRowBreak? {
    if (end != null && end.height > textBottom - textTop + 2 * INSET) return null
    val above = textTop - row.top
    val below = row.bottom - maxOf(textBottom, end?.takeIf { it.top >= title.box.bottom - TOLERANCE }?.bottom ?: textBottom)
    return when {
        above < INSET - TOLERANCE -> broken("R18", "above ${dp(above)}")
        below < INSET - TOLERANCE -> broken("R18", "below ${dp(below)}")
        abs(above - below) > TOLERANCE -> broken("R18", "above ${dp(above)} below ${dp(below)}")
        else -> null
    }
}

/** R1: a row is as tall as its text and padding need, and not less than 58 dp (one line) or 82 dp (with a subtitle) at 1.0. */
private fun RvRowRead.height(): RvRowBreak? {
    val least = if (subtitles.isEmpty()) ONE_LINE else TWO_LINE
    val below = end?.takeIf { it.top >= title.box.bottom - TOLERANCE }?.bottom ?: textBottom
    val need = maxOf(maxOf(textBottom, below) - textTop + 2 * INSET, end?.height ?: 0f)
    return when {
        row.height < least - TOLERANCE -> broken("R1", "height ${dp(row.height)}")
        row.height > maxOf(least, need) + TOLERANCE -> broken("R1", "height ${dp(row.height)} for ${dp(maxOf(least, need))}")
        else -> null
    }
}

private fun RvNode.firstLineHeight() = line?.let { it.bottom - it.top } ?: box.height

/** R12: the subtitle starts where the title ends. */
private fun RvRowRead.subtitleGap(): RvRowBreak? = subtitles.firstOrNull()?.let { off("R12", it.box.top - title.box.bottom) }

/** R7: in one card (list or settings rows stacked edge to edge), rows with the same leading visual start their text at one x. */
internal fun rvColumnBreaks(rows: List<RvRowRead>): List<RvRowBreak> {
    val cards = mutableListOf<MutableList<RvRowRead>>()
    for (r in rows.filter { it.kind == RvRowKind.List || it.kind == RvRowKind.Settings }) {
        val card = cards.lastOrNull { c -> c.last().let { abs(it.row.left - r.row.left) <= HALF && abs(it.row.right - r.row.right) <= HALF && abs(r.row.top - it.row.bottom) <= TOLERANCE } }
        if (card != null) card += r else cards += mutableListOf(r)
    }
    return cards.flatMap { card ->
        card.groupBy { "${it.lead?.tag} ${it.lead?.box?.width?.let(::dp)}" }.values.flatMap { same ->
            val x = same.groupingBy { dp(it.textLeft) }.eachCount().maxBy { it.value }.key
            same.filter { dp(it.textLeft) != x }.map { it.broken("R7", "x ${dp(it.textLeft)} vs $x") }
        }
    }
}

/**
 * R13: in one golden, every one-line row title takes one line box, and every one-line subtitle another, whatever its
 * script (owner, latinrow); a code title keeps its own.
 */
internal fun rvLineBoxBreaks(rows: List<RvRowRead>): List<RvRowBreak> {
    val lists = rows.filter { it.kind == RvRowKind.List || it.kind == RvRowKind.Settings }
    val titles = lists.filter { (it.title.line?.lines ?: 1) == 1 }.map { it to it.title }
    val subs = lists.mapNotNull { r -> r.subtitles.firstOrNull()?.takeIf { (it.line?.lines ?: 1) == 1 }?.let { r to it } }
    return listOf("title" to titles, "subtitle" to subs).flatMap { (what, texts) ->
        val usual = texts.groupingBy { dp(it.second.box.height) }.eachCount().maxByOrNull { it.value }?.key
        texts.filter { dp(it.second.box.height) != usual }.map { (r, t) -> r.broken("R13", "$what ${dp(t.box.height)} vs $usual") }
    }
}

private fun RvRowRead.off(rule: String, by: Float): RvRowBreak? = if (abs(by) > TOLERANCE) broken(rule, signed(by)) else null

private fun RvRowRead.broken(rule: String, measured: String) = RvRowBreak(rule, label(title.text.orEmpty()), measured)

/** One row as the sheets draw it: kind, row box, lead box, title box, subtitle box, trailing box (dp), tab-separated. */
private fun RvRowRead.measured(): String {
    fun box(r: Rect?) = r?.let { "${dp(it.left)},${dp(it.top)},${dp(it.right)},${dp(it.bottom)}" } ?: "-"
    val sub = subtitles.firstOrNull()?.box?.let { Rect(it.left, it.top, it.right, subtitles.last().box.bottom) }
    return listOf(kind.name, box(row), box(lead?.box), box(title.box), box(sub), box(end), label(title.text.orEmpty())).joinToString("\t")
}

private fun label(text: String) = text.replace(Regex("\\s+"), " ").trim().take(LABEL)

private fun dp(v: Float) = "%.1f".format(java.util.Locale.ROOT, v)

private fun signed(v: Float) = (if (v >= 0f) "+" else "") + dp(v)

/** The breaks of golden [name] as allowlist lines: golden, rule, row and measured value, tab-separated; a repeat numbered. */
internal fun rvBreakLines(name: String, breaks: List<RvRowBreak>): List<String> {
    val seen = mutableMapOf<String, Int>()
    return breaks.map { b ->
        val key = "${b.rule}\t${b.row}"
        val n = seen.merge(key, 1, Int::plus)!!
        "$name\t${b.rule}\t${b.row}${if (n > 1) " #$n" else ""}\t${b.measured}"
    }
}

/** The allowlist next to this file: today's breaks, each golden's lines; `#` starts a comment. */
internal const val RV_ROW_RULES_ALLOWLIST = "src/testDebug/kotlin/dev/doona/android/prototype/rv/rv-row-rules.txt"

private val allowed: Map<String, Set<String>> by lazy {
    File(RV_ROW_RULES_ALLOWLIST).readLines().filter { it.isNotBlank() && !it.startsWith("#") }.groupBy { it.substringBefore('\t') }.mapValues { it.value.toSet() }
}

/**
 * The gate for golden [name]: its rows' breaks must be exactly its allowlist lines, so a new break fails and so does a
 * listed one that no longer occurs (the list only shrinks, as `limits-ratchet.txt`). The breaks found are written to
 * `build/rowrules/<name>.txt` either way; with `doona.rowrules=write` they are only written, to remake the list.
 */
internal fun ComposeContentTestRule.rvRowRules(name: String) {
    val rows = rvRowsOf(rvRowNodes())
    val lines = rvBreakLines(name, rvRowBreaks(rows))
    File("build/rowrules").apply { mkdirs() }.resolve("$name.txt").writeText(lines.joinToString("") { "$it\n" })
    File("build/rowrules").resolve("$name.rows.tsv").writeText(rows.joinToString("") { it.measured() + "\n" })
    if (System.getProperty("doona.rowrules") == "write") return
    val listed = allowed[name].orEmpty()
    val new = lines.filterNot { it in listed }
    val gone = listed.filterNot { it in lines }
    if (new.isEmpty() && gone.isEmpty()) return
    throw AssertionError(
        "$name breaks the row rules (android/doona-android/docs/LAYOUT-RULES.md; golden, rule, row, measured):\n" +
            new.joinToString("") { "  new: $it\n" } + gone.joinToString("") { "  no longer occurs, drop it from rv-row-rules.txt: $it\n" },
    )
}

private const val TOLERANCE = 1f
private const val HALF = 0.5f
private const val DISC = 40f
private const val INSET = 16f
private const val GAP = 16f
private const val FIRST_LINE = 26f
private const val TALL_END = 36f
private const val LABEL = 24
private const val GAP_TRAILING = 12f
private const val TITLE_LINES = 2
private const val SUBTITLE_LINES = 3
private const val ONE_LINE = 58f
private const val TWO_LINE = 82f

/** The trailing parts that act: they sit on the title's first line in every row with a subtitle (LAYOUT-RULES.md, R11). */
private val CONTROLS = setOf(RvRowPart.SWITCH, RvRowPart.RADIO, RvRowPart.CHECK)
