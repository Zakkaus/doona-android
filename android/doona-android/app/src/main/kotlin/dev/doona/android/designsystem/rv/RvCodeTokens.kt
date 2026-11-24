package dev.doona.android.designsystem.rv

import androidx.compose.ui.unit.dp

/** How far a softer code role sits from its theme colour toward the text. */
internal object RvCodeShade {
    /**
     * Halfway: a string is the accent this far toward the card and a number the tertiary, before each darkens (light) or
     * lightens (dark) back until it reads; punctuation is the secondary ink this far toward the text.
     */
    const val SOFT = 0.5f
}

/**
 * An error's wavy underline in the source field, as an editor draws one: a zigzag [wave] long per period, [depth] from
 * crest to trough, a [stroke] wide, its crests [drop] under the baseline, clear of the next line's caps.
 */
internal object RvSourceSize {
    val wave = 4.dp
    val depth = 2.dp
    val stroke = 1.dp
    val drop = 2.dp
}
