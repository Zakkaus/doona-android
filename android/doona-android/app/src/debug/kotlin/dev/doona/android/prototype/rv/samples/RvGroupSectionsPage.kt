package dev.doona.android.prototype.rv.samples

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.doona.android.R
import dev.doona.android.designsystem.rv.LocalFrameInsets
import dev.doona.android.designsystem.rv.RvEmptyCaption
import dev.doona.android.designsystem.rv.RvGutter
import dev.doona.android.designsystem.rv.RvPageHeader
import dev.doona.android.designsystem.rv.RvPrimaryButton
import dev.doona.android.designsystem.rv.RvScrollPage
import dev.doona.android.designsystem.rv.RvSearchField
import dev.doona.android.designsystem.rv.RvSearchSlot
import dev.doona.android.designsystem.rv.RvSpace
import dev.doona.android.feature.model.RvPolicyGroup
import dev.doona.android.feature.model.RvSubscription
import dev.doona.android.feature.nodes.ui.RvNodeSection
import dev.doona.android.feature.nodes.ui.RvRowsComposed
import dev.doona.android.feature.nodes.ui.matches
import dev.doona.android.prototype.rv.data.RvData

/**
 * A group with this many subscriptions or fewer lists every one open; with more, only the section that holds the chosen
 * node starts open. Two sections are about a screenful, so the nodes are in reach by scrolling; from three on a page of
 * open sections is several screens, and the 訂閱 rows are the index to find a section by.
 */
internal const val RV_SECTIONS_OPEN_UP_TO = 2

/**
 * nbmany v2, the selector group's page built like the node picker (節點): the page's title and caption, the chosen node's
 * chip, the search field in the page, then one card per subscription in the picker's parts ([RvNodeSection]: its 訂閱
 * row over its nodes, fastest first, the chosen node's flag checked). Typing filters the nodes and opens every section
 * that still matches; a query nothing matches says so. The page is one page: no second level, no 「全部節點」 row. A tap
 * on a node chooses it. [query] is what the search field holds to begin with.
 */
@Composable
internal fun RvGroupSectionsPage(group: RvPolicyGroup, subscriptions: List<RvSubscription>, modifier: Modifier = Modifier, query: String = "") {
    val insets = LocalFrameInsets.current ?: WindowInsets.systemBars
    val name = stringResource(group.name)
    var typed by remember { mutableStateOf(query) }
    var chosen by remember { mutableStateOf(group.selectedId) }
    val labels = group.members.associate { it.id to stringResource(it.place.label) }
    val matching = group.members.filter { it.matches(typed.trim(), labels.getValue(it.id)) }.mapTo(mutableSetOf()) { it.id }
    val holder = group.members.first { it.id == chosen }.subscription
    RvScrollPage(name, insets, modifier, onBack = {}, bottomAction = { RvPrimaryButton(stringResource(R.string.rv_policy_test_group), {}) }) { titleBlock ->
        RvPageHeader(name, titleBlock, insets, caption = { RvGroupCaption(group) }, control = { RvGroupChip(group.members.first { it.id == chosen }) })
        Column(Modifier.fillMaxWidth().padding(horizontal = RvSpace.gutter)) {
            RvSearchSlot { RvSearchField(null, typed, { typed = it }, it) }
        }
        val open = subscriptions.size <= RV_SECTIONS_OPEN_UP_TO
        subscriptions.forEach { sub ->
            val members = group.byLatency.filter { it.subscription == sub.name }
            RvNodeSection(
                sub, members, matching, labels, chosen, searching = typed.isNotBlank(), onPick = { chosen = it }, onDetail = {},
                rows = RvRowsComposed(0) { Int.MAX_VALUE }, nowEpochMs = RvData.nowEpochMs, modifier = Modifier, initiallyOpen = open || sub.name == holder,
            )
        }
        if (matching.isEmpty()) {
            RvGutter { RvEmptyCaption(sampleText("找不到符合的節點", "找不到符合的节点", "No matching nodes"), stringResource(R.string.rv_conn_none_line)) }
        }
    }
}
