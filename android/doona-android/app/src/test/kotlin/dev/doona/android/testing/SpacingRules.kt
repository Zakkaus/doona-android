package dev.doona.android.testing

import androidx.compose.ui.unit.Dp
import dev.doona.android.designsystem.components.LayoutRole
import dev.doona.android.designsystem.theme.DoonaTheme
import kotlin.math.abs

/**
 * The spacing rules of the design system, read off a rendered layout tree. A rule that holds for every node (gaps on
 * the scale, touch targets) is generic; the others are asserted by role, so a new screen is checked without being
 * described. Every function returns one message per violation and an empty list when the rule holds.
 */
object SpacingRules {
    private const val TOL = 0.5f
    private const val SHARED_TOUCH = 1f
    private val space = DoonaTheme.spacing
    private val sizes = DoonaTheme.size

    /** The rules that hold at any scroll offset: the geometry of the layout, which a scroll only shifts. */
    internal fun check(root: LayoutBox, page: PageLayout): List<String> =
        detected(root, page.shows) + scaleGaps(root) + rhythm(root, page.rhythm) + sheet(root) + stacks(root) + cards(root) + controls(root) +
            alikeControls(root) + contained(root) + rows(root) + hubBar(root) + valueColumns(root) + contentColumn(root)

    private fun near(a: Float, b: Float) = abs(a - b) <= TOL

    /** The rules read nodes by role: a role the page is meant to show but the probe did not find is a probe that reads nothing. */
    internal fun detected(root: LayoutBox, shows: Set<LayoutRole>): List<String> {
        val found = root.descendants().mapNotNull { it.role }.toSet()
        val missing = (shows - found).map { "the page shows a $it but the probe found none, so the rules of that role checked nothing" }
        // The segmented control and the chips are filled, so a page with controls has a filled one; none means the fill went unseen.
        val unfilled = LayoutRole.Control in shows && root.descendants().none { c -> c.role == LayoutRole.Control && c.descendants().any { it.filled } }
        val unread = root.descendants().none { it.text != null }
        return missing + listOfNotNull(
            "the page shows controls but the probe found none filled, so the alike-controls rule checked nothing".takeIf { unfilled },
            "the page has no text, or the probe reads none from the semantics, so the touch rules never meet a label".takeIf { unread },
        )
    }

    /** Every gap between consecutive children of a column is 0 or a value of the spacing scale; an overlap is a negative gap. */
    fun scaleGaps(root: LayoutBox): List<String> {
        val allowed = listOf(0f) + space.scale.map { it.value }
        return root.descendants().flatMap { it.columnGaps() }
            .filter { (_, _, gap) -> allowed.none { near(it, gap) } }
            .map { (a, b, gap) -> "gap of %.1f dp between %s and %s is not on the scale".format(gap, a, b) }
            .toList()
    }

    /**
     * Nothing draws outside the box of the node that holds it: every child lies inside its parent, so a component that
     * hangs a part of itself in a neighbour's padding, and makes its callers leave room for it, fails. A scrolling sheet
     * is taller than the viewport that clips it, and a chevron's box clips the glyph it cuts to the ink.
     */
    fun contained(root: LayoutBox): List<String> {
        val viewports = root.descendants().filter { it.role == LayoutRole.Sheet }.map { root.viewportOf(it) }.toList()
        return root.descendants().filter { it.role != LayoutRole.Chevron && viewports.none { v -> v === it } }.flatMap { parent ->
            // A lazy sheet is not a column and is its own viewport: the rows it places past its top or bottom are clipped by it.
            val clips = parent.role == LayoutRole.Sheet && !parent.vertical
            parent.content.filter { it.left < parent.left - TOL || it.right > parent.right + TOL || (!clips && (it.top < parent.top - TOL || it.bottom > parent.bottom + TOL)) }
                .map { "$it draws outside its parent $parent" }
        }.toList()
    }

    /**
     * The rhythm of a page: the gap before each block of the sheet after the first is the spacing role the page declares
     * for that boundary (`afterTitle` under the title, `section` between sections, `block` and `item` in a section), so
     * a boundary that takes another role's value fails even though that value is on the scale.
     */
    fun rhythm(root: LayoutBox, expected: List<Dp>): List<String> = root.descendants().filter { it.role == LayoutRole.Sheet }.flatMap { sheet ->
        val gaps = sheet.columnGaps()
        if (gaps.size != expected.size) {
            listOf("sheet has ${gaps.size + 1} blocks, ${gaps.size} gaps; the page declares ${expected.size}: ${sheet.content}")
        } else {
            gaps.zip(expected).mapIndexedNotNull { i, (gap, want) ->
                val (a, b, got) = gap
                "sheet gap $i between $a and $b is %.1f dp, its role is %.0f".format(got, want.value).takeUnless { near(got, want.value) }
            }
        }
    }.toList()

    /**
     * Cards, and rows of cards, side by side or stacked, are a block apart: the gap between sibling cards of a section,
     * down a column and across a row of tiles.
     */
    fun stacks(root: LayoutBox): List<String> {
        fun cardLike(box: LayoutBox): Boolean = box.role == LayoutRole.Card || (box.content.isNotEmpty() && box.content.all(::cardLike))
        return root.descendants().filter { it.role != LayoutRole.Sheet && it.role != LayoutRole.Card && it.content.size > 1 && it.content.all(::cardLike) }.flatMap { stack ->
            (stack.columnGaps() + stack.rowGaps()).filter { (_, _, gap) -> !near(gap, space.block.value) }
                .map { (a, b, gap) -> "cards $a and $b are %.1f dp apart, a block is %.0f".format(gap, space.block.value) }
        }.toList()
    }

    /** A row pads nothing at its edges (the card's inset does); an icon is an `item` from what follows it, anything else a `block`. */
    private fun rowGapsOf(row: LayoutBox): List<String> = buildList {
        val items = row.content.sortedBy { it.left }
        if (items.isNotEmpty() && !near(items.first().left, row.left)) add("row $row: content starts %.1f dp inside the row".format(items.first().left - row.left))
        if (items.isNotEmpty() && !near(items.last().right, row.right)) add("row $row: content ends %.1f dp inside the row".format(row.right - items.last().right))
        row.rowGaps().forEach { (a, b, gap) ->
            val want = if (a.glyph) space.item.value else space.block.value
            if (!near(gap, want)) add("row $row: %.1f dp between %s and %s, the role is %.0f".format(gap, a, b, want))
        }
    }

    /**
     * The sheet, the top bar and the hub bar hold a column at most 600 dp wide in the room their parent gives them: the
     * window, or what a rail leaves of it (D20). The column is centred in that room, except in a window short enough for
     * a rail (under [DoonaSize.compactHeight]), where it starts at the room's start edge, next to the rail, so the rail
     * does not leave a band of empty sheet before the content. A rail is a hub bar that stands on end at the window's
     * edge; it holds no content column.
     */
    fun contentColumn(root: LayoutBox): List<String> {
        val roles = setOf(LayoutRole.Sheet, LayoutRole.TopBar, LayoutRole.HubBar)
        val compact = root.height < sizes.compactHeight.value
        return root.descendants().filter { it.role in roles && !(it.role == LayoutRole.HubBar && it.vertical) }.flatMap { column ->
            val room = root.parentOf(column)
            val before = column.left - room.left
            val after = room.right - column.right
            buildList {
                if (column.width > sizes.contentMax.value + TOL) add("${column.role} column $column is %.1f dp wide, over the %.0f dp cap".format(column.width, sizes.contentMax.value))
                if (compact && column.width < room.width - TOL && !near(before, 0f)) {
                    add("${column.role} column $column starts %.1f dp inside its room in a short window, where it starts at the rail".format(before))
                }
                if (!compact && !near(before, after)) add("${column.role} column $column is not centred: %.1f dp left, %.1f dp right".format(before, after))
            }
        }.toList()
    }

    /** Sheet: blocks start on the gutter, stay inside it and sit 20 below the sheet's top; [rhythm] checks the gaps between them. */
    fun sheet(root: LayoutBox): List<String> = root.descendants().filter { it.role == LayoutRole.Sheet }.flatMap { sheet ->
        val blocks = sheet.content
        val gutter = space.gutter.value
        buildList {
            blocks.filter { !near(it.left - sheet.left, gutter) }.forEach { add("sheet block $it starts at %.1f, not on the %.0f dp gutter".format(it.left - sheet.left, gutter)) }
            blocks.filter { it.right > sheet.right - gutter + TOL }.forEach { add("sheet block $it ends past the gutter") }
            blocks.firstOrNull()?.takeUnless { near(it.top - sheet.top, space.sheetTop.value) }
                ?.let { add("sheet starts its first block $it %.1f dp below its top, not %.0f".format(it.top - sheet.top, space.sheetTop.value)) }
        }
    }.toList()

    /** Card: content starts 16 inside every edge. */
    fun cards(root: LayoutBox): List<String> = root.descendants().filter { it.role == LayoutRole.Card }.flatMap { card ->
        val inset = space.cardInset.value
        val items = card.content
        buildList {
            if (items.isNotEmpty()) {
                val left = items.minOf { it.left } - card.left
                val top = items.minOf { it.top } - card.top
                if (!near(left, inset)) add("card $card: content starts %.1f dp from the left edge, not %.0f".format(left, inset))
                if (!near(top, inset)) add("card $card: content starts %.1f dp from the top edge, not %.0f".format(top, inset))
                if (card.right - items.maxOf { it.right } < inset - TOL) add("card $card: content ends inside the %.0f dp inset on the right".format(inset))
                if (card.bottom - items.maxOf { it.bottom } < inset - TOL) add("card $card: content ends inside the %.0f dp inset at the bottom".format(inset))
            }
        }
    }.toList()

    /**
     * Control (segmented control, picker, chip): exactly as high as its visible track; touch comes from the target size.
     * A button is at least that high and grows to hold a label that wraps.
     */
    fun controls(root: LayoutBox): List<String> = root.descendants().mapNotNull { node ->
        when {
            node.role == LayoutRole.Control && !near(node.height, sizes.control.value) -> "control $node is %.1f dp high, its track is %.0f".format(node.height, sizes.control.value)
            node.role == LayoutRole.Button && node.height < sizes.control.value - TOL -> "button $node is %.1f dp high, under the control's %.0f".format(node.height, sizes.control.value)
            else -> null
        }
    }.toList()

    /** Row: at least 48 dp high, its content centred on its middle; [rowGapsOf] holds what it pads. */
    fun rows(root: LayoutBox): List<String> = root.descendants().filter { it.role == LayoutRole.Row }.flatMap { row ->
        buildList {
            if (row.height < sizes.touch.value - TOL) add("row $row is %.1f dp high, under %.0f".format(row.height, sizes.touch.value))
            row.content.filter { !near(it.top + it.height / 2, row.top + row.height / 2) }
                .forEach { add("row $row: $it is not centred vertically") }
            addAll(rowGapsOf(row))
        }
    }.toList()

    /** Controls side by side are alike: all filled or all quiet. */
    fun alikeControls(root: LayoutBox): List<String> = root.descendants().flatMap { parent ->
        val controls = parent.content.filter { it.role == LayoutRole.Control }
        val fills = controls.map { c -> c.descendants().any { it.filled } }
        if (controls.size > 1 && fills.distinct().size > 1) {
            listOf("controls $controls side by side are not alike: filled is $fills")
        } else {
            emptyList()
        }
    }.toList()

    /**
     * What a scrolling sheet does not show whole: the blocks that reach out of its viewport. A pointer reaches only the
     * visible part of them, so their touch bounds say nothing about the design at this offset; a scroll to the offset
     * that shows them whole checks them (see [touch]). An action counts with the area a pointer hits over it, which
     * is taller than the control: the part of it outside the viewport is not hit, so it must not be held against the
     * bar that starts there.
     */
    private fun scrolledOut(root: LayoutBox): Set<LayoutBox> = root.descendants().filter { it.role == LayoutRole.Sheet }.flatMap { sheet ->
        val viewport = root.viewportOf(sheet)
        sheet.descendants().filter { it !== sheet && reachesOut(it, viewport) }
    }.toSet()

    private fun reachesOut(node: LayoutBox, viewport: LayoutBox): Boolean {
        val reach = if (node.actionable) maxOf(0f, ((node.touchSize?.second ?: node.height) - node.height) / 2) else 0f
        return node.top - reach < viewport.top - TOL || node.bottom + reach > viewport.bottom + TOL
    }

    /**
     * What [touch] saw at one scroll offset: the violations, the names of the actions it saw whole, and every action's
     * name with its description.
     */
    class TouchReport(val violations: List<String>, val seen: Set<String>, val actions: Map<String, String>)

    /**
     * The pointer rules at one scroll offset, for the nodes in view whole: every action is hit over at least 48 × 48 dp,
     * and a control smaller than that, which is still hit over 48 dp, has a hit area that reaches into no text and no
     * other control (a screen reader reports that area as its bounds and trims it below 48 otherwise). The tree is the
     * same at every offset, except for the rows a lazy list composes as they come into view, so an action is named by its
     * text and its place among the actions of that text: an action that no offset showed whole is in
     * [TouchReport.actions] but in no [TouchReport.seen], and the caller fails it ([unseen]).
     */
    fun touch(root: LayoutBox): TouchReport {
        val hidden = scrolledOut(root)
        val all = root.descendants().toList()
        val nodes = all.filter { it !in hidden }
        val violations = touchTargets(nodes) + touchZones(nodes)
        val names = actionNames(all)
        return TouchReport(violations, nodes.mapNotNull { names[it] }.toSet(), all.mapNotNull { n -> names[n]?.let { it to n.toString() } }.toMap())
    }

    /** The names of the actions among [all], by identity: the text of the action and how many actions before it have the same text. */
    private fun actionNames(all: List<LayoutBox>): Map<LayoutBox, String> {
        val before = HashMap<String, Int>()
        return all.filter { it.actionable }.associateWith { node ->
            val text = node.text.orEmpty()
            "$text#${before.merge(text, 1, Int::plus)!! - 1}"
        }
    }

    /** The actions that no scroll offset showed whole: they were never checked for touch. */
    fun unseen(reports: List<TouchReport>): List<String> {
        val seen = reports.flatMap { it.seen }.toSet()
        return reports.first().actions.filterKeys { it !in seen }.map { "action ${it.value} was never in view whole in ${reports.size} scroll offsets" }
    }

    private fun touchTargets(nodes: List<LayoutBox>): List<String> = nodes.filter { it.actionable }.mapNotNull { node ->
        val (w, h) = node.touchSize ?: return@mapNotNull null
        "touch target of $node is %.1f × %.1f dp, under %.0f".format(w, h, sizes.touch.value).takeIf { w < sizes.touch.value - TOL || h < sizes.touch.value - TOL }
    }

    private fun touchZones(nodes: List<LayoutBox>): List<String> {
        fun zone(node: LayoutBox): List<Float> {
            val (w, h) = node.touchSize?.takeIf { node.actionable } ?: (node.width to node.height)
            return listOf(node.centerX - w / 2, node.centerY - h / 2, node.centerX + w / 2, node.centerY + h / 2)
        }
        return nodes.filter { it.actionable }.flatMap { target ->
            val skip = target.descendants().toSet() + nodes.filter { n -> n.descendants().any { it === target } }
            val (left, top, right, bottom) = zone(target)
            nodes.filter { it !in skip && (it.text != null || it.actionable) }.filter { other ->
                val (l, t, r, b) = zone(other)
                // Neighbouring controls may share a sliver of touch area, which the platform splits between them: the
                // items of a segmented control are 44 dp wide, 4 apart, and a reader still reports them as 48.
                val slack = if (other.actionable) SHARED_TOUCH else TOL
                minOf(r, right) - maxOf(l, left) > slack && minOf(b, bottom) - maxOf(t, top) > slack
            }.map { "touch area of $target reaches into $it" }
        }
    }

    /**
     * Hub bar: equal tabs, each label and pill centred on its tab, and the tab's content padded by [space].navInset
     * above and below, so a bar that a larger font makes taller than its minimum does not press its content on its edges.
     */
    fun hubBar(root: LayoutBox): List<String> = root.descendants().filter { it.role == LayoutRole.HubBar }.flatMap { bar ->
        val tabs = bar.content
        buildList {
            tabs.filter { !near(it.width, tabs.first().width) }.forEach { add("hub bar: tab $it is not as wide as the first") }
            tabs.forEach { tab ->
                val label = tab.leaves.firstOrNull { it.text != null }
                if (label != null && !near(label.centerX, tab.centerX)) add("hub bar: label $label is %.1f dp off the centre of its tab".format(label.centerX - tab.centerX))
                val pill = tab.content.firstOrNull()
                if (pill != null && !near(pill.centerX, tab.centerX)) add("hub bar: pill $pill is %.1f dp off the centre of its tab".format(pill.centerX - tab.centerX))
                val air = space.navInset.value
                if (pill != null && pill.top - tab.top < air - TOL) add("hub bar: pill $pill is %.1f dp from the top of its tab, under %.0f".format(pill.top - tab.top, air))
                if (label != null && tab.bottom - label.bottom < air - TOL) add("hub bar: label $label is %.1f dp from the bottom of its tab, under %.0f".format(tab.bottom - label.bottom, air))
            }
        }
    }.toList()

    /** A metric tile's value starts on the tile's content edge, with or without a status dot. */
    fun valueColumns(root: LayoutBox): List<String> = root.descendants().filter { it.role == LayoutRole.Card }.flatMap { card ->
        val edge = card.left + space.cardInset.value
        card.descendants().filter { it.role == LayoutRole.Value }.mapNotNull { value ->
            val text = value.leaves.firstOrNull { it.text != null } ?: return@mapNotNull null
            "value $text starts %.1f dp off its tile's content edge".format(text.left - edge).takeUnless { near(text.left, edge) }
        }
    }.toList()
}
