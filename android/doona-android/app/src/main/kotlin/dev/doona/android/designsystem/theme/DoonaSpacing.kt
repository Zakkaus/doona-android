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
    /** Content edge: the sheet's side padding, where the first and last glyph of the top bar sit. */
    val gutter = SpaceScale.s4

    /** Padding of a card on all sides. */
    val cardInset = SpaceScale.s4

    /** Space above the first block of a sheet. */
    val sheetTop = SpaceScale.s5

    /** Space below the last block of a sheet, so it scrolls clear of the bar below. */
    val sheetBottom = SpaceScale.s6

    /** Between the sections of a page. */
    val section = SpaceScale.s6

    /** Between a page title and the tab row under it. */
    val afterTitle = SpaceScale.s4

    /** Between sibling blocks (cards, tiles, controls), inside a card, and the inline padding of rows and fills. */
    val block = SpaceScale.s3

    /** Between sibling items (node tiles, chips), and an icon or dot and its label. */
    val item = SpaceScale.s2

    /** Between a block and its caption; between wrapped lines of chips. */
    val tight = SpaceScale.s1

    /** Between a status dot and its value in a latency. */
    val dotGap = SpaceScale.s1h

    /** Knob inset in a switch; line gap in a tile. */
    val hairline = SpaceScale.half

    /** The whole scale, for checks. */
    val scale: List<Dp> = SpaceScale.all
}
