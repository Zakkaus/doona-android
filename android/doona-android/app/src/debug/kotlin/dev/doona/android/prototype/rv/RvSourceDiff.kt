package dev.doona.android.prototype.rv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import dev.doona.android.R
import dev.doona.android.designsystem.rv.Rv
import dev.doona.android.designsystem.rv.RvAlpha
import dev.doona.android.designsystem.rv.RvCard
import dev.doona.android.designsystem.rv.RvCardHeader
import dev.doona.android.designsystem.rv.RvCodeText
import dev.doona.android.designsystem.rv.RvSpace
import dev.doona.android.designsystem.rv.RvText
import dev.doona.android.designsystem.rv.rvLineNumberStyle
import dev.doona.android.designsystem.rv.rvLineNumberWidth
import dev.doona.android.designsystem.rv.rvRowPress
import dev.doona.android.designsystem.rv.rvSourceStyle

/** What a line of a diff is: kept as it was, added in the candidate, or removed from the base. */
internal enum class RvDiffKind(val sign: String, val label: Int) {
    Same("", R.string.rv_source_line),
    Added("+", R.string.rv_diff_added_line),
    Removed("−", R.string.rv_diff_removed_line),
}

/** One line of a diff: [line] is its number in the candidate when it is kept or added, in the base when removed. */
@Immutable
internal class RvDiffLine(val kind: RvDiffKind, val line: Int, val text: String) {
    /** Where a tap on this line opens its file: the base for a removed line, whose number counts there, else the candidate. */
    val at get() = RvDiffAt(line, base = kind == RvDiffKind.Removed)
}

/** A place a diff opens its file at: [line] in the base revision when [base], else in the candidate. */
@Immutable
internal data class RvDiffAt(val line: Int, val base: Boolean)

/** One file's changes: each run of changed lines with the lines kept round it, in the file's order. */
@Immutable
internal class RvDiffFile(val source: String, val hunks: List<List<RvDiffLine>>) {
    val added = hunks.sumOf { run -> run.count { it.kind == RvDiffKind.Added } }
    val removed = hunks.sumOf { run -> run.count { it.kind == RvDiffKind.Removed } }
}

/**
 * The source diff (config plan, history and diff; also the editor's check and a template's preview): one card per file,
 * headed by [RvCardHeader] with the file, wrapping when the path is wider than the card, and a tag of its added and
 * removed lines, which opens the file at its first change. Each line is a row: its number (the gutter of [RvSourceField]), a sign, and the text in the row subtitle's
 * 17 sp, highlighted ([RvCodeText]), wrapping (inside a long token too) and hanging under the line's own
 * indent; an added line is tinted in [RvColors.good] and a removed one in the error hue, as [RvStatusChip]
 * tints, the number and sign in that hue, so the sign and not the hue alone says which. A screen reader hears each line
 * as 新增 or 移除, its number and its text. A tap opens the file at that line ([onOpen] gets [RvDiffLine.at]: a removed
 * line in the base, as its number counts there; the others in the candidate).
 * Runs are apart by [RvSpace.subGap]; their numbers show what lies between.
 */
@Composable
internal fun RvSourceDiff(file: RvDiffFile, onOpen: (RvDiffAt) -> Unit, modifier: Modifier = Modifier) {
    val numbers = rvLineNumberStyle()
    val measurer = rememberTextMeasurer()
    val gutter = rvLineNumberWidth(measurer, file.hunks.maxOf { run -> run.maxOf { it.line } }, numbers)
    val signPx = remember(numbers, measurer) { measurer.measure(RvDiffKind.Removed.sign, numbers).size.width }
    val sign = with(LocalDensity.current) { signPx.toDp() }
    val first = file.hunks.first().first { it.kind != RvDiffKind.Same }.at
    RvCard(modifier) {
        RvCardHeader(file.source, onClick = { onOpen(first) }, tag = stringResource(R.string.rv_diff_counts, file.added, file.removed), titleLines = Int.MAX_VALUE, keepWords = false)
        file.hunks.forEachIndexed { i, run ->
            if (i > 0) Spacer(Modifier.height(RvSpace.subGap))
            run.forEach { DiffLine(it, DiffColumns(gutter, sign, numbers), onOpen) }
        }
        Spacer(Modifier.height(RvSpace.rowPadding))
    }
}

/** The widths of a diff line's number and sign, and the figures they are set in. */
private class DiffColumns(val number: Dp, val sign: Dp, val style: TextStyle)

@Composable
private fun DiffLine(line: RvDiffLine, columns: DiffColumns, onOpen: (RvDiffAt) -> Unit) {
    val colors = Rv.colors
    val hue = when (line.kind) {
        RvDiffKind.Same -> null
        RvDiffKind.Added -> colors.good
        RvDiffKind.Removed -> colors.error
    }
    val label = stringResource(line.kind.label, line.line, line.text)
    val tint = hue?.let { Modifier.background(it.copy(alpha = RvAlpha.STATUS_CHIP)) } ?: Modifier
    val mark = hue ?: colors.textSecondary
    Layout(
        {
            RvText(line.line.toString(), columns.style, Modifier.width(columns.number), color = mark, align = TextAlign.End)
            RvText(line.kind.sign, columns.style, Modifier.width(columns.sign), color = mark, align = TextAlign.Center)
            RvCodeText(line.text, rvSourceStyle())
        },
        Modifier
            .fillMaxWidth()
            .then(tint)
            .rvRowPress(Role.Button, { onOpen(line.at) })
            .semantics(mergeDescendants = true) { contentDescription = label }
            .padding(horizontal = RvSpace.rowPadding),
    ) { parts, constraints ->
        val gap = RvSpace.tight.roundToPx()
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val number = parts[0].measure(loose)
        val sign = parts[1].measure(loose)
        val textStart = number.width + gap + sign.width + gap
        val text = parts[2].measure(loose.copy(maxWidth = (constraints.maxWidth - textStart).coerceAtLeast(0)))
        // The row is a whole number of line boxes (the number's one line is one box), so a line with CJK, whose Noto
        // runs a pixel or two past the box, is as tall as one without; the text centres in it, and the number and
        // sign sit on the text's first baseline.
        val box = number.height.coerceAtLeast(1)
        val height = (text.height + box / 2) / box * box
        val textY = (height - text.height) / 2
        val numberY = textY + text[FirstBaseline] - number[FirstBaseline]
        layout(constraints.maxWidth, height.coerceAtLeast(box)) {
            number.place(0, numberY)
            sign.place(number.width + gap, numberY)
            text.place(textStart, textY)
        }
    }
}
