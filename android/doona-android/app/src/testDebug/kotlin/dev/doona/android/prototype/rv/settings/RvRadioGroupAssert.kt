package dev.doona.android.prototype.rv.settings

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.isSelectable

/** The page's [rows] radio rows, every one inside one selectable group, so a screen reader tells each row's place. */
internal fun SemanticsNodeInteractionsProvider.assertOneRadioGroup(rows: Int) {
    val group = SemanticsMatcher.keyIsDefined(SemanticsProperties.SelectableGroup)
    onAllNodes(group).assertCountEquals(1)
    onAllNodes(isSelectable()).assertCountEquals(rows)
    onAllNodes(isSelectable() and hasAnyAncestor(group)).assertCountEquals(rows)
}
