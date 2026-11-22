package dev.doona.android.testing

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
    private val space = DoonaTheme.spacing
    private val sizes = DoonaTheme.size

    /** Gaps between blocks of a page: the roles `tight`, `item`, `block`, `afterTitle` and `section`. */
    private val pageGaps = listOf(space.tight, space.item, space.block, space.afterTitle, space.section).map { it.value }

    fun check(root: LayoutBox, windowWidth: Float): List<String> =
        scaleGaps(root) + sheet(root, windowWidth) + cards(root) + controls(root) + alikeControls(root) + rows(root) + touchTargets(root) +
            topBar(root, windowWidth) + hubBar(root) + valueColumns(root)

    private fun near(a: Float, b: Float) = abs(a - b) <= TOL

    /** Every gap between consecutive children of a column is 0 or a value of the spacing scale. */
    fun scaleGaps(root: LayoutBox): List<String> {
        val allowed = listOf(0f) + space.scale.map { it.value }
        return root.descendants().flatMap { it.columnGaps() }
            .filter { (_, _, gap) -> allowed.none { near(it, gap) } }
            .map { (a, b, gap) -> "gap of %.1f dp between %s and %s is not on the scale".format(gap, a, b) }
            .toList()
    }

    /** Sheet: blocks start on the gutter, stay inside it, sit 20 below the sheet's top and 4 to 24 apart. */
    fun sheet(root: LayoutBox, windowWidth: Float): List<String> = root.descendants().filter { it.role == LayoutRole.Sheet }.flatMap { sheet ->
        val blocks = sheet.content
        val gutter = space.gutter.value
        buildList {
            blocks.filter { !near(it.left, gutter) }.forEach { add("sheet block $it starts at %.1f, not on the %.0f dp gutter".format(it.left, gutter)) }
            blocks.filter { it.right > windowWidth - gutter + TOL }.forEach { add("sheet block $it ends past the gutter") }
            blocks.firstOrNull()?.takeUnless { near(it.top - sheet.top, space.sheetTop.value) }
                ?.let { add("sheet starts its first block $it %.1f dp below its top, not %.0f".format(it.top - sheet.top, space.sheetTop.value)) }
            sheet.columnGaps().filter { (_, _, gap) -> pageGaps.none { near(it, gap) } }
                .forEach { (a, b, gap) -> add("sheet gap of %.1f dp between %s and %s is not a page gap".format(gap, a, b)) }
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

    /** Control (segmented control, picker, chip): exactly as high as its visible track; touch comes from the target size. */
    fun controls(root: LayoutBox): List<String> = root.descendants().filter { it.role == LayoutRole.Control }
        .filter { !near(it.height, sizes.control.value) }
        .map { "control $it is %.1f dp high, its track is %.0f".format(it.height, sizes.control.value) }
        .toList()

    /** Row: at least 48 dp high, its content centred on its middle. */
    fun rows(root: LayoutBox): List<String> = root.descendants().filter { it.role == LayoutRole.Row }.flatMap { row ->
        buildList {
            if (row.height < sizes.touch.value - TOL) add("row $row is %.1f dp high, under %.0f".format(row.height, sizes.touch.value))
            row.content.filter { !near(it.top + it.height / 2, row.top + row.height / 2) }
                .forEach { add("row $row: $it is not centred vertically") }
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

    /** Every actionable node can be hit over at least 48 × 48 dp. */
    fun touchTargets(root: LayoutBox): List<String> = root.descendants().filter { it.actionable }.mapNotNull { node ->
        val (w, h) = node.touchSize ?: return@mapNotNull null
        "touch target of $node is %.1f × %.1f dp, under %.0f".format(w, h, sizes.touch.value).takeIf { w < sizes.touch.value - TOL || h < sizes.touch.value - TOL }
    }.toList()

    /** Top bar: the first and last glyph sit on the content gutter, as the content below does. */
    fun topBar(root: LayoutBox, windowWidth: Float): List<String> = root.descendants().filter { it.role == LayoutRole.TopBar }.flatMap { bar ->
        val gutter = space.gutter.value
        val glyphs = bar.leaves
        buildList {
            glyphs.minByOrNull { it.left }?.takeUnless { near(it.left, gutter) }
                ?.let { add("top bar: first glyph $it starts at %.1f, %.1f dp off the gutter".format(it.left, it.left - gutter)) }
            // Without actions the bar holds only the mark or the back arrow.
            glyphs.takeIf { it.size > 1 }?.maxByOrNull { it.right }?.takeUnless { near(it.right, windowWidth - gutter) }
                ?.let { add("top bar: last glyph $it ends at %.1f, %.1f dp off the gutter".format(it.right, windowWidth - gutter - it.right)) }
        }
    }.toList()

    /** Hub bar: equal tabs, each label and pill centred on its tab. */
    fun hubBar(root: LayoutBox): List<String> = root.descendants().filter { it.role == LayoutRole.HubBar }.flatMap { bar ->
        val tabs = bar.content
        buildList {
            tabs.filter { !near(it.width, tabs.first().width) }.forEach { add("hub bar: tab $it is not as wide as the first") }
            tabs.forEach { tab ->
                val label = tab.leaves.firstOrNull { it.text != null }
                if (label != null && !near(label.centerX, tab.centerX)) add("hub bar: label $label is %.1f dp off the centre of its tab".format(label.centerX - tab.centerX))
                val pill = tab.content.firstOrNull()
                if (pill != null && !near(pill.centerX, tab.centerX)) add("hub bar: pill $pill is %.1f dp off the centre of its tab".format(pill.centerX - tab.centerX))
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
