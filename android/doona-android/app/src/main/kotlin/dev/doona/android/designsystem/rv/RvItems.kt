package dev.doona.android.designsystem.rv

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

/**
 * [items] of a column, each keyed by [key], that come, go and move instead of jumping. A row new since the last change
 * grows to its height and fades in over [RvMotion.ITEM_MS], rows arriving together [RvMotion.STAGGER_MS] apart (the
 * first [RvMotion.STAGGER_ROWS], then together); a row gone shrinks and fades out where it stood; when the rows that
 * stay change order, each moves to its new place ([rvMoves]). The rows shown first stand at rest, so a page opens and
 * renders as it always did; at animator scale 0 every change is immediate.
 */
@Composable
internal fun <T> ColumnScope.RvItems(items: List<T>, key: (T) -> Any, content: @Composable (T) -> Unit) {
    val motion = Rv.motion
    val list = remember { RvItemList<T>() }
    val rows = list.see(items, key)
    rows.forEach { row ->
        key(row.key) {
            val delay = row.order.coerceAtMost(RvMotion.STAGGER_ROWS) * RvMotion.STAGGER_MS
            val enter = if (motion.instant) {
                EnterTransition.None
            } else {
                fadeIn(motion.tween(RvMotion.ITEM_MS, RvMotion.Emphasized, delay)) +
                    expandVertically(motion.tween(RvMotion.ITEM_MS, RvMotion.Emphasized, delay), expandFrom = Alignment.Top)
            }
            val exit = if (motion.instant) {
                ExitTransition.None
            } else {
                fadeOut(motion.tween(RvMotion.ITEM_MS, RvMotion.Accelerate)) +
                    shrinkVertically(motion.tween(RvMotion.ITEM_MS, RvMotion.Accelerate), shrinkTowards = Alignment.Top)
            }
            AnimatedVisibility(row.shown, Modifier.rvMoves(list.reordered), enter = enter, exit = exit) { content(row.item) }
        }
    }
}

/** A row [RvItems] shows: [item] under [key], [shown] while it is in the list, [order] among the rows that arrived with it. */
private class RvItemRow<T>(val key: Any, var item: T, val shown: MutableTransitionState<Boolean>, var order: Int) {
    val gone: Boolean get() = shown.isIdle && !shown.currentState && !shown.targetState
}

/** The rows [RvItems] shows for the last list it saw: those in it, in its order, and those leaving, where they stood. */
private class RvItemList<T> {
    private var seen: List<T>? = null
    private var rows: List<RvItemRow<T>> = emptyList()

    /** Whether the last change moved rows that stayed. */
    var reordered = false
        private set

    fun see(items: List<T>, key: (T) -> Any): List<RvItemRow<T>> {
        if (items == seen) return rows
        val first = seen == null
        seen = items
        val keys = items.map(key)
        val now = keys.toSet()
        val before = rows.filterNot { it.gone }
        val byKey = before.associateBy { it.key }
        reordered = keys.filter { it in byKey } != before.filter { it.key in now && it.shown.targetState }.map { it.key }
        var arriving = 0
        val result = items.mapIndexedTo(mutableListOf()) { i, item ->
            val row = byKey[keys[i]]
            if (row != null) {
                row.item = item
                row.order = 0
                row.shown.targetState = true
                row
            } else {
                RvItemRow(keys[i], item, MutableTransitionState(first).apply { targetState = true }, if (first) 0 else arriving++)
            }
        }
        before.forEachIndexed { i, row ->
            if (row.key !in now) {
                row.shown.targetState = false
                val after = before.getOrNull(i - 1)?.key
                result.add(if (after == null) 0 else result.indexOfFirst { it.key == after } + 1, row)
            }
        }
        rows = result
        return rows
    }
}
