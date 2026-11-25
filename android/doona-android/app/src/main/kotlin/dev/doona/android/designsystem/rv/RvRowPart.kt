package dev.doona.android.designsystem.rv

/**
 * Test tags of a row's parts, which the row rules gate (`RvRowRules`, `docs/LAYOUT-RULES.md`) finds on every golden:
 * the leading visual by its kind, the trailing controls, and the row-like parts that are not an [RvRow]. Tags only:
 * each sits in a modifier chain that already measures the part, so no part moves.
 */
internal object RvRowPart {
    /** A glyph on the raised disc ([RvIconDisc]). */
    const val ICON_DISC = "rv-icon-disc"

    /** A country's flag ([RvFlag]). */
    const val FLAG = "rv-flag"

    /** An app's launcher icon in the disc ([RvAppDisc]). */
    const val APP_DISC = "rv-app-disc"

    /** A bare glyph ([RvGlyph]). */
    const val GLYPH = "rv-glyph"

    /** A line's own end of a line-wise [RvRow] (a figure beside the title, a change beside the subtitle): part of that line for the gate. */
    const val LINE_END = "rv-line-end"

    /** A touch target that reaches past what it draws (the node picker's info mark): the gate reads its ink, not its box. */
    const val TARGET = "rv-target"

    /**
     * A row's trailing room: the box its end is laid out in, which the end's visible parts may not fill (a latency figure
     * without its change, a busy bar standing in for it). The gate centres and measures the room, not the ink in it.
     */
    const val END = "rv-end"

    /** A title of honk code ([RvCodeText]): it keeps the code view's own line boxes, so the gate reads no first line of a role's from it. */
    const val CODE = "rv-code"

    /**
     * A row's text shown whole, with no line limit and no ellipsis, by an owner's exception to R14 ([RvWhole]); the tag
     * is `rv-whole:<id>`, the exception's id in LAYOUT-RULES.md, and the gate holds the text uncut instead of to the
     * role's lines only when that id is in the file's Exceptions table.
     */
    const val WHOLE = "rv-whole"

    const val CHEVRON = "rv-chevron"
    const val SWITCH = "rv-switch"
    const val RADIO = "rv-radio"
    const val CHECK = "rv-check"

    /** A notice card's disc and text ([RvBanner]). */
    const val NOTICE_ROW = "rv-notice-row"

    /** An item of a long-press menu ([RvLongPressMenu]). */
    const val MENU_ROW = "rv-menu-row"

    /** A settings row's line of parts, inside its [RvPart.ROW] (`RvSettingsRow`). */
    const val SETTINGS_ROW = "rv-settings-row"
}

/**
 * The owner's exceptions to R14 that show a row's text whole (no line limit, no ellipsis), each a row of LAYOUT-RULES.md's
 * Exceptions by its id. A row's text is tagged [RvRowPart.WHOLE] only through one of these, never by its line count
 * alone: a text of [Int.MAX_VALUE] lines with none is held to R14, and the gate fails on an id the file does not list.
 */
internal enum class RvWhole(val id: String) {
    /** A configuration revision's own name, the user's text (owner, 2026-11-25 18:55). */
    RevisionName("q-s1125c-vname"),
}
