package dev.doona.android.prototype.rv.diag

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemBars
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.style.TextOverflow
import dev.doona.android.R
import dev.doona.android.designsystem.rv.LocalFrameInsets
import dev.doona.android.designsystem.rv.Rv
import dev.doona.android.designsystem.rv.RvAddRow
import dev.doona.android.designsystem.rv.RvAppDisc
import dev.doona.android.designsystem.rv.RvCaption
import dev.doona.android.designsystem.rv.RvCard
import dev.doona.android.designsystem.rv.RvCardHeader
import dev.doona.android.designsystem.rv.RvChipPick
import dev.doona.android.designsystem.rv.RvFilterChipRow
import dev.doona.android.designsystem.rv.RvFlag
import dev.doona.android.designsystem.rv.RvGutter
import dev.doona.android.designsystem.rv.RvIconDisc
import dev.doona.android.designsystem.rv.RvPageHeader
import dev.doona.android.designsystem.rv.RvRow
import dev.doona.android.designsystem.rv.RvScrollPage
import dev.doona.android.designsystem.rv.RvSegmented
import dev.doona.android.designsystem.rv.RvSize
import dev.doona.android.designsystem.rv.RvSpace
import dev.doona.android.designsystem.rv.RvStatRow
import dev.doona.android.designsystem.rv.RvStatusChip
import dev.doona.android.designsystem.rv.RvTag
import dev.doona.android.designsystem.rv.SEPARATOR
import dev.doona.android.designsystem.rv.rememberRvFormat
import dev.doona.android.prototype.rv.samples.sampleText

/**
 * 流量記錄 (doona web's 分流 › 記錄): what honk recorded of the flows it routed, newest first. The caption counts the
 * retained flows; the two chips filter by transport and by state, as the web toolbar's 網路協定 and 狀態. 流量路徑 opens
 * the web's map tab as a page of its own. Each flow is its app (honk's uid), its target, the rule that decided and where
 * it went, its state over its transport and start; a tap opens the flow. Under the list, 觀測涵蓋範圍 says what honk can
 * see (the web's coverage badge spelt out), and 記錄設定 opens the recorder's mode (`record_flows`).
 */
@Composable
internal fun RvFlowsSample(flows: List<RvFlow> = RvDiagData.flows) {
    val map = RvDiagData.map
    RvDiagPage(
        sampleText("流量記錄", "Flows"),
        caption = sampleText("已保留 ${flows.size} 筆流程", "${flows.size} flows retained"),
        control = { RvFilterChipRow(RvChipPick(sampleText("全部協定", "All protocols")), RvChipPick(sampleText("全部狀態", "All states"))) },
    ) {
        RvGutter {
            RvCard {
                RvDiagNavRow(
                    R.drawable.rv_sym_call_split,
                    sampleText("流量路徑", "Traffic path"),
                    sampleText("${map.rules.size} 條規則 · ${map.outbounds.size} 個出站 · ${map.nodes.size} 個節點", "${map.rules.size} rules · ${map.outbounds.size} outbounds · ${map.nodes.size} nodes"),
                )
            }
            RvCard { flows.forEach { FlowLine(it) } }
        }
        RvDiagSection(sampleText("觀測涵蓋範圍", "Observation coverage"))
        RvGutter {
            RvCard { RvDiagData.coverage.forEach { RvStatRow(it.scope.text(), it.value.text()) } }
            RvCard { RvDiagNavRow(R.drawable.rv_sym_settings, sampleText("記錄設定", "Recording settings"), sampleText("自動", "Auto")) }
        }
    }
}

/** One flow: the app, target:port cut in the middle, the rule and its outbound, the state over transport and start. */
@Composable
private fun FlowLine(flow: RvFlow) {
    val clock = rememberRvFormat().clock(flow.startedAtEpochMs)
    val recomputed = if (flow.recomputed) sampleText("重新評估", "Re-evaluated") + SEPARATOR else ""
    RvRow(
        { RvAppDisc(flow.packageName) },
        flow.target + PORT + flow.port,
        recomputed + flow.rule + ARROW + outText(flow.out),
        onClick = {},
        titleEnd = { RvEndValue(flow.state.word.text(), flow.state.trouble) },
        subtitleEnd = { RvEndSub((if (flow.udp) RV_UDP else RV_TCP) + SEPARATOR + clock) },
        titleLines = 1,
        titleOverflow = TextOverflow.MiddleEllipsis,
        subtitleKeepWords = false,
    )
}

/** A group and its node, or 直連 or 拒絕 alone. */
@Composable
private fun outText(out: RvFlowOut): String = listOfNotNull(out.group.text(), out.node).joinToString(NODE)

/**
 * A flow (doona web's record drawer): the app's disc over the target, the app and transport, and the trace's chips:
 * 完整 or 不完整, and its revision. Then the facts the drawer lists (出站, 節點, 狀態, 規則 and, for a partial trace,
 * 不完整原因, with 規則來源 when honk re-evaluated the rule), 檢視連線 while the connection is open, 新增規則 from this
 * flow, and 追蹤: each recorded stage in order, its time since the flow began on its header and its fields under it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RvFlowDetailSample(flow: RvFlow = RvDiagData.flows.first()) {
    val insets = LocalFrameInsets.current ?: WindowInsets.systemBars
    val format = rememberRvFormat()
    val target = flow.target + PORT + flow.port
    RvScrollPage(target, insets, onBack = {}) { titleBlock ->
        RvPageHeader(
            target,
            titleBlock,
            insets,
            mark = { RvAppDisc(flow.packageName, size = RvSize.headerDisc) },
            caption = { RvCaption(flow.app + SEPARATOR + if (flow.udp) RV_UDP else RV_TCP) },
            control = {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(RvSpace.tight * 2), verticalArrangement = Arrangement.spacedBy(RvSpace.tight * 2)) {
                    if (flow.complete) {
                        RvStatusChip(R.drawable.rv_nodes_ic_check_circle, sampleText("完整", "Complete"), Rv.colors.positive)
                    } else {
                        RvStatusChip(R.drawable.rv_nodes_ic_warning, sampleText("不完整", "Partial"), Rv.colors.fair)
                    }
                    RvTag(sampleText("修訂 ${flow.revision}", "Revision ${flow.revision}"))
                }
            },
        )
        RvGutter {
            RvCard {
                RvStatRow(sampleText("出站", "Outbound"), flow.out.group.text())
                flow.out.node?.let { RvStatRow(sampleText("節點", "Node"), it, valueKeepWords = false) }
                RvStatRow(sampleText("狀態", "State"), flow.state.word.text())
                RvStatRow(sampleText("規則", "Rule"), flow.rule, valueKeepWords = false)
                if (flow.recomputed) RvStatRow(sampleText("規則來源", "Rule source"), sampleText("重新評估", "Re-evaluated"))
                if (flow.missing.isNotEmpty()) RvStatRow(sampleText("不完整原因", "Why incomplete"), flow.missing.map { it.text() }.joinToString(sampleText("、", ", ")))
                RvStatRow(sampleText("開始", "Started"), format.clock(flow.startedAtEpochMs))
            }
            if (flow.connectionId != null) RvCard { RvDiagNavRow(R.drawable.rv_sym_sync_alt, sampleText("檢視連線", "View connection")) }
            RvCard { RvAddRow(sampleText("新增規則", "Add rule"), {}) }
        }
        if (flow.steps.isNotEmpty()) {
            RvDiagSection(sampleText("追蹤", "Trace"))
            RvGutter {
                flow.steps.forEach { step ->
                    RvCard {
                        RvCardHeader(step.stage.text(), tag = rvCount(step.elapsedUs) + MICROSECONDS)
                        step.facts.forEach { RvStatRow(it.label.text(), it.value.text(), valueKeepWords = false) }
                    }
                }
            }
        }
    }
}

/**
 * 流量路徑 (doona web's 分流 › 總覽, the topology): the flows honk keeps, grouped by the rule that decided them (or by
 * the device that sent them), the outbound each rule sends to, and the node each group picked, each with its count of
 * flows. A rule's line names its outbound, an outbound's its policy and its node, a node's the groups that reach it and
 * its latency; 直連 and 拒絕 end the path. A row opens 流量記錄 filtered to that path.
 */
@Composable
internal fun RvFlowMapSample(byDevice: Boolean = false) {
    val map = RvDiagData.map
    val insets = LocalFrameInsets.current ?: WindowInsets.systemBars
    val title = sampleText("流量路徑", "Traffic path")
    val total = RvDiagData.flows.size
    RvScrollPage(title, insets, onBack = {}) { titleBlock ->
        val options = listOf(sampleText("依規則", "By rule"), sampleText("依裝置", "By device"))
        RvPageHeader(title, titleBlock, insets, caption = { RvCaption(sampleText("$total 筆流程", "$total flows")) }, control = { RvSegmented(options, if (byDevice) 1 else 0, {}) })
        RvDiagSection(if (byDevice) sampleText("裝置", "Devices") else sampleText("規則", "Rules"))
        RvGutter { RvCard { (if (byDevice) map.devices else map.rules).forEach { Tile(it) { RvIconDisc(if (byDevice) R.drawable.rv_sym_apps else R.drawable.rv_sym_rule) } } } }
        RvDiagSection(sampleText("出站", "Outbounds"))
        RvGutter { RvCard { map.outbounds.forEach { Tile(it) { RvIconDisc(it.glyph ?: R.drawable.rv_sym_account_tree) } } } }
        RvDiagSection(sampleText("節點", "Nodes"))
        RvGutter { RvCard { map.nodes.forEach { Tile(it) { RvFlag(it.place?.flag, RvSize.rowDisc) } } } }
    }
}

/** A tile of the map as a row: its glyph, its name, its line, and its count of flows at the end. */
@Composable
private fun Tile(tile: RvMapTile, leading: @Composable () -> Unit) {
    val n = tile.flows
    RvRow(
        leading,
        tile.title.text(),
        tile.line?.text(),
        onClick = {},
        titleKeepWords = false,
        subtitleKeepWords = false,
        titleCode = tile.code,
        trailing = { RvEndValue(sampleText("$n 筆", if (n == 1) "1 flow" else "$n flows")) },
    )
}

private const val PORT = ":"
private const val ARROW = " → "
private const val NODE = " › "
private const val MICROSECONDS = " μs"
