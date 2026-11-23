package dev.doona.android.testing

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.performSemanticsAction
import dev.doona.android.designsystem.components.LayoutRole
import kotlin.math.abs

/** The scroll of the page's sheet: the text of a search field scrolls too, and is not the page. */
fun sheetScroll(): SemanticsMatcher = hasScrollAction() and !hasSetTextAction()

private fun ComposeContentTestRule.scrollOffset(): Float =
    onNode(sheetScroll()).fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()

private const val STEP = 0.9f
private const val MIN_STEP = 0.2f
private const val MOVED = 0.01f
private const val MAX_STEPS = 60

/**
 * Reads the page at each scroll offset: before the first scroll, then after each step of 80 % of the viewport until the
 * sheet stops moving. A scrolling sheet lays out all its blocks, but a pointer reaches only the part of a node that is in
 * the viewport, so the rules about touch need each block in view whole at some offset. [read] runs at once on each
 * tree, since a node's touch bounds are read from the live layout and no longer describe the offset after the next scroll.
 */
fun <T> ComposeContentTestRule.readWhileScrolling(read: (LayoutBox) -> T): List<T> {
    var tree = layoutTree()
    val reads = mutableListOf(read(tree))
    while (true) {
        val sheet = tree.descendants().first { it.role == LayoutRole.Sheet }
        val viewport = tree.viewportOf(sheet).height
        val tallest = tree.descendants().filter { it.actionable }.maxOfOrNull { it.height } ?: 0f
        val step = maxOf((viewport - tallest) * STEP, viewport * MIN_STEP) * density.density
        // The sheet moves in the viewport when it is a scrolling column and stays where it is when it is a lazy list, so the offset is read from the scroll.
        val offset = scrollOffset()
        onNode(sheetScroll()).performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, step) }
        waitForIdle()
        if (abs(scrollOffset() - offset) < MOVED) return reads
        tree = layoutTree()
        reads += read(tree)
        check(reads.size <= MAX_STEPS) { "the page still scrolls after $MAX_STEPS steps of the viewport" }
    }
}
