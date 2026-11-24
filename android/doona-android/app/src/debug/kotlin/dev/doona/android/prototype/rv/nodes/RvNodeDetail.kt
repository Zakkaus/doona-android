package dev.doona.android.prototype.rv.nodes

import android.content.res.Configuration
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import dev.doona.android.R
import dev.doona.android.designsystem.components.Tone
import dev.doona.android.designsystem.components.latencyTone
import dev.doona.android.designsystem.rv.RV_NO_VALUE
import dev.doona.android.designsystem.rv.Rv
import dev.doona.android.designsystem.rv.RvCaption
import dev.doona.android.designsystem.rv.RvCard
import dev.doona.android.designsystem.rv.RvCardHeader
import dev.doona.android.designsystem.rv.RvFlag
import dev.doona.android.designsystem.rv.RvGutter
import dev.doona.android.designsystem.rv.RvNotice
import dev.doona.android.designsystem.rv.RvPageHeader
import dev.doona.android.designsystem.rv.RvScrollPage
import dev.doona.android.designsystem.rv.RvSectionTitle
import dev.doona.android.designsystem.rv.RvSize
import dev.doona.android.designsystem.rv.RvSpace
import dev.doona.android.designsystem.rv.RvSpanText
import dev.doona.android.designsystem.rv.RvStatRow
import dev.doona.android.designsystem.rv.RvText
import dev.doona.android.designsystem.rv.RvTheme
import dev.doona.android.designsystem.rv.rememberRvFormat
import dev.doona.android.designsystem.rv.rvSystemScheme
import dev.doona.android.designsystem.rv.scripted
import dev.doona.android.designsystem.theme.LocalFrameInsets
import dev.doona.android.prototype.rv.data.RvNode
import dev.doona.android.prototype.rv.rvSharedFlag

/**
 * A node's page in the language of Revolut's stock page: the flag over its name as the large title, which collapses into
 * the bar beside the back disc on scroll, its protocol and country under it, and the status chips that apply; 延遲紀錄 as the EPS dot chart of its last tests, the picked one's figure over
 * it; its share link's details as a Key stats card; and 設為目前節點 floating at the bottom as 'Get started', with a
 * round 測試延遲 beside it. A [banner] (a test that failed) sits over 延遲紀錄 in [RvGutter]'s slot.
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
) {
    val insets = LocalFrameInsets.current ?: WindowInsets.systemBars
    val format = rememberRvFormat()
    val times = testedAtEpochMs.map(format::time)
    // Only a test the user picked is kept; with none picked, or one the history no longer has, the latest is shown.
    var choice by rememberSaveable(node.id) { mutableIntStateOf(NONE_PICKED) }
    val picked = choice.takeIf { it in node.history.indices } ?: node.history.lastIndex
    RvScrollPage(
        node.name,
        insets,
        onBack = onBack,
        overlay = { Actions(current, onUse, onTest, insets, Modifier.align(Alignment.BottomCenter)) },
    ) { titleBlock ->
        Header(node, current, titleBlock, insets)
        RvGutter(banner = banner) {
            HistoryCard(node, times, picked) { choice = it }
        }
        RvSectionTitle(stringResource(R.string.rv_nodes_details), Modifier.padding(horizontal = RvSpace.gutter))
        RvGutter { StatsCard(node, times.lastOrNull() ?: RV_NO_VALUE) }
        // The floating actions' room under the last card (S7-17, N05): the gap, the companion, and its 16 dp over the inset.
        Spacer(Modifier.height(RvSpace.gutter + RvNodesSize.ctaCompanion + RvSpace.gutter))
        Spacer(Modifier.windowInsetsBottomHeight(insets))
    }
}

/** The flag over the name, `協定 · 地區`, and the chips that apply: in use, and a latency over the good range. */
@Composable
private fun Header(node: RvNode, current: Boolean, titleBlock: Modifier, insets: WindowInsets) {
    val high = latencyTone(node.latencyMs).let { it == Tone.Warn || it == Tone.Err }
    val chips: (@Composable () -> Unit)? = if (current || high) {
        {
            Row(horizontalArrangement = Arrangement.spacedBy(RvSpace.tight * 2)) {
                if (current) RvStatusChip(R.drawable.rv_nodes_ic_check_circle, stringResource(R.string.rv_nodes_in_use), Rv.colors.positive)
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
        caption = { RvCaption(node.protocol + SEPARATOR + stringResource(node.place.label)) },
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

/** A latency as a card's figure: the number large, the unit after it small, on one baseline. */
@Composable
private fun Figure(text: String) {
    val type = Rv.type
    val cut = text.indexOf(' ').takeIf { it > 0 } ?: text.length
    val styled = buildAnnotatedString {
        append(text.substring(0, cut))
        withStyle(type.cardUnit.toSpanStyle()) { append(scripted(text.substring(cut))) }
    }
    RvSpanText(styled, type.cardValue.copy(color = Rv.colors.text), maxLines = 1)
}

/** Key stats: what the share link says, the subscription it came from and when it was last tested. */
@Composable
private fun StatsCard(node: RvNode, lastTest: String) {
    RvCard {
        RvStatRow(stringResource(R.string.rv_nodes_protocol), node.protocol)
        RvStatRow(stringResource(R.string.rv_nodes_server), node.host)
        RvStatRow(stringResource(R.string.rv_nodes_port), node.port.toString())
        RvStatRow(stringResource(R.string.rv_nodes_transport), node.transport)
        RvStatRow(TLS, stringResource(if (node.tls) R.string.ui_on else R.string.ui_off))
        RvStatRow(stringResource(R.string.rv_nodes_subscription), node.subscription)
        RvStatRow(stringResource(R.string.rv_nodes_last_test), lastTest)
    }
}

/** The floating actions, with no scrim: the page reserves their room, so the cards scroll under them (S7-17, N05). */
@Composable
private fun Actions(current: Boolean, onUse: () -> Unit, onTest: () -> Unit, insets: WindowInsets, modifier: Modifier) {
    Column(modifier.fillMaxWidth().padding(horizontal = RvSpace.gutter)) {
        RvFloatingActions(
            stringResource(if (current) R.string.rv_nodes_in_use else R.string.rv_nodes_use),
            onUse,
            R.drawable.rv_sym_speed,
            stringResource(R.string.rv_test_latency),
            onTest,
        )
        Spacer(Modifier.height(RvSpace.gutter))
        Spacer(Modifier.windowInsetsBottomHeight(insets))
    }
}

private const val TLS = "TLS"

/** No test picked by the user: the chart marks the latest. */
private const val NONE_PICKED = -1

@Preview(name = "Light", locale = "zh-rTW", widthDp = 412, heightDp = 915)
@Preview(name = "Dark", locale = "zh-rTW", widthDp = 412, heightDp = 915, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun RvNodeDetailPreview() {
    val dark = isSystemInDarkTheme()
    val page = sampleNodesPage()
    RvTheme(rvSystemScheme(dark), dark) {
        RvNodeDetail(page.nodes.first { it.id == RV_NODES_DETAIL }, current = false, page.testedAtEpochMs, onBack = {}, onUse = {}, onTest = {})
    }
}
