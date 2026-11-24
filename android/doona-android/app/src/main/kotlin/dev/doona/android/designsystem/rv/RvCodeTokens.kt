package dev.doona.android.designsystem.rv

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * The hue of each code role in one theme, one colour per role as a code editor gives it (owner 2026-11-24: 「高亮的顏色是
 * 個和一般編輯器一樣」): a [comment], a [keyword] (a section's name, where a rule sends), a [string], a [number], a call
 * ([function], `domain(…)`), a [key] before its colon, and [punctuation] with the operators.
 */
@Immutable
internal class RvCodeHues(
    val comment: Color,
    val keyword: Color,
    val string: Color,
    val number: Color,
    val function: Color,
    val key: Color,
    val punctuation: Color,
)

/**
 * The code hues in light and in dark: Visual Studio Code's default Light+ and Dark+ themes, the colours most editors
 * share (green comments, blue keywords, red or orange strings, green numbers, brown or yellow calls, navy or light blue
 * keys, the editor's ink for punctuation). [rvCodeColors] then darkens (light) or lightens (dark) each until it reads at
 * 4.5:1 on the page, the card and a diff's added and removed tints. They replace doona web's daeTokens colours on Android.
 */
internal object RvCodeHue {
    val light = RvCodeHues(
        comment = Color(0xFF008000),
        keyword = Color(0xFF0000FF),
        string = Color(0xFFA31515),
        number = Color(0xFF098658),
        function = Color(0xFF795E26),
        key = Color(0xFF001080),
        punctuation = Color(0xFF3B3B3B),
    )
    val dark = RvCodeHues(
        comment = Color(0xFF6A9955),
        keyword = Color(0xFF569CD6),
        string = Color(0xFFCE9178),
        number = Color(0xFFB5CEA8),
        function = Color(0xFFDCDCAA),
        key = Color(0xFF9CDCFE),
        punctuation = Color(0xFFD4D4D4),
    )
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
