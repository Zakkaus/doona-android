package dev.doona.android.designsystem.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import dev.doona.android.designsystem.tokens.SpaceScale

/**
 * Spacing roles (§4). Every gap of a screen is one of these, named by what it separates; the values come from the
 * spacing scale.
 */
@Immutable
object DoonaSpacing {
    /** No space: the bound of a clamp, or a shift that is not there. */
    val none = SpaceScale.none

    /** Content edge: the sheet's side padding, where the first and last glyph of the top bar sit. */
    val gutter = SpaceScale.s4

    /** Padding of a card on all sides. */
    val cardInset = SpaceScale.s4

    /** Space above the first block of a sheet. */
    val sheetTop = SpaceScale.s5

    /** Space below the last block of a sheet, so it scrolls clear of the bar below. */
    val sheetBottom = SpaceScale.s10

    /** Between the sections of a page. */
    val section = SpaceScale.s6

    /** Between a page title and the tab row under it. */
    val afterTitle = SpaceScale.s4

    /** Between sibling blocks (cards, tiles, controls), inside a card, and between a label and its trailing control. */
    val block = SpaceScale.s3

    /**
     * Padding at the sides inside a filled control, a node tile or a page tab. It is the size of [block] but not a gap
     * between two things, so a change to the one must not move the other.
     */
    val fillInset = SpaceScale.s3

    /** Between sibling items (node tiles, chips), and an icon or dot and its label. */
    val item = SpaceScale.s2

    /** Between a block and its caption; between wrapped lines of chips. */
    val tight = SpaceScale.s1

    /** Between a status dot and its value in a latency. */
    val dotGap = SpaceScale.s1h

    /** Between the lines of a tile: the name row and its source row. The switch's knob inset is the switch's own. */
    val lineGap = SpaceScale.half

    /** Above and below the icon and label of a hub tab: the tab keeps its air when a larger font makes it taller than the bar. */
    val navInset = SpaceScale.s1h

    /** The whole scale, for checks. */
    val scale: List<Dp> = SpaceScale.all
}
