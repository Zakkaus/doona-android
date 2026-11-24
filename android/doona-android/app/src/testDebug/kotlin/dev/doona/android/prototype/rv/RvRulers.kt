package dev.doona.android.prototype.rv

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.toSize
import dev.doona.android.designsystem.rv.RvChartSize
import dev.doona.android.designsystem.rv.RvChrome
import dev.doona.android.designsystem.rv.RvPart
import dev.doona.android.designsystem.rv.RvSize
import dev.doona.android.designsystem.rv.RvSpace
import dev.doona.android.designsystem.rv.rvBarHeight
import dev.doona.android.testing.StatusBar
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * How a page starts under the bar: a large title, a centred header (a detail page's, as a block) where a large title
 * would start, its own hero (not measured), or a tab home's first content, [RvSpace.searchGap] under its bar's search
 * ([RvChrome.tabBarTop]).
 */
internal enum class Top { Title, Header, Hero, Tab }

/** What a ruler measures: an edge against a keyline, the gap between two blocks, a part's height, or the header's top. */
internal enum class RvRuleKind { Edge, Gap, Height, Top }

/**
 * One measurement: [what] was measured, its [actual] value in dp and the [expected] one, or null where the tokens set
 * none (two kinds of block that have other content between them, a row grown by its wrapping text). [at] is the part
 * measured; for a gap, the strip between the two blocks across the width they share. A gap is [token] when it equals
 * one of [RvSpace]'s values.
 */
internal class RvRule(val kind: RvRuleKind, val what: String, val actual: Float, val expected: Float?, val at: Rect) {
    val off get() = expected != null && abs(actual - expected) > RV_TOLERANCE
    val token get() = rvSpacingTokens.any { abs(actual - it) <= RV_TOLERANCE }
}

/** The library's rulers allow half a dp: the layout rounds to whole pixels at 2 px per dp. */
internal const val RV_TOLERANCE = 0.5f

/** The width of the phone the pages are measured on. */
internal const val RV_SCREEN = 412f

/** Every spacing token's value in dp, [RvSpace]'s fields, read once. */
internal val rvSpacingTokens: List<Float> by lazy {
    RvSpace::class.java.declaredFields.filter { it.type == java.lang.Float.TYPE }.map { field ->
        field.isAccessible = true
        field.getFloat(RvSpace)
    }.distinct().sorted()
}

/** The tags of the library's layout parts, the ones the rulers measure. */
internal val RV_PART_TAGS = listOf(
    RvPart.MARK, RvPart.TITLE, RvPart.CAPTION, RvPart.CONTROL, RvPart.SEARCH, RvPart.CARD, RvPart.SECTION, RvPart.FOOTER, RvPart.BLOCK, RvPart.ROW,
    RvPart.SEGMENTED,
)

/** The bottom of the bar in the phone's frame, in dp: a tab home's bar sits higher than a pushed page's. */
internal fun rvBarBottom(top: Top): Float = StatusBar.value + rvBarHeight(if (top == Top.Tab) RvChrome.tabBarTop else RvChrome.barTop).value

/** The library's parts on the page by tag, in dp, unclipped (the scroll's viewport would clip boundsInRoot to the window). */
internal fun ComposeContentTestRule.rvParts(): Map<String, List<Rect>> =
    // The merged tree: a picture of a page (主題's preview) clears its semantics, so its cards are not the page's.
    RV_PART_TAGS.associateWith { tag ->
        onAllNodesWithTag(tag).fetchSemanticsNodes().map { node ->
            val d = node.layoutInfo.density.density
            Rect(node.positionInRoot / d, node.size.toSize() / d)
        }
    }

/**
 * One page's parts in dp, measured against the tokens (plans/reviews/spacing-audit.md): [run] returns the drifts, and
 * [rules] holds every measurement made, on the tokens or not, for the design board's redlines.
 */
internal class RvMeasure(private val parts: Map<String, List<Rect>>, private val barBottom: Float) {
    private val width = RV_SCREEN
    private val drift = mutableListOf<String>()
    private val measured = mutableListOf<RvRule>()
    private val cards = parts.getValue(RvPart.CARD).filter { it.left >= 0f && it.right <= width }
    private val controls = parts.getValue(RvPart.CONTROL)
    private val holders = controls + parts.getValue(RvPart.BLOCK)

    val rules: List<RvRule> get() = measured

    /** Measures the page; with [start] false (a scrolled window, its header gone) the header's top is not measured. */
    fun run(top: Top, start: Boolean = true): List<String> {
        if (start) top(top)
        column(top)
        rows()
        segmented()
        return drift
    }

    private fun top(top: Top) {
        // The title, or the mark above it, is the header's first part.
        val head = (parts.getValue(RvPart.MARK) + parts.getValue(RvPart.TITLE)).minByOrNull { it.top }
        val first = blocks().firstOrNull()?.second
        when (top) {
            Top.Title -> head?.let { expect(RvRuleKind.Top, "header's top", it.top, barBottom + RvChrome.largeTitleTop.value, it) } ?: drift.add("no large title")
            Top.Header -> first?.let { expect(RvRuleKind.Top, "header's top", it.top, barBottom + RvChrome.largeTitleTop.value, it) } ?: drift.add("no header")
            Top.Hero -> Unit
            Top.Tab -> first?.let { expect(RvRuleKind.Top, "tab's first content under the search", it.top, barBottom + RvSpace.searchGap.value, it) } ?: drift.add("no content")
        }
    }

    /**
     * The page's column: each block on the gutter, and the gap to the block under it by what the two are. A centred
     * header (a detail page's, measured as a block) is a header: what follows it keeps the header's gap.
     */
    private fun column(top: Top) {
        val blocks = blocks()
        val header = blocks.firstOrNull()?.takeIf { top == Top.Header }
        val end = width - GUTTER.value
        for ((kind, rect) in blocks) {
            when {
                // A pager runs edge to edge; its tiles keep to the gutter by its content padding.
                kind == RvPart.BLOCK -> Unit

                kind != RvPart.CARD -> edge("$kind's start at ${rect.top} dp", rect.left, GUTTER.value, rect)

                // A tile beside another keeps one edge on the gutter: the nearer one is measured.
                rect.width < width / 2 ->
                    if (abs(rect.left - GUTTER.value) <= abs(rect.right - end)) {
                        edge("tile's start at ${rect.top} dp", rect.left, GUTTER.value, rect)
                    } else {
                        edge("tile's end at ${rect.top} dp", rect.right, end, rect)
                    }

                else -> {
                    edge("card's start at ${rect.top} dp", rect.left, GUTTER.value, rect)
                    edge("card's end at ${rect.top} dp", rect.right, end, rect)
                }
            }
        }
        for ((upper, lower) in pairs(blocks)) {
            val kinds = upper.first.asCard() to lower.first.asCard()
            val gap = if (upper == header) RvSpace.headerGap else (TAB_GAPS[kinds]?.takeIf { top == Top.Tab } ?: GAPS[kinds])
            val (a, b) = upper.second to lower.second
            val strip = Rect(max(a.left, b.left), a.bottom, min(a.right, b.right), b.top)
            expect(RvRuleKind.Gap, "gap ${upper.first} to ${lower.first} at ${b.top} dp", b.top - a.bottom, gap?.value, strip)
        }
    }

    /** The column's top-level blocks, top first: a card inside a control or another card is part of it. */
    fun blocks(): List<Pair<String, Rect>> {
        val kinds = listOf(RvPart.MARK, RvPart.TITLE, RvPart.CAPTION, RvPart.CONTROL, RvPart.SEARCH, RvPart.SECTION, RvPart.FOOTER)
        val loose = kinds.flatMap { kind -> parts.getValue(kind).map { kind to it } }
        // A block is measured as a card; the cards in a block or a control are part of it.
        val outer = cards.filter { card -> (holders + cards).none { it != card && it.contains(card) } }.map { RvPart.CARD to it }
        val composite = parts.getValue(RvPart.BLOCK).map { RvPart.BLOCK to it }
        val segmented = parts.getValue(RvPart.SEGMENTED).filter { s -> (holders + cards).none { it.contains(s) } }.map { RvPart.SEGMENTED to it }
        return (loose + outer + composite + segmented).sortedBy { it.second.top }
    }

    /** Each block with the block straight under it in the same column (they overlap across). */
    private fun pairs(blocks: List<Pair<String, Rect>>): List<Pair<Pair<String, Rect>, Pair<String, Rect>>> = blocks.mapNotNull { lower ->
        blocks.filter { it.second.bottom <= lower.second.top + RV_TOLERANCE && it.second.overlapsAcross(lower.second) }.maxByOrNull { it.second.bottom }?.let { it to lower }
    }

    /** Rows run edge to edge in their card at 58 or 82 dp, or taller where their text wraps (the one row rule). */
    private fun rows() {
        for (row in parts.getValue(RvPart.ROW)) {
            val card = cards.firstOrNull { it.contains(row) } ?: continue
            val grown = row.height > RvSize.rowTwoLine.value + RV_TOLERANCE
            // A row off both heights is measured against the nearer one.
            val nearest = ROW_HEIGHTS.minBy { abs(row.height - it.value) }.value.takeUnless { grown }
            expect(RvRuleKind.Height, "row at ${row.top} dp", row.height, nearest, row)
            edge("row's start at ${row.top} dp", row.left, card.left, row)
            edge("row's end at ${row.top} dp", row.right, card.right, row)
        }
    }

    /** A segmented control is 40 dp tall (look E), on the gutter or 16 dp inside its card. */
    private fun segmented() {
        for (s in parts.getValue(RvPart.SEGMENTED)) {
            expect(RvRuleKind.Height, "segmented control's height", s.height, RvChartSize.segment.value, s)
            val card = cards.firstOrNull { it.contains(s) }
            edge("segmented control's start", s.left, card?.let { it.left + RvSpace.rowPadding.value } ?: GUTTER.value, s)
            edge("segmented control's end", s.right, card?.let { it.right - RvSpace.rowPadding.value } ?: (width - GUTTER.value), s)
        }
    }

    private fun String.asCard() = if (this == RvPart.BLOCK) RvPart.CARD else this

    private fun edge(what: String, actual: Float, expected: Float, at: Rect) = expect(RvRuleKind.Edge, what, actual, expected, at)

    /** Records the measurement; one off its token is a drift. */
    private fun expect(kind: RvRuleKind, what: String, actual: Float, expected: Float?, at: Rect) {
        val rule = RvRule(kind, what, actual, expected, at)
        measured += rule
        if (rule.off) drift.add("$what: $actual dp, the tokens say $expected dp")
    }

    private fun Rect.contains(other: Rect) =
        other.left >= left - RV_TOLERANCE && other.right <= right + RV_TOLERANCE && other.top >= top - RV_TOLERANCE && other.bottom <= bottom + RV_TOLERANCE

    private fun Rect.overlapsAcross(other: Rect) = left < other.right && other.left < right

    private companion object {
        val GUTTER = RvSpace.gutter
        val ROW_HEIGHTS = listOf(RvSize.rowOneLine, RvSize.rowTwoLine)

        /** The gap the tokens set between two kinds of block, upper to lower; pairs not listed have other content between. */
        val GAPS: Map<Pair<String, String>, Dp> = mapOf(
            (RvPart.MARK to RvPart.TITLE) to RvSpace.markToTitle,
            (RvPart.TITLE to RvPart.CAPTION) to RvSpace.captionGap,
            (RvPart.TITLE to RvPart.CONTROL) to RvSpace.controlGap,
            (RvPart.CAPTION to RvPart.CONTROL) to RvSpace.controlGap,
            (RvPart.TITLE to RvPart.CARD) to RvSpace.headerGap,
            (RvPart.CAPTION to RvPart.CARD) to RvSpace.headerGap,
            (RvPart.CONTROL to RvPart.CARD) to RvSpace.controlGap,
            (RvPart.TITLE to RvPart.SECTION) to RvSpace.headerGap,
            (RvPart.CAPTION to RvPart.SECTION) to RvSpace.headerGap,
            (RvPart.CONTROL to RvPart.SECTION) to RvSpace.controlGap,
            (RvPart.CARD to RvPart.CARD) to RvSpace.cardGap,
            (RvPart.CARD to RvPart.SECTION) to RvSpace.sectionAbove,
            (RvPart.CARD to RvPart.CONTROL) to RvSpace.controlGap,
            (RvPart.CARD to RvPart.FOOTER) to RvSpace.footnoteAbove,
            (RvPart.FOOTER to RvPart.SECTION) to RvSpace.sectionAbove,
            // A footer ends its group; a card with no section title over it starts the next as a section title would.
            (RvPart.FOOTER to RvPart.CARD) to RvSpace.sectionAbove,
            (RvPart.SECTION to RvPart.CARD) to RvSpace.sectionBelow,
            // An in-page search is a control of the page over the list it searches, which sits as a tab's under its bar.
            (RvPart.CARD to RvPart.SEARCH) to RvSpace.controlGap,
            (RvPart.SEARCH to RvPart.CARD) to RvSpace.searchGap,
        )

        /** On a tab home every block sits one gap from the next, a control row too (rv-fix-4e). */
        val TAB_GAPS: Map<Pair<String, String>, Dp> = mapOf(
            (RvPart.CAPTION to RvPart.CONTROL) to RvSpace.tabBlockGap,
            (RvPart.CAPTION to RvPart.CARD) to RvSpace.tabBlockGap,
            (RvPart.CONTROL to RvPart.CARD) to RvSpace.tabBlockGap,
            (RvPart.CARD to RvPart.CONTROL) to RvSpace.tabBlockGap,
        )
    }
}
