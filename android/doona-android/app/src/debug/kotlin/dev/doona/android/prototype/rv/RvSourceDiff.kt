package dev.doona.android.prototype.rv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
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

/** What a line of a diff is: kept as it was, added in the candidate, or removed from the base. */
internal enum class RvDiffKind(val sign: String, val label: Int) {
    Same("", R.string.rv_source_line),
    Added("+", R.string.rv_diff_added_line),
    Removed("−", R.string.rv_diff_removed_line),
}

/** One line of a diff: [line] is its number in the candidate when it is kept or added, in the base when removed. */
@Immutable
internal class RvDiffLine(val kind: RvDiffKind, val line: Int, val text: String)

/** One file's changes: each run of changed lines with the lines kept round it, in the file's order. */
@Immutable
internal class RvDiffFile(val source: String, val hunks: List<List<RvDiffLine>>) {
    val added = hunks.sumOf { run -> run.count { it.kind == RvDiffKind.Added } }
    val removed = hunks.sumOf { run -> run.count { it.kind == RvDiffKind.Removed } }
}

/**
 * The source diff (config plan, history and diff; also the editor's check and a template's preview): one card per file,
 * headed by [RvCardHeader] with the file and a tag of its added and removed lines, which opens the file at its first
 * change. Each line is a row: its number (the gutter of [RvSourceField]), a sign, and the text in the row subtitle's
 * 17 sp, wrapping; an added line is tinted in [RvColors.good] and a removed one in the error hue, as [RvStatusChip]
 * tints, the number and sign in that hue, so the sign and not the hue alone says which. A screen reader hears each line
 * as 新增 or 移除, its number and its text. A tap opens the file at that line ([onOpen] gets the line's number).
 * Runs are apart by [RvSpace.subGap]; their numbers show what lies between.
 */
@Composable
internal fun RvSourceDiff(file: RvDiffFile, onOpen: (Int) -> Unit, modifier: Modifier = Modifier) {
    val numbers = rvLineNumberStyle()
    val measurer = rememberTextMeasurer()
    val gutter = rvLineNumberWidth(measurer, file.hunks.maxOf { run -> run.maxOf { it.line } }, numbers)
    val signPx = remember(numbers, measurer) { measurer.measure(RvDiffKind.Removed.sign, numbers).size.width }
    val sign = with(LocalDensity.current) { signPx.toDp() }
    val first = file.hunks.first().first { it.kind != RvDiffKind.Same }.line
    RvCard(modifier) {
        RvCardHeader(file.source, onClick = { onOpen(first) }, tag = stringResource(R.string.rv_diff_counts, file.added, file.removed))
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
private fun DiffLine(line: RvDiffLine, columns: DiffColumns, onOpen: (Int) -> Unit) {
    val colors = Rv.colors
    val hue = when (line.kind) {
        RvDiffKind.Same -> null
        RvDiffKind.Added -> colors.good
        RvDiffKind.Removed -> colors.error
    }
    val label = stringResource(line.kind.label, line.line, line.text)
    val tint = hue?.let { Modifier.background(it.copy(alpha = RvAlpha.STATUS_CHIP)) } ?: Modifier
    Row(
        Modifier
            .fillMaxWidth()
            .then(tint)
            .rvRowPress(Role.Button, { onOpen(line.line) })
            .semantics(mergeDescendants = true) { contentDescription = label }
            .padding(horizontal = RvSpace.rowPadding),
    ) {
        val mark = hue ?: colors.textSecondary
        // The number and sign sit on the text's first baseline: a line with CJK in Noto sits lower in its line box.
        RvText(line.line.toString(), columns.style, Modifier.width(columns.number).alignByBaseline(), color = mark, align = TextAlign.End)
        Spacer(Modifier.width(RvSpace.tight))
        RvText(line.kind.sign, columns.style, Modifier.width(columns.sign).alignByBaseline(), color = mark, align = TextAlign.Center)
        Spacer(Modifier.width(RvSpace.tight))
        RvText(line.text, rvSourceStyle(), Modifier.weight(1f).alignByBaseline(), maxLines = Int.MAX_VALUE)
    }
}
