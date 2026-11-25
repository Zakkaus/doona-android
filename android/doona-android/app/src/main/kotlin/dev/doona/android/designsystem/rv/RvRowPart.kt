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
