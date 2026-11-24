package dev.doona.android.prototype.rv

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * The two code roles the scheme has no colour for, in one theme: [number] (doona's iris) and [function] (doona's pine,
 * a call such as `domain(…)`). The other roles take the scheme's: a comment the secondary ink, a keyword the accent, a
 * string the notice amber, a key the text, an operator and punctuation [RvCodeShade.SUBTLE].
 */
@Immutable
internal class RvCodeHues(val number: Color, val function: Color)

/**
 * The code hues in light and in dark, before [rvCodeColors] darkens (light) or lightens (dark) each until it reads at
 * 4.5:1 on the page, the card and a diff's added and removed tints: violet and blue, away from the diff's green and red.
 */
internal object RvCodeHue {
    val light = RvCodeHues(number = Color(0xFF6F42C1), function = Color(0xFF0B57D0))
    val dark = RvCodeHues(number = Color(0xFFC9A6FF), function = Color(0xFF8AB4F8))
}

internal object RvCodeShade {
    /**
     * doona's subtle ink, between its muted (a comment) and its text: here the secondary ink this far toward the text,
     * so an operator or a brace recedes less than a comment and more than a key.
     */
    const val SUBTLE = 0.5f
}
