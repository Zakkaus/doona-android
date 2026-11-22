package dev.doona.android.prototype.rv.nodes

import android.content.res.Configuration
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import dev.doona.android.R
import dev.doona.android.designsystem.theme.LocalFrameInsets
import dev.doona.android.feature.NOT_WIRED
import dev.doona.android.prototype.rv.Rv
import dev.doona.android.prototype.rv.RvCaption
import dev.doona.android.prototype.rv.RvCard
import dev.doona.android.prototype.rv.RvDelta
import dev.doona.android.prototype.rv.RvFlag
import dev.doona.android.prototype.rv.RvGlassButton
import dev.doona.android.prototype.rv.RvGutter
import dev.doona.android.prototype.rv.RvPageHeader
import dev.doona.android.prototype.rv.RvRow
import dev.doona.android.prototype.rv.RvScrollPage
import dev.doona.android.prototype.rv.RvSearchPill
import dev.doona.android.prototype.rv.RvSeeAll
import dev.doona.android.prototype.rv.RvSpace
import dev.doona.android.prototype.rv.RvSubscriptionRow
import dev.doona.android.prototype.rv.RvText
import dev.doona.android.prototype.rv.RvTheme
import dev.doona.android.prototype.rv.data.RvData
import dev.doona.android.prototype.rv.data.RvNode
import dev.doona.android.prototype.rv.data.RvNodeGroup
import dev.doona.android.prototype.rv.data.RvSubscription
import dev.doona.android.prototype.rv.data.rvNodeGroups
import dev.doona.android.prototype.rv.rvSystemScheme

/** The node list's control row: the search pill (the sample does not type into it). */
@Composable
internal fun RvNodeListControls() {
    RvSearchPill(NOT_WIRED, Modifier.fillMaxWidth())
}

/**
 * The node list (the picker under 全域 and the main node's page, rv-final's pull-down picker): one card per
 * subscription, its 訂閱 row (use against quota, end date, what is left) over its nodes in 節點's rows, the [currentId]
 * node's flag checked. Each card shows its first [ROWS] and the current node wherever it falls, then 查看全部 opens the
 * rest in place. A tap on a node picks it ([onPick]), and the check moves to it.
 */
@Composable
internal fun RvNodeGroups(groups: List<RvNodeGroup>, currentId: String, onPick: (String) -> Unit) {
    RvGutter {
        groups.forEach { key(it.key) { GroupCard(it, currentId, onPick) } }
    }
}

@Composable
private fun GroupCard(group: RvNodeGroup, currentId: String, onPick: (String) -> Unit) {
    var all by rememberSaveable(group.key) { mutableStateOf(false) }
    val shown = if (all) group.nodes else group.nodes.take(ROWS) + group.nodes.drop(ROWS).filter { it.id == currentId }
    val count = group.nodes.size
    RvCard {
        RvSubscriptionRow(group.subscription)
        shown.forEach { key(it.id) { NodeRow(it, it.id == currentId, onPick) } }
        if (shown.size < count) RvSeeAll(pluralStringResource(R.plurals.rv_nodes_see_all, count, count)) { all = true }
    }
}

/** 節點's row as a choice: the flag (checked when [current]), name, protocol and place, latency over its change. */
@Composable
private fun NodeRow(node: RvNode, current: Boolean, onPick: (String) -> Unit) {
    val inUse = stringResource(R.string.rv_nodes_in_use)
    val pick = Modifier.selectable(current, interactionSource = null, indication = ripple(color = Rv.colors.stateLayer), role = Role.RadioButton) { onPick(node.id) }
    RvRow(
        { RvFlag(node.place.name, RvNodesSize.rowFlag, current = current) },
        node.name,
        node.protocol + SEPARATOR + stringResource(node.place.label),
        if (current) pick.semantics { stateDescription = inUse } else pick,
        titleEnd = { RvText(latencyText(node.latencyMs), Rv.type.rowValue, align = TextAlign.End) },
        subtitleEnd = node.changeMs?.let { { RvDelta(it) } },
    )
}

/** As many rows as a policy group's page shows before its 查看全部: the list is one to pick from. */
private const val ROWS = 8

/**
 * The main node's picker, pushed from 規則's 主節點 card: the back disc and 測試延遲 in the bar, 主節點 as the large title
 * over what the choice does, the search pill, then the node list. A tap picks the node and stays, the check moving to it.
 */
@Composable
internal fun RvNodePickerPage(
    nodes: List<RvNode>,
    subscriptions: List<RvSubscription>,
    currentId: String,
    onPick: (String) -> Unit,
    onBack: () -> Unit,
    onTest: () -> Unit,
) {
    val insets = LocalFrameInsets.current ?: WindowInsets.systemBars
    val title = stringResource(R.string.rv_main_node)
    val test = stringResource(R.string.rv_test_latency)
    val groups = remember(nodes, subscriptions) { rvNodeGroups(nodes, subscriptions) }
    RvScrollPage(title, insets, onBack = onBack, actions = { glass -> RvGlassButton(glass, R.drawable.rv_sym_speed, test, onTest) }) { titleBlock ->
        RvPageHeader(title, titleBlock, insets, caption = { RvCaption(stringResource(R.string.rv_main_node_hint)) }, control = { RvNodeListControls() })
        RvNodeGroups(groups, currentId, onPick)
        Spacer(Modifier.height(RvSpace.cardGap))
        Spacer(Modifier.windowInsetsBottomHeight(insets))
    }
}

@Preview(name = "Light", locale = "zh-rTW", widthDp = 412, heightDp = 915)
@Preview(name = "Dark", locale = "zh-rTW", widthDp = 412, heightDp = 915, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun RvNodePickerPagePreview() {
    val dark = isSystemInDarkTheme()
    var current by remember { mutableStateOf(RvData.CURRENT_ID) }
    RvTheme(rvSystemScheme(dark), dark) { RvNodePickerPage(RvData.nodes, RvData.subscriptions, current, { current = it }, onBack = {}, onTest = {}) }
}
