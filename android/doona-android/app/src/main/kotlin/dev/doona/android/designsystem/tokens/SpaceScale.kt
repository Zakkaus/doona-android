package dev.doona.android.designsystem.tokens

import androidx.compose.ui.unit.dp

// Raw values of the design system. The named values are the Revolut sample's, measured from the store shots at a 412 dp
// screen (~/code/doona-v1/rv-spec.md, the section of each in its KDoc; audit IDs from rv-final-design.md). Only
// designsystem/theme reads this package: components and screens use the roles there (tools/check-layers.sh enforces it).

/** Spacing: the sample's gaps by purpose, then the U1 steps the old screens are laid out on. */
object SpaceScale {
    /** A screen's side margin, and every row's start and end inside a card (measured §7: 16 dp). */
    val gutter = 16.dp

    /** Between stacked cards (measured §2: 16 dp; X-03, A06, N11, P02). */
    val cardGap = 16.dp

    /** Start and end padding of a row inside a card, and of the chart card (measured §7: 16 dp). */
    val rowPadding = 16.dp

    /** From a row's leading visual to its text (measured §7: 16 dp). */
    val rowGap = 16.dp
    val trailingGap = 12.dp

    /** From a round action's disc to its label (measured §6: 8 dp). */
    val discToLabel = 8.dp

    /**
     * From the status bar to the hero's caption: the top bar's row (12 + 44 dp), then 96 dp, as Revolut sets its caption
     * about 100 dp under the bar; the map's marker sits in that space, clear of the bar's glass (S5-01).
     */
    val headerTop = 152.dp
    val heroToActions = 64.dp
    val actionsToCard = 32.dp
    val subGap = 6.dp
    val tight = 4.dp

    /** Between two tiles side by side: Revolut's crypto tiles sit 16 dp apart (measured §10.2). */
    val tileGap = 16.dp

    /** From the detail page's segmented control to its hero, and from its figures to its chart. */
    val chartGap = 24.dp

    /** From the detail chart to its range chips. */
    val chipGap = 12.dp

    /** An outside section title: this far under what is above it, and this far above its card (measured §13.4; X-05, X-17). */
    val sectionAbove = 24.dp
    val sectionBelow = 12.dp

    /** A card's in-card header row sits this far under the card's top, so the card's padding is the same at both ends (S5-17). */
    val headerInset = 4.dp

    /** From a screen's title block to its first card. */
    val titleToCard = 24.dp

    /** Between a card's lines of text and its progress bar. */
    val barGap = 14.dp

    // U1's spacing scale (doona `foundations.css:67-79`), kept while the old screens are laid out on it: their gaps are
    // checked against [all]. The sample has no scale; its gaps are the named values above.

    /** No space, for a clamp or a padding that is not there; not a step of the scale. */
    val none = 0.dp
    val half = 2.dp
    val s2 = 8.dp
    val s2h = 10.dp
    val s3 = 12.dp
    val s5 = 20.dp
    val s8 = 32.dp
    val s10 = 40.dp
    val s12 = 48.dp
    val s16 = 64.dp
    val all = listOf(half, tight, subGap, s2, s2h, s3, gutter, s5, sectionAbove, s8, s10, s12, s16)
}
