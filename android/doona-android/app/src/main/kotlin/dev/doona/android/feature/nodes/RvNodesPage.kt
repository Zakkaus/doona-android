package dev.doona.android.feature.nodes

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import dev.doona.android.R
import dev.doona.android.designsystem.rv.LocalFrameInsets
import dev.doona.android.designsystem.rv.Rv
import dev.doona.android.designsystem.rv.RvBannerSlot
import dev.doona.android.designsystem.rv.RvCaption
import dev.doona.android.designsystem.rv.RvCard
import dev.doona.android.designsystem.rv.RvCardHeader
import dev.doona.android.designsystem.rv.RvFilterChipRow
import dev.doona.android.designsystem.rv.RvGutter
import dev.doona.android.designsystem.rv.RvLatencyChange
import dev.doona.android.designsystem.rv.RvMenuAnchor
import dev.doona.android.designsystem.rv.RvMotion
import dev.doona.android.designsystem.rv.RvNodesSize
import dev.doona.android.designsystem.rv.RvNotice
import dev.doona.android.designsystem.rv.RvPageHeader
import dev.doona.android.designsystem.rv.RvRegion
import dev.doona.android.designsystem.rv.RvRow
import dev.doona.android.designsystem.rv.RvScrollPage
import dev.doona.android.designsystem.rv.RvSectionTitle
import dev.doona.android.designsystem.rv.RvSeeAll
import dev.doona.android.designsystem.rv.RvSegmented
import dev.doona.android.designsystem.rv.RvSkeletonBlock
import dev.doona.android.designsystem.rv.RvSkeletonCard
import dev.doona.android.designsystem.rv.RvSkeletonDisc
import dev.doona.android.designsystem.rv.RvSkeletonEnd
import dev.doona.android.designsystem.rv.RvSkeletonFade
import dev.doona.android.designsystem.rv.RvSkeletonHeader
import dev.doona.android.designsystem.rv.RvSkeletonLabel
import dev.doona.android.designsystem.rv.RvSkeletonLine
import dev.doona.android.designsystem.rv.RvSkeletonRow
import dev.doona.android.designsystem.rv.RvSkeletonSeeAll
import dev.doona.android.designsystem.rv.RvSkeletonSize
import dev.doona.android.designsystem.rv.RvSkeletonText
import dev.doona.android.designsystem.rv.RvSkeletonWorldMap
import dev.doona.android.designsystem.rv.RvSpace
import dev.doona.android.designsystem.rv.RvText
import dev.doona.android.designsystem.rv.RvWorldMap
import dev.doona.android.designsystem.rv.SEPARATOR
import dev.doona.android.designsystem.rv.latencyColor
import dev.doona.android.designsystem.rv.latencyText
import dev.doona.android.designsystem.rv.rvMoves
import dev.doona.android.designsystem.rv.rvPress
import dev.doona.android.designsystem.rv.rvSegmentHeight
import dev.doona.android.designsystem.rv.rvSharedFlag
import dev.doona.android.feature.model.RvNode
import dev.doona.android.feature.model.RvNodeSort
import dev.doona.android.feature.nodes.ui.LocalRvRowActions
import dev.doona.android.feature.nodes.ui.RvNodeDisc
import dev.doona.android.feature.nodes.ui.rememberRvNodeTitles
import dev.doona.android.feature.nodes.ui.rvNodeChips
import dev.doona.android.feature.nodes.ui.rvNodeLine
import dev.doona.android.feature.nodes.ui.rvNodeMenu
import dev.doona.android.feature.nodes.ui.rvNodeTitle
import dev.doona.android.feature.nodes.ui.rvPicked

/**
 * The 節點 page, pushed from 活動, in the language of Revolut's Analytics and Crypto pages: the back disc and the large
 * title over two filter chips; 最快節點 as 'Top movers' (fastest or steadiest, eight flags with their latency); 節點分布
 * as 'Your trips' (the world map with every country that has a node filled); then 全部節點 as 'All crypto' rows, one
 * card per part of the world, each node's latency over its change since the test before, five rows and then 查看全部. The
 * node in use carries the check badge on its flag wherever it is listed; a tap on a node opens its page and never
 * switches to it (Addendum A). The line under the title, [caption], says that the node chosen here is the main node (item
 * 24); with no main group there is none (q-p3a-nomain), and no node carries the check. The chips (decision 2026-11-25 19:25, shared with the pickers and a group's page)
 * are the page's own: 訂閱 shows one subscription's nodes, or 組態內's, across the page; 排序 orders the rows of each
 * part of the world. With no node (no subscription) the chips and the cards give way to the empty state, whose 新增訂閱
 * runs [onAdd]. A failed action ([banner]: a switch, a test, the read of the nodes' details) stands under the header with
 * its 重試, as on the pickers. While the main group's TCP and UDP choices differ, both its nodes carry the check. Before the
 * catalog is read ([loading]) the cards stand as their skeletons, in their bounds, and fade into them; the chips are there
 * already, their labels known (the 訂閱 chip's menu lists its subscriptions once they are read).
 */
@Composable
internal fun RvNodesPage(
    state: RvNodesPageState,
    onBack: () -> Unit,
    onOpen: (String) -> Unit,
    onAllNodes: () -> Unit,
    onAdd: () -> Unit = {},
    score: RvScoreMarks? = null,
    caption: String? = stringResource(R.string.rv_main_node_hint),
    banner: RvNotice? = null,
    loading: Boolean = false,
) {
    val insets = LocalFrameInsets.current ?: WindowInsets.systemBars
    var steadiest by rememberSaveable { mutableStateOf(false) }
    var chosen by rememberSaveable { mutableStateOf<String?>(null) }
    val picked = rvPicked(chosen, state.nodes)
    var order by rememberSaveable { mutableStateOf(RvNodeSort.Latency) }
    val title = stringResource(R.string.rv_nodes_title)
    val shown = remember(state, picked) { state.scoped(picked) }
    val titles = rememberRvNodeTitles()
    RvScrollPage(title, insets, onBack = onBack) { titleBlock ->
        // The pull-down from 活動 picks the main node; groups with their own choice open from 活動's rule rows (item 24).
        val empty = !loading && state.nodes.isEmpty()
        val chips: (@Composable () -> Unit)? = if (empty) {
            null
        } else {
            {
                val (subChip, orderChip) = rvNodeChips(state.subscriptions, state.nodes, picked, order, { chosen = it }, { order = it })
                RvFilterChipRow(subChip, orderChip)
            }
        }
        RvPageHeader(title, titleBlock, insets, caption = caption?.let { line -> { RvCaption(line) } }, control = chips)
        RvBannerSlot(banner)
        if (empty) {
            RvGutter { RvNoSubscription(onAdd) }
        } else {
            RvSkeletonFade(loading, { Column { NodesSkeleton(onAllNodes) } }) {
                Column {
                    RvGutter {
                        TopCard(shown.top(steadiest), shown.current, steadiest, { steadiest = it }, onOpen, onAllNodes)
                        MapCard(shown)
                    }
                    RvSectionTitle(stringResource(R.string.rv_nodes_all), Modifier.padding(horizontal = RvSpace.gutter), onAllNodes)
                    RvGutter {
                        for ((region, nodes) in shown.byRegion(order, titles)) RegionCard(region, nodes, shown.current, onOpen, score)
                    }
                }
            }
        }
    }
}

/**
 * 節點 before the catalog is read ([RvNodesPage]'s `loading`), as after process death: each card's skeleton in the card's
 * bounds (decision 2026-11-24 15:17), 最快節點's header, its 最快 | 最穩 track and its eight flags, 節點分布's map and the line
 * under it, then 全部節點 over a part of the world's rows and its 查看全部.
 */
@Composable
private fun NodesSkeleton(onAllNodes: () -> Unit) {
    RvGutter {
        RvSkeletonCard {
            RvSkeletonHeader(stringResource(R.string.rv_nodes_fastest_card))
            RvSkeletonBlock(rvSegmentHeight(Rv.type.segment), Modifier.padding(horizontal = RvSpace.rowPadding), CircleShape)
            Spacer(Modifier.height(RvNodesSize.topRowGap))
            repeat(TOP_ROWS) {
                Row(Modifier.fillMaxWidth().padding(horizontal = RvSpace.rowPadding - RvSpace.tight)) {
                    repeat(TOP_COLUMNS) { TopNodeSkeleton(Modifier.weight(1f)) }
                }
            }
            Spacer(Modifier.height(RvSpace.rowPadding - RvSpace.tight))
        }
        RvSkeletonCard {
            RvSkeletonHeader(stringResource(R.string.rv_nodes_spread))
            RvSkeletonWorldMap(Modifier.padding(horizontal = RvSpace.rowPadding))
            Spacer(Modifier.height(RvSpace.barGap))
            RvSkeletonLine(Rv.type.rowTitle, RvSkeletonSize.CAPTION, Modifier.padding(horizontal = RvSpace.rowPadding), RvSkeletonText.Mixed)
            Spacer(Modifier.height(RvSpace.rowPadding))
        }
    }
    RvSectionTitle(stringResource(R.string.rv_nodes_all), Modifier.padding(horizontal = RvSpace.gutter), onAllNodes)
    RvGutter {
        RvSkeletonCard {
            RvSkeletonHeader()
            repeat(REGION_ROWS) { RvSkeletonRow(it, RvSkeletonText.Mixed, RvSkeletonEnd.LatencyLines, titleKeepWords = false) }
            RvSkeletonSeeAll()
        }
    }
}

/** One of 'Top movers' before its node: the flag's disc over the short name's and a latency's bars, centred. */
@Composable
private fun TopNodeSkeleton(modifier: Modifier) {
    Column(modifier.padding(vertical = RvSpace.tight), horizontalAlignment = Alignment.CenterHorizontally) {
        RvSkeletonDisc(RvNodesSize.topFlag)
        Spacer(Modifier.height(RvNodesSize.topLabelGap))
        RvSkeletonLine(Rv.type.pill, RvSkeletonSize.FLAG_NAME, kind = RvSkeletonText.Figures)
        // A latency's line as the tile sets one, cut where the tile is too narrow for it.
        RvSkeletonLabel(latencyText(RvSkeletonSize.FIGURE), Rv.type.pill, maxLines = 1)
    }
}

/** 'Top movers': the card's header (it opens the list, as on 活動), 最快 | 最穩, then the eight nodes in two rows of four. */
@Composable
private fun TopCard(top: List<RvNode>, current: Set<String>, steadiest: Boolean, onSteadiest: (Boolean) -> Unit, onOpen: (String) -> Unit, onAll: () -> Unit) {
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
                line.forEach { TopNode(it, it.id in current, onOpen, Modifier.weight(1f)) }
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
        RvNodeDisc(node, RvNodesSize.topFlag, current = current)
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
private fun RegionCard(region: RvRegion, nodes: List<RvNode>, current: Set<String>, onOpen: (String) -> Unit, score: RvScoreMarks?) {
    var all by rememberSaveable(region) { mutableStateOf(false) }
    // 查看全部 opens the card in place, its height growing over the move's time; a new test re-sorts the rows, each
    // moving to its new place.
    RvCard(Modifier.animateContentSize(Rv.motion.tween(RvMotion.MOVE_MS))) {
        RvCardHeader(stringResource(region.label))
        (if (all) nodes else nodes.take(REGION_ROWS)).forEachIndexed { order, node ->
            key(node.id) {
                RegionRow(node, node.id in current, order, onOpen, score)
            }
        }
        if (!all && nodes.size > REGION_ROWS) RvSeeAll(pluralStringResource(R.plurals.rv_nodes_see_all, nodes.size, nodes.size)) { all = true }
    }
}

/**
 * A node in its part of the world: the flag (badged in use), the name, `協定 · 地區`, and its latency over its change. With
 * [score] the line begins with the node's Score mark where a Score group chose it.
 */
@Composable
private fun RegionRow(node: RvNode, current: Boolean, order: Int, onOpen: (String) -> Unit, score: RvScoreMarks?) {
    val inUse = stringResource(R.string.rv_nodes_in_use)
    val mark = score?.mark(node.id)
    // A long press opens the row's menu; the box round the row moves when a test re-sorts the card.
    RvMenuAnchor(rvNodeMenu(node, current, LocalRvRowActions.current.useNode, onOpen), Modifier.rvMoves()) { menu, actions ->
        RvRow(
            { RvNodeDisc(node, RvNodesSize.rowFlag, Modifier.rvSharedFlag(node.id), current = current) },
            rvNodeTitle(node),
            rvNodeLine(mark, node.protocol, stringResource(node.place.label)),
            (if (current) Modifier.semantics { stateDescription = inUse } else Modifier).then(actions),
            onClick = { onOpen(node.id) },
            onLongClick = menu,
            titleKeepWords = false,
        ) { RvLatencyChange(node.latencyMs, node.changeMs, node.timedOut, order, node.id) }
    }
}

private const val TOP_COLUMNS = 4
private const val TOP_ROWS = 2
private const val REGION_ROWS = 5
