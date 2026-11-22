package dev.doona.android.prototype.rv.nodes

import android.content.res.Configuration
import androidx.compose.foundation.background
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
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import dev.doona.android.R
import dev.doona.android.designsystem.components.Tone
import dev.doona.android.designsystem.components.latencyTone
import dev.doona.android.designsystem.theme.LocalFrameInsets
import dev.doona.android.prototype.rv.Rv
import dev.doona.android.prototype.rv.RvBarSpacer
import dev.doona.android.prototype.rv.RvCard
import dev.doona.android.prototype.rv.RvFlag
import dev.doona.android.prototype.rv.RvGutter
import dev.doona.android.prototype.rv.RvScrollPage
import dev.doona.android.prototype.rv.RvSpace
import dev.doona.android.prototype.rv.RvStatRow
import dev.doona.android.prototype.rv.RvText
import dev.doona.android.prototype.rv.RvTheme
import dev.doona.android.prototype.rv.data.RvNode
import dev.doona.android.prototype.rv.rememberRvFormat
import dev.doona.android.prototype.rv.rvSystemScheme
import dev.doona.android.prototype.rv.scripted

/**
 * A node's page, its name on the bar beside the back disc, in the language of Revolut's stock page: the flag, its
 * protocol and country with a status chip; 延遲紀錄 as the EPS dot chart of its last tests, the picked one's figure over
 * it; its share link's details as a Key stats card; and 設為目前節點 floating at the bottom as 'Get started', with a
 * round 測試延遲 beside it.
 */
@Composable
internal fun RvNodeDetail(node: RvNode, current: Boolean, testedAtEpochMs: List<Long>, onBack: () -> Unit, onUse: () -> Unit, onTest: () -> Unit) {
    val insets = LocalFrameInsets.current ?: WindowInsets.systemBars
    val format = rememberRvFormat()
    val times = testedAtEpochMs.map(format::time)
    var picked by rememberSaveable(node.id) { mutableIntStateOf(node.history.lastIndex) }
    RvScrollPage(
        node.name,
        insets,
        onBack = onBack,
        compact = true,
        overlay = { Actions(current, onUse, onTest, insets, Modifier.align(Alignment.BottomCenter)) },
    ) {
        RvBarSpacer(insets)
        Spacer(Modifier.height(RvSpace.gutter))
        Top(node, current)
        RvGutter {
            HistoryCard(node, times, picked) { picked = it }
        }
        Spacer(Modifier.height(RvSpace.sectionGap))
        RvText(stringResource(R.string.rv_nodes_details), Rv.type.section, Modifier.padding(horizontal = RvSpace.gutter))
        Spacer(Modifier.height(RvSpace.headingGap))
        RvGutter { StatsCard(node, times.last()) }
        // Room under the last card for the floating actions, so it scrolls clear of them.
        Spacer(Modifier.height(RvNodesSize.ctaFade + RvNodesSize.cta + RvSpace.gutter))
        Spacer(Modifier.windowInsetsBottomHeight(insets))
    }
}

/** The flag, `協定 · 地區`, and the chips that apply: in use, and a latency over the good range; the bar names the node. */
@Composable
private fun Top(node: RvNode, current: Boolean) {
    Column(Modifier.padding(horizontal = RvSpace.gutter)) {
        RvFlag(node.place.name, RvNodesSize.detailFlag)
        Spacer(Modifier.height(RvSpace.headingGap))
        RvText(node.protocol + SEPARATOR + stringResource(node.place.label), Rv.type.rowSub, color = Rv.colors.textSecondary)
        val high = latencyTone(node.latencyMs).let { it == Tone.Warn || it == Tone.Err }
        if (current || high) {
            Spacer(Modifier.height(RvSpace.headingGap))
            Row(horizontalArrangement = Arrangement.spacedBy(RvSpace.tight * 2)) {
                if (current) RvStatusChip(R.drawable.rv_nodes_ic_check_circle, stringResource(R.string.rv_nodes_in_use), Rv.colors.positive)
                if (high) RvStatusChip(R.drawable.rv_nodes_ic_warning, stringResource(R.string.rv_nodes_latency_high), latencyColor(node.latencyMs))
            }
        }
    }
    Spacer(Modifier.height(RvSpace.titleToCard))
}

/** 延遲紀錄: the header, the picked test's latency over its time and the median, and the dot chart. */
@Composable
private fun HistoryCard(node: RvNode, times: List<String>, picked: Int, onPick: (Int) -> Unit) {
    RvCard {
        RvCardHeader(stringResource(R.string.rv_nodes_history))
        Column(Modifier.padding(horizontal = RvSpace.rowPadding)) {
            Figure(latencyText(node.history[picked]))
            val median = node.medianMs?.let { SEPARATOR + stringResource(R.string.rv_nodes_median, latencyText(it)) }.orEmpty()
            RvText(times[picked] + median, Rv.type.tileCaption, color = Rv.colors.textSecondary)
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
    BasicText(styled, style = type.cardValue.copy(color = Rv.colors.text), maxLines = 1)
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

/** The floating actions over a fade from the page, on the page's colour down through the gesture bar. */
@Composable
private fun Actions(current: Boolean, onUse: () -> Unit, onTest: () -> Unit, insets: WindowInsets, modifier: Modifier) {
    val page = Rv.colors.page
    Column(modifier.fillMaxWidth()) {
        Spacer(Modifier.fillMaxWidth().height(RvNodesSize.ctaFade).background(Brush.verticalGradient(listOf(page.copy(alpha = 0f), page))))
        Column(Modifier.fillMaxWidth().background(page).padding(horizontal = RvSpace.gutter)) {
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
}

private const val TLS = "TLS"

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
