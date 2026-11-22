package dev.doona.android.testing

import dev.doona.android.designsystem.components.DoonaSettingsRowDefaults
import dev.doona.android.designsystem.components.LayoutRole
import dev.doona.android.designsystem.theme.DoonaTheme
import kotlin.math.abs

/**
 * The spacing the design system's components promise, read off a rendered layout tree by their layout roles (the RV
 * roles, which [SpacingRules] leaves alone) and held to the roles a [GalleryLayout] states. Every function returns one
 * message per violation and an empty list when the rule holds. The heights are those at font scale 1.0, where the
 * components keep their measured sizes; a larger font grows them.
 */
object RvSpacingRules {
    private const val TOL = 0.5f
    private val sizes = DoonaTheme.size
    private val chrome = DoonaTheme.chrome

    internal fun check(root: LayoutBox, layout: GalleryLayout): List<String> =
        SpacingRules.detected(root, layout.shows) + rowHeights(root) + leading(root, layout) + trailing(root, layout) + cardPadding(root) +
            sectionTitles(root, layout) + targets(root) + backDisc(root, layout) + navClearance(root, layout)

    private fun near(a: Float, b: Float) = abs(a - b) <= TOL

    /** Every row of a list card, with its card: the card's edge is what the row's insets are measured from. */
    private fun cardRows(root: LayoutBox): List<Pair<LayoutBox, LayoutBox>> = root.descendants().filter { it.role == LayoutRole.ListCard }
        .flatMap { card -> card.descendants().filter { it.role == LayoutRole.ListRow }.map { card to it } }.toList()

    /** A row's title: its widest part, which takes the room the leading and trailing parts leave. */
    private fun title(row: LayoutBox): LayoutBox? = row.content.maxByOrNull { it.width }

    /** The 'See all' row: one text, centred. */
    private fun seeAll(row: LayoutBox): Boolean = row.content.singleOrNull()?.let { it.text != null && near(it.centerX, row.centerX) } == true

    /**
     * A row of one line is 58 dp, a list row of two (a title over a subtitle) 82, and the 'See all' row 56. A settings row
     * of two lines keeps its natural height instead (Addendum A): its text 16 dp from its top and its bottom.
     */
    fun rowHeights(root: LayoutBox): List<String> = cardRows(root).mapNotNull { (_, row) ->
        val text = title(row)
        val lines = text?.takeIf { it.vertical }?.content?.size ?: 1
        val pad = DoonaSettingsRowDefaults.verticalPad.value
        val natural = text != null && near(text.top - row.top, pad) && near(row.bottom - text.bottom, pad)
        val want = when {
            seeAll(row) -> sizes.seeAll
            lines >= 2 -> sizes.rowTwoLine
            else -> sizes.rowOneLine
        }.value
        "row %s is %.1f dp high, a row of %d line(s) is %.0f".format(row, row.height, lines, want).takeUnless { near(row.height, want) || (lines >= 2 && natural) }
    }

    /** A row's leading visual starts [GalleryLayout.rowPadding] from the card's edge and ends [GalleryLayout.rowGap] before the text. */
    internal fun leading(root: LayoutBox, layout: GalleryLayout): List<String> = cardRows(root).flatMap { (card, row) ->
        val items = row.content.sortedBy { it.left }
        val lead = items.firstOrNull()?.takeIf { items.size > 1 && it !== title(row) } ?: return@flatMap emptyList()
        listOfNotNull(
            "row %s: leading %s starts %.1f dp from the card's edge, not %.0f".format(row, lead, lead.left - card.left, layout.rowPadding.value)
                .takeUnless { near(lead.left - card.left, layout.rowPadding.value) },
            "row %s: %.1f dp from leading %s to %s, not %.0f".format(row, items[1].left - lead.right, lead, items[1], layout.rowGap.value)
                .takeUnless { near(items[1].left - lead.right, layout.rowGap.value) },
        )
    }

    /** What ends a row (its trailing value, control or chevron, or its title) ends [GalleryLayout.rowPadding] from the card's edge. */
    internal fun trailing(root: LayoutBox, layout: GalleryLayout): List<String> = cardRows(root).filter { !seeAll(it.second) }.mapNotNull { (card, row) ->
        val last = row.content.maxByOrNull { it.right } ?: return@mapNotNull null
        "row %s: %s ends %.1f dp from the card's edge, not %.0f".format(row, last, card.right - last.right, layout.rowPadding.value)
            .takeUnless { near(card.right - last.right, layout.rowPadding.value) }
    }

    /** A card pads its bottom as it pads its top: a list card neither, a card of free content both by its inset. */
    fun cardPadding(root: LayoutBox): List<String> = root.descendants().filter { it.role == LayoutRole.ListCard || it.role == LayoutRole.Card }.mapNotNull { card ->
        val items = card.content.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        val top = items.minOf { it.top } - card.top
        val bottom = card.bottom - items.maxOf { it.bottom }
        "card %s pads %.1f dp at the top and %.1f at the bottom".format(card, top, bottom).takeUnless { near(top, bottom) }
    }.toList()

    /** A section title outside a card keeps [GalleryLayout.sectionAbove] above its text and [GalleryLayout.sectionBelow] under it. */
    internal fun sectionTitles(root: LayoutBox, layout: GalleryLayout): List<String> {
        val inCards = root.descendants().filter { it.role == LayoutRole.ListCard || it.role == LayoutRole.Card }.flatMap { it.descendants() }.toSet()
        return root.descendants().filter { it.role == LayoutRole.Heading && it.children.isNotEmpty() && it !in inCards }.flatMap { title ->
            val text = title.leaves.firstOrNull { it.text != null } ?: return@flatMap emptyList()
            listOfNotNull(
                "section title %s: %.1f dp above its text, not %.0f".format(title, text.top - title.top, layout.sectionAbove.value)
                    .takeUnless { near(text.top - title.top, layout.sectionAbove.value) },
                "section title %s: %.1f dp under its text, not %.0f".format(title, title.bottom - text.bottom, layout.sectionBelow.value)
                    .takeUnless { near(title.bottom - text.bottom, layout.sectionBelow.value) },
            )
        }.toList()
    }

    /**
     * Every action lays out a 48 dp target, however small the control it draws (a 44 dp glass control, a 35 dp tool disc):
     * its box is at least 48 dp each way. A tab of the floating nav is the exception: the capsule round it is the target,
     * and it is at least 48 dp high when collapsed too.
     */
    fun targets(root: LayoutBox): List<String> = root.descendants().filter { it.actionable }.mapNotNull { action ->
        val holder = root.parentOf(action).takeIf { it.role == LayoutRole.Chrome }
        val (w, h) = if (holder != null) sizes.touch.value to holder.height else action.width to action.height
        "action %s lays out %.1f × %.1f dp, under %.0f".format(action, w, h, sizes.touch.value).takeIf { w < sizes.touch.value - TOL || h < sizes.touch.value - TOL }
    }.toList()

    /** The back disc (the one action of the chrome role) sits [GalleryLayout.gutter] from the start of what holds it. */
    internal fun backDisc(root: LayoutBox, layout: GalleryLayout): List<String> = root.descendants().filter { it.role == LayoutRole.Chrome && it.actionable }.mapNotNull { disc ->
        val bounds = disc.semanticsBounds ?: return@mapNotNull "back disc $disc reports no bounds"
        val x = bounds.center.x - chrome.control.value / 2 - root.parentOf(disc).left
        "back disc %s is at x %.1f dp, not %.0f".format(disc, x, layout.gutter.value).takeUnless { near(x, layout.gutter.value) }
    }.toList()

    /**
     * A page with the floating nav ends its scrolling content with a space as tall as the nav's bottom inset, the nav's
     * height and [GalleryLayout.navGap], so the last card clears the nav at the end of the scroll. The page is the box that
     * holds the nav's room, a scrolling column and a bar; a [GalleryLayout.scaffold] must have one.
     */
    internal fun navClearance(root: LayoutBox, layout: GalleryLayout): List<String> {
        val navs = root.descendants().filter { n -> n.role == LayoutRole.Chrome && !n.actionable && n.content.isNotEmpty() && n.content.all { it.actionable } }
        val checks = navs.mapNotNull { nav ->
            val page = root.parentOf(root.parentOf(nav))
            val column = page.content.firstOrNull { it.vertical }?.takeIf { page.content.any { it.role == LayoutRole.Chrome } } ?: return@mapNotNull null
            val want = (page.bottom - nav.bottom) + nav.height + layout.navGap.value
            val end = column.children.lastOrNull()
            "the page's content ends with %s, not a space of %.1f dp (nav inset, nav, gap)".format(end, want).takeUnless { end != null && end.blank && near(end.height, want) }
        }.toList()
        val found = navs.count { nav -> root.parentOf(root.parentOf(nav)).content.any { it.vertical } }
        return checks + listOfNotNull("the page is a scaffold but the probe found no page with the nav".takeIf { layout.scaffold && found == 0 })
    }
}
