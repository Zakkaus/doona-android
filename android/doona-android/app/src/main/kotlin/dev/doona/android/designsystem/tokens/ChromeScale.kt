package dev.doona.android.designsystem.tokens

import androidx.compose.ui.unit.dp

/**
 * The chrome of every page, after Revolut's measured top bar (§4) and floating bottom bar (§9): a row of 44 dp controls
 * 12 dp under the status bar (活動's duck, search pill and discs; a pushed page's back disc and title), a strip of blurred
 * page behind the row once content scrolls under it, and a glass capsule 16 dp in from the sides whose bottom sits 40 dp
 * above the screen's edge, 60 dp tall, that shrinks to icons only, 336 x 52 dp, while the content scrolls up.
 */
object ChromeScale {
    /** The controls' row: its top this far under the status bar, every control a 44 dp disc or pill in a 48 dp target (A01, X-04, X-16). */
    val barTop = 12.dp
    val control = 44.dp
    val controlGlyph = 24.dp

    /** The strip behind the row: solid to the controls' bottom plus [stripBelow], then fading out over [stripFade] (fix round 2). */
    val stripBelow = 8.dp
    val stripFade = 12.dp

    /** The strip is fully in once the content has scrolled this far; a large title hands over to the bar's over this. */
    val shadeAfter = 48.dp
    val collapse = 32.dp
    val markGlyph = 28.dp

    /** From the duck to the search pill and from the pill to the buttons; between the two buttons (A01, measured §4). */
    val controlGap = 10.dp
    val buttonGap = 8.dp

    /** The search pill's magnifier, inset from its start, and the gap to the placeholder. */
    val searchGlyph = 24.dp
    val searchInset = 8.dp

    /** A pushed page's compact title starts this far from the screen's edge: after the back disc and a gutter. */
    val titleStart = 76.dp

    /** A pushed page's large title: this far under the bar, and this far above what follows it. */
    val largeTitleTop = 8.dp
    val largeTitleBottom = 16.dp

    val navInset = 16.dp
    val navBottom = 40.dp

    /**
     * From the last card to the capsule at a page's end, the same on every page with the capsule: Revolut's measures give
     * none, so it is 活動's, as the round-2 audits measured it (S02, S-01: 28 dp; fix round 2, `rvNavClearance`).
     */
    val navGap = 28.dp

    /** The capsule's bottom stays this far above a taller system bar, so it never sits on the gesture handle. */
    val navAboveInset = 16.dp
    val navHeight = 60.dp
    val navCollapsedHeight = 52.dp
    val navCollapsedWidth = 336.dp

    /** The selected item's inner pill, inset this far from the capsule's edges: 52 dp tall in the 60 dp capsule (S5-19). */
    val navPillInset = 4.dp
    val navGlyph = 24.dp
    val navCollapsedGlyph = 20.dp
    val navLabelGap = 2.dp

    /** The label's line: 15 sp semibold whole, so glyph, gap and label (44 dp) sit inside the 52 dp pill (A07). */
    val navLabel = 18.dp

    /**
     * The glass blur's radius, and how far past a glass surface's edges the blur reads the content, so its edges blur
     * what lies there (fix round 2, item 23); then the shadows under the capsule and the top bar's controls (light only).
     */
    val blur = 20.dp
    val blurReach = 40.dp
    val navShadow = 12.dp
    val navShadowDrop = 4.dp
    val controlShadow = 6.dp
    val controlShadowDrop = 1.dp
}
