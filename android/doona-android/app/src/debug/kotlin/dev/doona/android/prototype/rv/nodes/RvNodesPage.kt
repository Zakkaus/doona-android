package dev.doona.android.prototype.rv.nodes

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import dev.doona.android.R
import dev.doona.android.designsystem.theme.LocalFrameInsets
import dev.doona.android.prototype.rv.Rv
import dev.doona.android.prototype.rv.RvCard
import dev.doona.android.prototype.rv.RvFlag
import dev.doona.android.prototype.rv.RvGlyph
import dev.doona.android.prototype.rv.RvGutter
import dev.doona.android.prototype.rv.RvLargeTitle
import dev.doona.android.prototype.rv.RvRow
import dev.doona.android.prototype.rv.RvScrollPage
import dev.doona.android.prototype.rv.RvSegmented
import dev.doona.android.prototype.rv.RvSize
import dev.doona.android.prototype.rv.RvSpace
import dev.doona.android.prototype.rv.RvText
import dev.doona.android.prototype.rv.RvTheme
import dev.doona.android.prototype.rv.data.RvNode
import dev.doona.android.prototype.rv.data.RvRegion
import dev.doona.android.prototype.rv.rvPress
import dev.doona.android.prototype.rv.rvSystemScheme

/**
 * The 節點 page, pushed from 活動, in the language of Revolut's Analytics and Crypto pages: the back disc and the large
 * title over two filter chips; 最快節點 as 'Top movers' (fastest or steadiest, eight flags with their latency); 節點分佈
 * as 'Your trips' (the world map with every country that has a node filled); then 全部節點 as 'All crypto' rows, one
 * card per part of the world, each node's latency over its change since the test before. The node in use sits on a
 * faint primary tint.
 */
@Composable
internal fun RvNodesPage(state: RvNodesPageState, onBack: () -> Unit, onFilter: () -> Unit, onOpen: (String) -> Unit, onAllNodes: () -> Unit) {
    val insets = LocalFrameInsets.current ?: WindowInsets.systemBars
    var steadiest by rememberSaveable { mutableStateOf(false) }
    val title = stringResource(R.string.rv_nodes_title)
    RvScrollPage(title, insets, onBack = onBack) { titleBlock ->
        RvLargeTitle(title, titleBlock, insets)
        Chips(onFilter)
        RvGutter {
            TopCard(state.top(steadiest), steadiest, { steadiest = it }, onOpen)
            MapCard(state)
        }
        Spacer(Modifier.height(RvSpace.sectionGap))
        Heading(onAllNodes)
        Spacer(Modifier.height(RvSpace.headingGap))
        RvGutter {
            for ((region, nodes) in state.byRegion()) RegionCard(region, nodes, state.currentId, onOpen)
        }
        Spacer(Modifier.height(RvSpace.cardGap))
        Spacer(Modifier.windowInsetsBottomHeight(insets))
    }
}

/** Under the large title, Analytics' two chips: the subscription at the start, the order at the end. */
@Composable
private fun Chips(onFilter: () -> Unit) {
    Row(Modifier.padding(horizontal = RvSpace.gutter), verticalAlignment = Alignment.CenterVertically) {
        RvFilterChip(stringResource(R.string.rv_nodes_all_subscriptions), menu = true, onFilter)
        Spacer(Modifier.weight(1f))
        RvFilterChip(stringResource(R.string.rv_nodes_by_latency), menu = false, onFilter)
    }
    Spacer(Modifier.height(RvSpace.titleToCard))
}

/** 'Top movers': the card's header, 最快 | 最穩, then the eight nodes in two rows of four. */
@Composable
private fun TopCard(top: List<RvNode>, steadiest: Boolean, onSteadiest: (Boolean) -> Unit, onOpen: (String) -> Unit) {
    RvCard {
        RvCardHeader(stringResource(R.string.rv_nodes_fastest_card))
        RvSegmented(
            listOf(stringResource(R.string.rv_nodes_fastest), stringResource(R.string.rv_nodes_steadiest)),
            selected = if (steadiest) 1 else 0,
            onSelect = { onSteadiest(it == 1) },
            modifier = Modifier.padding(horizontal = RvSpace.rowPadding),
        )
        Spacer(Modifier.height(RvNodesSize.topRowGap))
        for (line in top.chunked(TOP_COLUMNS)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = RvSpace.rowPadding - RvSpace.tight)) {
                line.forEach { TopNode(it, onOpen, Modifier.weight(1f)) }
                repeat(TOP_COLUMNS - line.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        Spacer(Modifier.height(RvSpace.rowPadding - RvSpace.tight))
    }
}

/** One of 'Top movers': the flag, the short name and the latency in its quality's hue. */
@Composable
private fun TopNode(node: RvNode, onOpen: (String) -> Unit, modifier: Modifier) {
    Column(
        modifier
            .rvPress(RoundedCornerShape(RvSize.cardRadius), Role.Button) { onOpen(node.id) }
            .semantics(mergeDescendants = true) {}
            .padding(vertical = RvSpace.tight),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        RvFlag(node.place.name, RvNodesSize.topFlag)
        Spacer(Modifier.height(RvNodesSize.topLabelGap))
        RvText(node.short, RvNodesType.topName, align = TextAlign.Center)
        RvText(latencyText(node.latencyMs), RvNodesType.topValue, color = latencyColor(node.latencyMs), align = TextAlign.Center)
    }
}

/** 'Your trips': the header, the map, and how many countries and nodes under it. */
@Composable
private fun MapCard(state: RvNodesPageState) {
    RvCard {
        RvCardHeader(stringResource(R.string.rv_nodes_spread))
        RvNodesMap(state.places, Modifier.padding(horizontal = RvSpace.rowPadding))
        Spacer(Modifier.height(RvSpace.barGap))
        val countries = state.places.size
        val nodes = state.nodes.size
        val text = pluralStringResource(R.plurals.rv_nodes_countries, countries, countries) + SEPARATOR + pluralStringResource(R.plurals.rv_nodes_nodes, nodes, nodes)
        RvText(text, Rv.type.rowTitle, Modifier.padding(horizontal = RvSpace.rowPadding))
        Spacer(Modifier.height(RvSpace.rowPadding))
    }
}

/** 全部節點 as a section title on the page, with the chevron Revolut sets after a title that opens more. */
@Composable
private fun Heading(onAllNodes: () -> Unit) {
    Row(
        Modifier.padding(horizontal = RvSpace.gutter).rvPress(RoundedCornerShape(RvSpace.tight), Role.Button, onAllNodes),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RvText(stringResource(R.string.rv_nodes_all), Rv.type.section)
        Spacer(Modifier.width(RvSpace.tight / 2))
        RvGlyph(R.drawable.rv_sym_chevron_right, RvSize.navGlyph, Rv.colors.text)
    }
}

/** One part of the world: its name as the card's header, then its nodes, fastest first. */
@Composable
private fun RegionCard(region: RvRegion, nodes: List<RvNode>, currentId: String, onOpen: (String) -> Unit) {
    val tint = selectedRowTint()
    RvCard {
        RvCardHeader(stringResource(region.label))
        for (node in nodes) {
            RvRow(
                { RvFlag(node.place.name, RvNodesSize.rowFlag) },
                node.name,
                node.protocol + SEPARATOR + stringResource(node.place.label),
                if (node.id == currentId) Modifier.background(tint) else Modifier,
                onClick = { onOpen(node.id) },
            ) { RvLatencyChange(node) }
        }
    }
}

internal const val SEPARATOR = " · "
private const val TOP_COLUMNS = 4

@Preview(name = "Light", locale = "zh-rTW", widthDp = 412, heightDp = 915)
@Preview(name = "Dark", locale = "zh-rTW", widthDp = 412, heightDp = 915, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun RvNodesPagePreview() {
    val dark = isSystemInDarkTheme()
    RvTheme(rvSystemScheme(dark), dark) { RvNodesPage(sampleNodesPage(), onBack = {}, onFilter = {}, onOpen = {}, onAllNodes = {}) }
}
