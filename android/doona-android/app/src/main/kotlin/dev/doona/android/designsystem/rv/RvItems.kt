package dev.doona.android.designsystem.rv

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * [items], each keyed by [key], one under another [gap] apart, that come, go and move instead of jumping. A row new since
 * the last change grows to its height and fades in over [RvMotion.ITEM_MS], rows arriving together [RvMotion.STAGGER_MS]
 * apart (the first [RvMotion.STAGGER_ROWS], then together); a row gone shrinks and fades out where it stood, and leaves
 * the list when it has; when the rows that stay change order, each moves to its new place ([rvMoves]). The gap next to a
 * row grows and shrinks with it, so a row coming or going never leaves a gap behind or doubles one: put the rows' gap
 * here, not in the parent's arrangement. [empty], if given, stands in for the rows while there are none, and comes and
 * goes as a row does. The rows shown first stand at rest, so a page opens and renders as it always did; at animator
 * scale 0 every change is immediate.
 */
@Composable
internal fun <T> RvItems(items: List<T>, key: (T) -> Any, gap: Dp = 0.dp, empty: (@Composable () -> Unit)? = null, content: @Composable (T) -> Unit) {
    val motion = Rv.motion
    val list = remember { RvItemList(items, key, empty != null) }
    // Rows come and go after the frame that saw the change, never while composing; a row's own data is the latest.
    LaunchedEffect(items, empty != null) { list.see(items, key, empty != null) }
    val latest = items.associateBy(key)
    val rows = list.rows
    val reordered = list.reordered
    Layout(
        content = {
            rows.forEach { row ->
                key(row.key) {
                    LaunchedEffect(row, row.shown) {
                        val target = if (row.shown) 1f else 0f
                        val delay = row.order.coerceAtMost(RvMotion.STAGGER_ROWS) * RvMotion.STAGGER_MS
                        val spec = if (row.shown) motion.tween<Float>(RvMotion.ITEM_MS, RvMotion.Emphasized, delay) else motion.tween(RvMotion.ITEM_MS, RvMotion.Accelerate)
                        row.progress.animateTo(target, spec)
                        if (!row.shown) list.drop(row)
                    }
                    Box(Modifier.rvMoves(reordered).rvReveal { row.progress.value }) {
                        if (row.key === RvItemList.EMPTY) {
                            empty?.invoke()
                        } else {
                            @Suppress("UNCHECKED_CAST")
                            content(latest[row.key] ?: row.item as T)
                        }
                    }
                }
            }
        },
    ) { measurables, constraints ->
        rvStack(measurables, constraints, gap.roundToPx()) { rows.getOrNull(it)?.progress?.value ?: 1f }
    }
}

/**
 * Lays out [measurables] one under another: the gap before each is [gap] times how far it is shown and how far the
 * rows above it are, so a row at 0 has none on either side and the first row shown none above it.
 */
private fun MeasureScope.rvStack(measurables: List<Measurable>, constraints: Constraints, gap: Int, shown: (Int) -> Float): MeasureResult {
    val placeables = measurables.map { it.measure(constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity)) }
    val tops = IntArray(placeables.size)
    var y = 0
    var above = 0f
    placeables.forEachIndexed { i, placeable ->
        val share = shown(i)
        if (i > 0) y += (gap * share * above).roundToInt()
        tops[i] = y
        y += placeable.height
        above = maxOf(above, share)
    }
    val width = maxOf(constraints.minWidth, placeables.maxOfOrNull { it.width } ?: 0)
    return layout(width, constraints.constrainHeight(y)) { placeables.forEachIndexed { i, it -> it.place(0, tops[i]) } }
}

/** [progress] of its height shown from the top, the rest clipped, and as opaque: a row growing in or shrinking out. */
private fun Modifier.rvReveal(progress: () -> Float): Modifier = graphicsLayer {
    val p = progress()
    alpha = p
    clip = p < 1f
}.layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    layout(placeable.width, (placeable.height * progress()).roundToInt()) { placeable.place(0, 0) }
}

/** A row [RvItems] shows: [item] under [key], [shown] while it is in the list, [order] among the rows that arrived with it. */
private class RvItemRow(val key: Any, var item: Any?, shown: Boolean, val order: Int, val progress: Animatable<Float, AnimationVector1D>) {
    var shown by mutableStateOf(shown)
}

/** The rows [RvItems] shows for the last list it saw: those in it, in its order, and those leaving, where they stood. */
private class RvItemList<T>(items: List<T>, key: (T) -> Any, empty: Boolean) {
    private var seen: List<T> = items
    private var seenEmpty = empty

    var rows by mutableStateOf(keys(items, key, empty).mapIndexed { i, k -> RvItemRow(k, items.getOrNull(i), true, 0, Animatable(1f)) })
        private set

    /** Whether the last change moved rows that stayed. */
    var reordered by mutableStateOf(false)
        private set

    fun see(items: List<T>, key: (T) -> Any, empty: Boolean) {
        if (items == seen && empty == seenEmpty) return
        seen = items
        seenEmpty = empty
        val keys = keys(items, key, empty)
        val now = keys.toSet()
        val before = rows
        val byKey = before.associateBy { it.key }
        reordered = keys.filter { it in byKey } != before.filter { it.key in now && it.shown }.map { it.key }
        var arriving = 0
        val result = keys.mapIndexedTo(mutableListOf()) { i, k ->
            val item = items.getOrNull(i)
            byKey[k]?.also {
                it.item = item
                it.shown = true
            } ?: RvItemRow(k, item, true, arriving++, Animatable(0f))
        }
        before.forEachIndexed { i, row ->
            if (row.key !in now) {
                row.shown = false
                val after = before.getOrNull(i - 1)?.key
                result.add(if (after == null) 0 else result.indexOfFirst { it.key == after } + 1, row)
            }
        }
        rows = result
    }

    /** Takes [row] out once it has gone, unless it came back meanwhile. */
    fun drop(row: RvItemRow) {
        if (!row.shown) rows = rows - row
    }

    private fun keys(items: List<T>, key: (T) -> Any, empty: Boolean): List<Any> = if (items.isEmpty() && empty) listOf(EMPTY) else items.map(key)

    companion object {
        /** The key of the stand-in shown while there are no rows. */
        val EMPTY = Any()
    }
}
