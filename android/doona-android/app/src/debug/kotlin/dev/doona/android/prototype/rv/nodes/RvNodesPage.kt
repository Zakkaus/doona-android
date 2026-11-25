package dev.doona.android.prototype.rv.nodes

import android.content.res.Configuration
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import dev.doona.android.R
import dev.doona.android.designsystem.rv.LocalFrameInsets
import dev.doona.android.designsystem.rv.Rv
import dev.doona.android.designsystem.rv.RvCaption
import dev.doona.android.designsystem.rv.RvCard
import dev.doona.android.designsystem.rv.RvCardHeader
import dev.doona.android.designsystem.rv.RvChipPick
import dev.doona.android.designsystem.rv.RvEmptyState
import dev.doona.android.designsystem.rv.RvFilterChipRow
import dev.doona.android.designsystem.rv.RvFlag
import dev.doona.android.designsystem.rv.RvGutter
import dev.doona.android.designsystem.rv.RvLatencyChange
import dev.doona.android.designsystem.rv.RvMenuAnchor
import dev.doona.android.designsystem.rv.RvMotion
import dev.doona.android.designsystem.rv.RvPageHeader
import dev.doona.android.designsystem.rv.RvRegion
import dev.doona.android.designsystem.rv.RvRow
import dev.doona.android.designsystem.rv.RvScrollPage
import dev.doona.android.designsystem.rv.RvSectionTitle
import dev.doona.android.designsystem.rv.RvSeeAll
import dev.doona.android.designsystem.rv.RvSegmented
import dev.doona.android.designsystem.rv.RvSpace
import dev.doona.android.designsystem.rv.RvText
import dev.doona.android.designsystem.rv.RvTheme
import dev.doona.android.designsystem.rv.RvWorldMap
import dev.doona.android.designsystem.rv.rvMoves
import dev.doona.android.designsystem.rv.rvPress
import dev.doona.android.designsystem.rv.rvSystemScheme
import dev.doona.android.prototype.rv.LocalRvRowActions
import dev.doona.android.prototype.rv.data.RvNode
import dev.doona.android.prototype.rv.rvNodeMenu
import dev.doona.android.prototype.rv.rvSharedFlag

/**
 * The 節點 page, pushed from 活動, in the language of Revolut's Analytics and Crypto pages: the back disc and the large
 * title over two filter chips; 最快節點 as 'Top movers' (fastest or steadiest, eight flags with their latency); 節點分布
 * as 'Your trips' (the world map with every country that has a node filled); then 全部節點 as 'All crypto' rows, one
 * card per part of the world, each node's latency over its change since the test before, five rows and then 查看全部. The
 * node in use carries the check badge on its flag wherever it is listed; a tap on a node opens its page and never
 * switches to it (Addendum A). The line under the title says that the node chosen here is the main node (item 24). With no
 * node (no subscription) the chips and the cards give way to the empty state, whose 新增訂閱 runs [onAdd].
 */
@Composable
internal fun RvNodesPage(
    state: RvNodesPageState,
    onBack: () -> Unit,
    onFilter: () -> Unit,
    onOpen: (String) -> Unit,
    onAllNodes: () -> Unit,
    onAdd: () -> Unit = {},
) {
    val insets = LocalFrameInsets.current ?: WindowInsets.systemBars
    var steadiest by rememberSaveable { mutableStateOf(false) }
    val title = stringResource(R.string.rv_nodes_title)
    RvScrollPage(title, insets, onBack = onBack) { titleBlock ->
        // The pull-down from 活動 picks the main node; groups with their own choice open from 活動's rule rows (item 24).
        val empty = state.nodes.isEmpty()
        val chips: (@Composable () -> Unit)? = if (empty) null else ({ Chips(onFilter) })
        RvPageHeader(title, titleBlock, insets, caption = { RvCaption(stringResource(R.string.rv_main_node_hint)) }, control = chips)
        if (empty) {
            RvGutter {
                RvEmptyState(
                    stringResource(R.string.rv_empty_subscription),
                    stringResource(R.string.rv_empty_subscription_line),
                    stringResource(R.string.rv_add_subscription),
                    onAdd,
                )
            }
        } else {
            RvGutter {
                TopCard(state.top(steadiest), state.currentId, steadiest, { steadiest = it }, onOpen, onAllNodes)
                MapCard(state)
            }
            RvSectionTitle(stringResource(R.string.rv_nodes_all), Modifier.padding(horizontal = RvSpace.gutter), onAllNodes)
            RvGutter {
                for ((region, nodes) in state.byRegion()) RegionCard(region, nodes, state.currentId, onOpen)
            }
        }
    }
}

/** Under the large title, Analytics' two chips: the subscription at the start, the order at the end. */
@Composable
private fun Chips(onFilter: () -> Unit) {
    RvFilterChipRow(RvChipPick(stringResource(R.string.rv_nodes_all_subscriptions), onFilter), RvChipPick(stringResource(R.string.rv_nodes_by_latency), onFilter))
}

/** 'Top movers': the card's header (it opens the list, as on 活動), 最快 | 最穩, then the eight nodes in two rows of four. */
@Composable
private fun TopCard(top: List<RvNode>, currentId: String, steadiest: Boolean, onSteadiest: (Boolean) -> Unit, onOpen: (String) -> Unit, onAll: () -> Unit) {
    RvCard {
        RvCardHeader(stringResource(R.string.rv_nodes_fastest_card), onAll)
        RvSegmented(
            listOf(stringResource(R.string.rv_nodes_fastest), stringResource(R.string.rv_nodes_steadiest)),
            selected = if (steadiest) 1 else 0,
            onSelect = { onSteadiest(it == 1) },
            modifier = Modifier.padding(horizontal = RvSpace.rowPadding),
        )
        Spacer(Modifier.height(RvNodesSize.topRowGap))
        for (line in top.chunked(TOP_COLUMNS)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = RvSpace.rowPadding - RvSpace.tight)) {
                line.forEach { TopNode(it, it.id == currentId, onOpen, Modifier.weight(1f)) }
                repeat(TOP_COLUMNS - line.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        Spacer(Modifier.height(RvSpace.rowPadding - RvSpace.tight))
    }
}

/** One of 'Top movers': the flag (badged when [current]), the short name and the latency in its quality's hue. */
@Composable
private fun TopNode(node: RvNode, current: Boolean, onOpen: (String) -> Unit, modifier: Modifier) {
    Column(
        modifier
            .rvPress(RoundedCornerShape(Rv.highlightRadius), Role.Button, { onOpen(node.id) })
            .semantics(mergeDescendants = true) {}
            .padding(vertical = RvSpace.tight),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        RvFlag(node.place.flag, RvNodesSize.topFlag, current = current)
        Spacer(Modifier.height(RvNodesSize.topLabelGap))
        // A node's code keeps its own figures: Inter's tabular figures widen the hyphen of `hk-01`.
        RvText(node.short, Rv.type.pill.copy(fontFeatureSettings = null), align = TextAlign.Center)
        RvText(latencyText(node.latencyMs), Rv.type.pill, color = latencyColor(node.latencyMs), align = TextAlign.Center)
    }
}

/** 'Your trips': the header, the map, and how many countries and nodes under it. */
@Composable
private fun MapCard(state: RvNodesPageState) {
    RvCard {
        RvCardHeader(stringResource(R.string.rv_nodes_spread))
        RvWorldMap(state.places, Modifier.padding(horizontal = RvSpace.rowPadding))
        Spacer(Modifier.height(RvSpace.barGap))
        val countries = state.places.size
        val nodes = state.nodes.size
        val text = pluralStringResource(R.plurals.rv_nodes_countries, countries, countries) + SEPARATOR + pluralStringResource(R.plurals.rv_nodes_nodes, nodes, nodes)
        RvText(text, Rv.type.rowTitle, Modifier.padding(horizontal = RvSpace.rowPadding))
        Spacer(Modifier.height(RvSpace.rowPadding))
    }
}

/**
 * One part of the world: its name as the card's header, then its nodes, fastest first: the first [REGION_ROWS], then
 * Revolut's 56 dp 'See all' row (S7-12), which opens the rest in place.
 */
@Composable
private fun RegionCard(region: RvRegion, nodes: List<RvNode>, currentId: String, onOpen: (String) -> Unit) {
    var all by rememberSaveable(region) { mutableStateOf(false) }
    val inUse = stringResource(R.string.rv_nodes_in_use)
    // 查看全部 opens the card in place, its height growing over the move's time; a new test re-sorts the rows, each
    // moving to its new place.
    RvCard(Modifier.animateContentSize(Rv.motion.tween(RvMotion.MOVE_MS))) {
        RvCardHeader(stringResource(region.label))
        (if (all) nodes else nodes.take(REGION_ROWS)).forEachIndexed { order, node ->
            key(node.id) {
                val current = node.id == currentId
                // A long press opens the row's menu; the box round the row moves when a test re-sorts the card.
                RvMenuAnchor(rvNodeMenu(node, current, LocalRvRowActions.current.useNode, onOpen), Modifier.rvMoves()) { menu, actions ->
                    RvRow(
                        { RvFlag(node.place.flag, RvNodesSize.rowFlag, Modifier.rvSharedFlag(node.id), current = current) },
                        node.name,
                        node.protocol + SEPARATOR + stringResource(node.place.label),
                        (if (current) Modifier.semantics { stateDescription = inUse } else Modifier).then(actions),
                        onClick = { onOpen(node.id) },
                        onLongClick = menu,
                        titleKeepWords = false,
                    ) { RvLatencyChange(node.latencyMs, node.changeMs, node.timedOut, order, node.id) }
                }
            }
        }
        if (!all && nodes.size > REGION_ROWS) RvSeeAll(pluralStringResource(R.plurals.rv_nodes_see_all, nodes.size, nodes.size)) { all = true }
    }
}

internal const val SEPARATOR = " · "
private const val TOP_COLUMNS = 4
private const val REGION_ROWS = 5

@Preview(name = "Light", locale = "zh-rTW", widthDp = 412, heightDp = 915)
@Preview(name = "Dark", locale = "zh-rTW", widthDp = 412, heightDp = 915, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun RvNodesPagePreview() {
    val dark = isSystemInDarkTheme()
    RvTheme(rvSystemScheme(dark), dark) { RvNodesPage(sampleNodesPage(), onBack = {}, onFilter = {}, onOpen = {}, onAllNodes = {}) }
}
