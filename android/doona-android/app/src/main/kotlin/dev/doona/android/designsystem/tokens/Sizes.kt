package dev.doona.android.designsystem.tokens

import androidx.compose.ui.unit.dp

/** Component metrics: the sample's rows, discs and badge, then the U1 metrics of components the sample has no match for. */
object SizeScale {
    /** A round action's disc (measured §6: 48 dp; A03). */
    val actionDisc = 48.dp

    /** Material Symbols draw in a 20 dp live area of their 24 dp box, the width of #11's '+'. */
    val actionGlyph = 24.dp

    /** A row's leading visual, disc or flag, and the glyph in a disc: about 18 dp of ink (measured §7, §12.2; A05, N12, X-08). */
    val rowDisc = 40.dp
    val rowGlyph = 22.dp

    /** A row with a subtitle, and one without (measured §7: 82 and 58 dp; S04). */
    val rowTwoLine = 82.dp
    val rowOneLine = 58.dp
    val inlineGlyph = 16.dp

    /** A status dot: the sample's dot, and U1's small status light. */
    val dot = 8.dp

    /** A list card's 'See all' row (S7-12, Revolut's 56 dp). */
    val seeAll = 56.dp

    /** Android's touch target and row minimum. */
    val touch = 48.dp
    val navGlyph = 24.dp
    val progress = 5.dp

    /** The current node's check (measured §7 `avatar_badgeBottom`): a 16 dp disc, its ring in the card's colour (S7-06). */
    val badge = 16.dp
    val badgeRing = 2.dp
    val badgeCheck = 1.5.dp
    val badgeOverhang = 2.dp

    /** A hairline: a flag's ring, the back disc's ring in light, the dark capsule's rim, an outline. */
    val ring = 1.dp

    // U1 metrics (sketch-doona TOKENS.md §5-§8), kept while an old component still draws with them.

    val control = 36.dp
    val topBar = 64.dp
    val bottomBar = 64.dp
    val railWidth = 80.dp
    val logo = 28.dp
    val icon = 25.dp
    val iconSmall = 20.dp
    val hubPillWidth = 56.dp
    val hubPillHeight = 32.dp
    val borderStrong = 2.dp

    /** U1's status light dot (§7: 10 px), larger than the sample's [dot]. */
    val statusDot = 10.dp
    val badgeHeight = 20.dp
    val nodeTileMin = 56.dp
    val tabUnderline = 2.dp
    val spark = 32.dp
    val sparkStroke = 1.5.dp
    val flag = 12.dp
    val flagLetters = 5.dp
    val flagEdge = 0.5.dp
    val chartInset = 2.dp

    /** The launcher icon in a list row: the size of Android's list icon, and of the sample's row disc. */
    val appIcon = rowDisc

    /** A checkbox's box: doona's 14 grown to a touch size, like its 20 to 25 icons (§6). */
    val checkbox = 20.dp
    val meterTrack = 4.dp

    /** The width of the busy spinner's stroke. */
    val spinnerStroke = 2.dp

    /**
     * The edge of a field's text that fades where the text runs on past the box, at the normal font size: more than
     * twice a glyph, so that the glyph cut at the edge, and the one before it, are already faint. It grows with the text line.
     */
    val fieldFade = 32.dp
    val emptyIcon = 48.dp

    /** Widest dialog; a narrower window leaves the gutter at both sides. */
    val dialogMax = 560.dp

    /** Widest content column: wider windows centre it (D20). */
    val contentMax = 600.dp

    /** Windows shorter than this swap the bottom bar for a rail at the start edge (Material's compact height). */
    val compactHeight = 480.dp
}

/** Radii. */
object RadiusScale {
    /** Every card's corners, 設定's and 主題's included, and a page sheet's top (measured §2: 20 dp; X-02, A12, T12, N14, S8, N-06). */
    val card = 20.dp

    // U1 radii (§5, `foundations.css:47-53`) of the badge, the control and the checkbox.
    val xs = 2.dp
    val sm = 6.dp
    val md = 8.dp
    const val PILL_PERCENT = 50
}

/** The one chevron (measured §7; S5-06, X-07): 6.7 × 12.2 dp of 2 dp stroke in a 24 dp box. */
object ChevronScale {
    val box = 24.dp
    val width = 6.7.dp
    val height = 12.2.dp
    val stroke = 2.dp
}

/** A card's in-card header (measured §7 `listSubheader`): a 48 dp row (X-05). */
object HeaderScale {
    val row = 48.dp
}
