package dev.doona.android.designsystem.rv

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.AlignmentLine
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import kotlin.math.max
import kotlin.math.min

// RvRow's text: the one row rule's line counts, a row's text as it is laid out and measured, and a line-wise line.

/**
 * The one row rule, every list row's ([RvRow] and the settings rows), as Revolut's measured rows (`07-set-interface.xml`,
 * `07-privacy.xml`): the title wraps to two lines and the subtitle to three, the last ending in an ellipsis; the
 * subtitle's line box starts [RvSpace.titleToSubtitle] under the title's (owner, q-s1125c-titlegap).
 */
internal const val RV_ROW_TITLE_LINES = 2
internal const val RV_ROW_SUBTITLE_LINES = 3

/** The share of a line a row's title keeps, if it needs it, before the end of the line takes the rest. */
internal const val RV_ROW_TITLE_SHARE = 0.4f

/**
 * One line of a line-wise [RvRow]: [start] keeps its own width up to [RV_ROW_TITLE_SHARE] of the line, [end] takes what it
 * needs of the rest, and [start] then gets what the end leaves. Where [end] does not fit whole on that line, or [start]
 * ([text], when given) does not read well beside it or would take more lines there than on the whole line, [end] goes
 * under [start] on the line's end and each has the whole line, so neither is cut or squeezed; the middle of its line is
 * then [RvEndUnder] on the title's line, whose end is the row's [figure].
 */
@Composable
internal fun RowLine(end: (@Composable () -> Unit)?, text: RowText?, figure: Boolean = false, start: @Composable () -> Unit) {
    val measurer = rememberTextMeasurer()
    Layout({
        start()
        end?.invoke()
    }) { parts, constraints ->
        val width = constraints.maxWidth
        val gap = if (parts.size > 1) RvSpace.trailingGap.roundToPx() else 0
        val keep = min(parts[0].maxIntrinsicWidth(constraints.maxHeight), (width * RV_ROW_TITLE_SHARE).toInt())
        val room = max(0, width - keep - gap)
        val endWidth = parts.getOrNull(1)?.maxIntrinsicWidth(constraints.maxHeight)
        val beside = endWidth == null || (endWidth <= room && (text == null || measurer.keeps(text, max(0, width - endWidth - gap), width)))
        val endPart = parts.getOrNull(1)?.measure(Constraints(maxWidth = if (beside) room else width))
        val startPart = parts[0].measure(Constraints(maxWidth = if (beside) max(0, width - (endPart?.width ?: 0) - gap) else width))
        if (beside) {
            val height = max(startPart.height, endPart?.height ?: 0)
            layout(width, height) {
                startPart.place(0, (height - startPart.height) / 2)
                endPart?.let { it.place(width - it.width, (height - it.height) / 2) }
            }
        } else {
            val line: Map<AlignmentLine, Int> = endPart?.takeIf { figure }?.let { mapOf(RvEndUnder to startPart.height + it.height / 2) } ?: emptyMap()
            layout(width, startPart.height + (endPart?.height ?: 0), line) {
                startPart.place(0, 0)
                endPart?.let { it.place(width - it.width, startPart.height) }
            }
        }
    }
}

/**
 * A row's text as it is laid out: its words, its role and [color], how many lines it may take, whether its words are held
 * ([RvText]'s `keepWords`), the exception that shows it whole, and whether it is honk [code] ([RvCodeText]: a skipped
 * declaration, which keeps the code view's line boxes); [words] is the text as its [RvText] takes it ([rvWords]), taken
 * once for the text.
 */
internal class RowText(val text: String, val style: TextStyle, val lines: Int, val keepWords: Boolean, val whole: RvWhole?, val color: Color, val code: Boolean) {
    val words = rvWords(text, lines, keepWords)

    /** The text in its role's Latin line box (code in its own), as the row shows it. */
    @Composable
    fun Shown(overflow: TextOverflow = TextOverflow.Ellipsis) = if (code) {
        RvCodeText(text, style, maxLines = lines)
    } else {
        RvText(text, style, rvWhole(whole), color = color, maxLines = lines, overflow = overflow, lineBox = true, keepWords = keepWords)
    }
}

/**
 * A row text tagged [RvRowPart.WHOLE] with [whole]'s id where it has no limit by that exception. The row checks the
 * exception against the text's lines once, on entry ([rvRequireWhole]).
 */
internal fun rvWhole(whole: RvWhole?): Modifier = whole?.let { Modifier.testTag("${RvRowPart.WHOLE}:${it.id}") } ?: Modifier

/**
 * A text given an exception [whole] takes [Int.MAX_VALUE] lines; one of [lines] lines would be cut all the same, so the
 * exception would do nothing: an error.
 */
internal fun rvRequireWhole(whole: RvWhole?, lines: Int) =
    require(whole == null || lines == Int.MAX_VALUE) { "a text given $whole takes $lines lines: a text shown whole takes Int.MAX_VALUE" }

@Composable
internal fun rememberRowText(text: String, style: TextStyle, lines: Int, keepWords: Boolean, whole: RvWhole?, color: Color, code: Boolean = false) =
    remember(text, style, lines, keepWords, whole, color, code) { RowText(text, style, lines, keepWords, whole, color, code) }

/** [text] laid out [width] px wide as its [RvText] lays it out. */
internal fun TextMeasurer.layout(text: RowText, width: Int) = rvLayout(text.words, text.style, text.lines, width)

/**
 * Whether [text] reads well [width] px wide ([rvReadsWell]) on no more lines than it takes [wide] px wide: an end beside a
 * line may not make it wrap (`VLESS ·` over `臺灣`, `Hysteria` over `2`) where the whole line holds it.
 */
private fun TextMeasurer.keeps(text: RowText, width: Int, wide: Int): Boolean {
    val narrow = layout(text, width)
    return rvReadsWell(narrow) && (width >= wide || narrow.lineCount <= layout(text, wide).lineCount)
}
