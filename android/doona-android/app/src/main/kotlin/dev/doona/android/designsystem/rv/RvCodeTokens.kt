package dev.doona.android.designsystem.rv

import androidx.compose.ui.unit.dp

/** How the code kinds' inks derive from the theme's accent and tertiary ([rvCodeColors]). */
internal object RvCodeShade {
    /** A literal's step from the accent toward the page, and an operator's from a key's colour away from it (Oklab L). */
    const val STEP = 0.12f

    /** A key's colour: halfway from the accent to the tertiary, in Oklab, as Compose blends. */
    const val MID = 0.5f
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
