package dev.doona.android.designsystem.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import dev.doona.android.designsystem.tokens.SpaceScale

/** Spacing roles. Every gap of a screen is one of these, named by what it separates. */
@Immutable
object DoonaSpacing {
    /** Content edge: a screen's side margin, where the first and last glyph of the top bar sit. */
    val gutter = SpaceScale.gutter

    /** Between stacked cards. */
    val cardGap = SpaceScale.cardGap

    /** Start and end padding of a row inside a card. */
    val rowPadding = SpaceScale.rowPadding

    /** From a row's leading visual to its text, and from its text to its trailing ink. */
    val rowGap = SpaceScale.rowGap
    val trailingGap = SpaceScale.trailingGap

    /** From a round action's disc to its label. */
    val discToLabel = SpaceScale.discToLabel

    /** 活動's header: from the status bar to the hero's caption, the hero to the actions, the actions to the first card. */
    val headerTop = SpaceScale.headerTop
    val heroToActions = SpaceScale.heroToActions
    val actionsToCard = SpaceScale.actionsToCard

    /** Between a line and the line under it that belongs to it. */
    val subGap = SpaceScale.subGap

    /** Between a block and its caption; between wrapped lines of chips. */
    val tight = SpaceScale.tight
    val tileGap = SpaceScale.tileGap
    val chartGap = SpaceScale.chartGap
    val chipGap = SpaceScale.chipGap

    /** An outside section title: this far under what is above it, and this far above its card. */
    val sectionAbove = SpaceScale.sectionAbove
    val sectionBelow = SpaceScale.sectionBelow

    /** A card's in-card header row under the card's top. */
    val headerInset = SpaceScale.headerInset
    val titleToCard = SpaceScale.titleToCard
    val barGap = SpaceScale.barGap

    // The old screens' roles, on U1's scale until the page lanes rebuild them.

    /** No space: the bound of a clamp, or a shift that is not there. */
    val none = SpaceScale.none

    /** Padding of a card on all sides: a row's padding. */
    val cardInset = SpaceScale.rowPadding

    /** Space above the first block of a sheet. */
    val sheetTop = SpaceScale.s5

    /** Space below the last block of a sheet, so it scrolls clear of the bar below. */
    val sheetBottom = SpaceScale.s10

    /** Between the sections of a page: an outside section title's space above. */
    val section = SpaceScale.sectionAbove

    /** Between a page title and the tab row under it. */
    val afterTitle = SpaceScale.gutter

    /** Between sibling blocks (cards, tiles, controls), inside a card, and between a label and its trailing control. */
    val block = SpaceScale.s3

    /**
     * Padding at the sides inside a filled control, a node tile or a page tab. It is the size of [block] but not a gap
     * between two things, so a change to the one must not move the other.
     */
    val fillInset = SpaceScale.s3

    /** Between sibling items (node tiles, chips), and an icon or dot and its label. */
    val item = SpaceScale.s2

    /** Between a status dot and its value in a latency. */
    val dotGap = SpaceScale.subGap

    /** Between the lines of a tile: the name row and its source row. The switch's knob inset is the switch's own. */
    val lineGap = SpaceScale.half

    /** Padding of a dialog on all sides (`.rp-dialog`, 24 on a phone). */
    val dialogInset = SpaceScale.sectionAbove

    /** Above and below the icon and label of a hub tab: the tab keeps its air when a larger font makes it taller than the bar. */
    val navInset = SpaceScale.subGap

    /** U1's scale, for the old screens' gap checks. */
    val scale: List<Dp> = SpaceScale.all
}
