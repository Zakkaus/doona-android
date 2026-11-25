package dev.doona.android.feature.config

import dev.doona.android.designsystem.rv.RvDiffFile
import dev.doona.android.designsystem.rv.RvDiffKind
import dev.doona.android.designsystem.rv.RvDiffLine

/**
 * The line diff of one file, [base] against [candidate], as the source diff draws it (config-plan.md: "the editor reads
 * base and candidate bytes for the diff"). Lines are matched by their longest common run; each run of changes keeps one
 * line round it, and one more where that next line opens the block above the change (it ends in `{`) or closes the
 * block below it (it is `}`), so a group's change shows the group it is in. Runs whose kept lines meet are one hunk.
 * Removed lines come before the lines added in their place. Null when nothing changed.
 */
internal fun rvLineDiff(source: String, base: String, candidate: String): RvDiffFile? {
    val a = base.lines()
    val b = candidate.lines()
    val edits = edits(a, b)
    val changed = edits.indices.filter { edits[it].kind != RvDiffKind.Same }
    if (changed.isEmpty()) return null
    val shown = BooleanArray(edits.size)
    changed.forEach { i ->
        shown[i] = true
        reach(edits, i, -1)?.let { shown[it] = true }
        reach(edits, i, 1)?.let { shown[it] = true }
    }
    val hunks = mutableListOf<MutableList<RvDiffLine>>()
    edits.forEachIndexed { i, line ->
        if (!shown[i]) return@forEachIndexed
        if (i == 0 || !shown[i - 1]) hunks.add(mutableListOf())
        hunks.last().add(line)
    }
    return RvDiffFile(source, hunks)
}

/** The farthest kept line round the change at [i] in [step]'s direction: one line, or two to the block's own brace. */
private fun reach(edits: List<RvDiffLine>, i: Int, step: Int): Int? {
    val next = i + step
    val first = edits.getOrNull(next)?.takeIf { it.kind == RvDiffKind.Same } ?: return null
    val brace = edits.getOrNull(next + step)?.takeIf { it.kind == RvDiffKind.Same }?.text?.trim()
    val block = if (step < 0) brace?.endsWith("{") == true && !first.text.trim().endsWith("{") else brace == "}" && first.text.trim() != "}"
    return if (block) next + step else next
}

/** Every line of [a] and [b] in order: kept (numbered in [b]), removed (numbered in [a]) or added (numbered in [b]). */
private fun edits(a: List<String>, b: List<String>): List<RvDiffLine> {
    // keep[i][j]: the longest common run of a[i..] and b[j..].
    val keep = Array(a.size + 1) { IntArray(b.size + 1) }
    for (i in a.indices.reversed()) {
        for (j in b.indices.reversed()) keep[i][j] = if (a[i] == b[j]) keep[i + 1][j + 1] + 1 else maxOf(keep[i + 1][j], keep[i][j + 1])
    }
    val out = mutableListOf<RvDiffLine>()
    val added = mutableListOf<RvDiffLine>()
    var i = 0
    var j = 0
    fun flush() {
        out.addAll(added)
        added.clear()
    }
    while (i < a.size || j < b.size) {
        when {
            i < a.size && j < b.size && a[i] == b[j] -> {
                flush()
                out.add(RvDiffLine(RvDiffKind.Same, j + 1, b[j]))
                i++
                j++
            }

            j < b.size && (i == a.size || keep[i][j + 1] >= keep[i + 1][j]) -> added.add(RvDiffLine(RvDiffKind.Added, j + 1, b[j++]))

            else -> out.add(RvDiffLine(RvDiffKind.Removed, i + 1, a[i++]))
        }
    }
    flush()
    return out
}
