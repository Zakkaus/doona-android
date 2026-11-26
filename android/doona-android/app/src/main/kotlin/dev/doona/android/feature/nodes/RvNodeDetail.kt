package dev.doona.android.feature.nodes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.doona.android.R
import dev.doona.android.designsystem.rv.LocalFrameInsets
import dev.doona.android.designsystem.rv.RV_NO_VALUE
import dev.doona.android.designsystem.rv.Rv
import dev.doona.android.designsystem.rv.RvCaption
import dev.doona.android.designsystem.rv.RvCard
import dev.doona.android.designsystem.rv.RvCardHeader
import dev.doona.android.designsystem.rv.RvFigure
import dev.doona.android.designsystem.rv.RvFigureText
import dev.doona.android.designsystem.rv.RvFlag
import dev.doona.android.designsystem.rv.RvGutter
import dev.doona.android.designsystem.rv.RvNodesSize
import dev.doona.android.designsystem.rv.RvNotice
import dev.doona.android.designsystem.rv.RvPageEnd
import dev.doona.android.designsystem.rv.RvPageHeader
import dev.doona.android.designsystem.rv.RvScrollPage
import dev.doona.android.designsystem.rv.RvSectionTitle
import dev.doona.android.designsystem.rv.RvSize
import dev.doona.android.designsystem.rv.RvSkeletonBlock
import dev.doona.android.designsystem.rv.RvSkeletonCard
import dev.doona.android.designsystem.rv.RvSkeletonHeader
import dev.doona.android.designsystem.rv.RvSkeletonLine
import dev.doona.android.designsystem.rv.RvSkeletonPageHeader
import dev.doona.android.designsystem.rv.RvSkeletonSize
import dev.doona.android.designsystem.rv.RvSkeletonStatRow
import dev.doona.android.designsystem.rv.RvSkeletonText
import dev.doona.android.designsystem.rv.RvSpace
import dev.doona.android.designsystem.rv.RvStatRow
import dev.doona.android.designsystem.rv.RvStatusChip
import dev.doona.android.designsystem.rv.RvText
import dev.doona.android.designsystem.rv.SEPARATOR
import dev.doona.android.designsystem.rv.Tone
import dev.doona.android.designsystem.rv.latencyColor
import dev.doona.android.designsystem.rv.latencyText
import dev.doona.android.designsystem.rv.latencyTone
import dev.doona.android.designsystem.rv.rememberRvFormat
import dev.doona.android.designsystem.rv.rvSharedFlag
import dev.doona.android.feature.model.RvNode
import dev.doona.android.feature.nodes.ui.rvNodeLine

/**
 * A node's page in the language of Revolut's stock page: the flag over its name as the large title, which collapses into
 * the bar beside the back disc on scroll, its protocol and country under it, and the status chips that apply; 延遲紀錄 as the EPS dot chart of its last tests, the picked one's figure over
 * it; its details as a Key stats card; and 設為目前節點 floating at the bottom as 'Get started', with a
 * round 測試延遲 beside it. A [banner] (a test that failed) sits over 延遲紀錄 in [RvGutter]'s slot. Without [canUse] (a
 * profile with no main group) the main action is disabled.
 */
@Composable
internal fun RvNodeDetail(
    node: RvNode,
    current: Boolean,
    testedAtEpochMs: List<Long>,
    onBack: () -> Unit,
    onUse: () -> Unit,
    onTest: () -> Unit,
    banner: RvNotice? = null,
    score: RvScoreMarks? = null,
    canUse: Boolean = true,
) {
    val insets = LocalFrameInsets.current ?: WindowInsets.systemBars
    val format = rememberRvFormat()
    // One time per test, the latest time the latest test's: a node probed fewer rounds than the list holds (one probe
    // after an import) reads its own time, and a test the list has no time for reads as a dash.
    val offset = testedAtEpochMs.size - node.history.size
    val times = node.history.indices.map { i -> testedAtEpochMs.getOrNull(offset + i)?.let(format::time) ?: RV_NO_VALUE }
    // Only a test the user picked is kept; with none picked, or one the history no longer has, the latest is shown.
    var choice by rememberSaveable(node.id) { mutableIntStateOf(NONE_PICKED) }
    val picked = choice.takeIf { it in node.history.indices } ?: node.history.lastIndex
    RvScrollPage(
        node.name,
        insets,
        onBack = onBack,
        overlay = { Actions(current, canUse, onUse, onTest, insets, Modifier.align(Alignment.BottomCenter)) },
        // The floating actions' room under the last card (S7-17, N05): the gap, the companion, and its 16 dp over the inset.
        end = RvPageEnd.Inset(RvSpace.gutter + RvNodesSize.ctaCompanion + RvSpace.gutter),
    ) { titleBlock ->
        Header(node, current, titleBlock, insets, score?.detailMark(node.id))
        RvGutter(banner = banner) {
            HistoryCard(node, times, picked) { choice = it }
        }
        RvSectionTitle(stringResource(R.string.rv_nodes_details), Modifier.padding(horizontal = RvSpace.gutter))
        RvGutter { StatsCard(node, times.lastOrNull() ?: RV_NO_VALUE) }
    }
}

/**
 * A node's page before the catalog is read, as after process death (the page knows only the node's id): the head's
 * flag, name and `協定 · 地區` as bars, 延遲紀錄's figure, time and chart, then 詳細資料's labels over their values' bars, each
 * in the bounds of what replaces it (decision 2026-11-24 15:17). The status chips and the floating actions come with the
 * node.
 */
@Composable
internal fun RvNodeDetailSkeleton(onBack: () -> Unit) {
    val insets = LocalFrameInsets.current ?: WindowInsets.systemBars
    val labels = listOf(
        stringResource(R.string.rv_nodes_protocol),
        stringResource(R.string.rv_nodes_transport),
        stringResource(R.string.rv_nodes_subscription),
        stringResource(R.string.rv_nodes_last_test),
    )
    val time = rememberRvFormat().time(0L)
    RvScrollPage("", insets, onBack = onBack, end = RvPageEnd.Inset(RvSpace.gutter + RvNodesSize.ctaCompanion + RvSpace.gutter)) {
        RvSkeletonPageHeader(insets, mark = RvSize.headerDisc, title = RvSkeletonText.Figures, caption = RvSkeletonText.Mixed)
        RvGutter {
            RvSkeletonCard {
                RvSkeletonHeader(stringResource(R.string.rv_nodes_history))
                Column(Modifier.padding(horizontal = RvSpace.rowPadding)) {
                    RvSkeletonLine(Rv.type.cardValue, RvSkeletonSize.TILE_FIGURE, kind = RvSkeletonText.Figures)
                    RvSkeletonLine(Rv.type.tileCaption, RvSkeletonSize.CAPTION, kind = RvSkeletonText.Mixed)
                    Spacer(Modifier.height(RvSpace.barGap))
                    RvSkeletonBlock(rvLatencyDotsHeight(time))
                }
                Spacer(Modifier.height(RvSpace.rowPadding))
            }
        }
        RvSectionTitle(stringResource(R.string.rv_nodes_details), Modifier.padding(horizontal = RvSpace.gutter))
        RvGutter { RvSkeletonCard { labels.forEachIndexed { i, label -> RvSkeletonStatRow(label, i) } } }
    }
}

/**
 * The flag over the name, `協定 · 地區`, and the chips that apply: in use, a Score group's choice ([scoreMark], the same
 * chip in the same green), and a latency over the good range.
 */
@Composable
private fun Header(node: RvNode, current: Boolean, titleBlock: Modifier, insets: WindowInsets, scoreMark: String?) {
    val high = latencyColor(node.latencyMs).let { it == Rv.colors.fair || it == Rv.colors.slow }
    val chips: (@Composable () -> Unit)? = if (current || high || scoreMark != null) {
        {
            // Chips that do not fit one line (two at fs200) wrap, the same gap between lines as between chips.
            FlowRow(horizontalArrangement = Arrangement.spacedBy(RvSpace.tight * 2), verticalArrangement = Arrangement.spacedBy(RvSpace.tight * 2)) {
                if (current) RvStatusChip(R.drawable.rv_nodes_ic_check_circle, stringResource(R.string.rv_nodes_in_use), Rv.colors.positive)
                if (scoreMark != null) RvStatusChip(R.drawable.rv_nodes_ic_check_circle, scoreMark, Rv.colors.positive)
                if (high) RvStatusChip(R.drawable.rv_nodes_ic_warning, stringResource(R.string.rv_nodes_latency_high), latencyColor(node.latencyMs))
            }
        }
    } else {
        null
    }
    RvPageHeader(
        node.name,
        titleBlock,
        insets,
        // The flag of the row this page was opened from grows into this one (rule 4).
        mark = { RvFlag(node.place.flag, RvSize.headerDisc, Modifier.rvSharedFlag(node.id)) },
        caption = { RvCaption(rvNodeLine(node.protocol, stringResource(node.place.label))) },
        control = chips,
    )
}

/** 延遲紀錄: the header, the picked test's latency over its time and the median (a dash with no test), and the dot chart. */
@Composable
private fun HistoryCard(node: RvNode, times: List<String>, picked: Int, onPick: (Int) -> Unit) {
    RvCard {
        RvCardHeader(stringResource(R.string.rv_nodes_history))
        Column(Modifier.padding(horizontal = RvSpace.rowPadding)) {
            if (node.history.isEmpty()) {
                // No test yet: a dash for the figure, and the chart's plot with its labels and the empty line in it.
                Figure(RV_NO_VALUE)
            } else {
                Figure(latencyText(node.history[picked]))
                val median = node.medianMs?.let { SEPARATOR + stringResource(R.string.rv_nodes_median, latencyText(it)) }.orEmpty()
                RvText(times[picked] + median, Rv.type.tileCaption, color = Rv.colors.textSecondary)
            }
            Spacer(Modifier.height(RvSpace.barGap))
            RvLatencyDots(node.history, node.medianMs, times, picked, onPick)
        }
        Spacer(Modifier.height(RvSpace.rowPadding))
    }
}

/** A latency as a card's figure: the number large, the unit after it small, on one baseline, shrunk to fit, never cut. */
@Composable
private fun Figure(text: String) {
    val cut = text.indexOf(' ').takeIf { it > 0 } ?: text.length
    RvFigureText(RvFigure(text.substring(0, cut), text.substring(cut)), Rv.type.cardValue, Rv.type.cardUnit)
}

/**
 * Key stats: the protocol and transport, the subscription it came from (組態內 for a node the profile lists itself,
 * decision q-s5-inline-name) and when it was last tested. honk's nodes carry no server, port or TLS, so those rows are
 * not drawn until /nodes sends them (owner, q-node-host 2026-11-26).
 */
@Composable
private fun StatsCard(node: RvNode, lastTest: String) {
    RvCard {
        RvStatRow(stringResource(R.string.rv_nodes_protocol), node.protocol.ifEmpty { RV_NO_VALUE }, valueKeepWords = false)
        RvStatRow(stringResource(R.string.rv_nodes_transport), node.transport.ifEmpty { RV_NO_VALUE }, valueKeepWords = false)
        val source = if (node.inline) stringResource(R.string.rv_nodes_inline) else node.subscription
        RvStatRow(stringResource(R.string.rv_nodes_subscription), source, valueKeepWords = false)
        RvStatRow(stringResource(R.string.rv_nodes_last_test), lastTest)
    }
}

/** The floating actions, with no scrim: the page reserves their room, so the cards scroll under them (S7-17, N05). */
@Composable
private fun Actions(current: Boolean, canUse: Boolean, onUse: () -> Unit, onTest: () -> Unit, insets: WindowInsets, modifier: Modifier) {
    Column(modifier.fillMaxWidth().padding(horizontal = RvSpace.gutter)) {
        RvFloatingActions(
            stringResource(if (current) R.string.rv_nodes_in_use else R.string.rv_nodes_use),
            onUse,
            R.drawable.rv_sym_speed,
            stringResource(R.string.rv_test_latency),
            onTest,
            enabled = canUse,
        )
        Spacer(Modifier.height(RvSpace.gutter))
        Spacer(Modifier.windowInsetsBottomHeight(insets))
    }
}

/** No test picked by the user: the chart marks the latest. */
private const val NONE_PICKED = -1
