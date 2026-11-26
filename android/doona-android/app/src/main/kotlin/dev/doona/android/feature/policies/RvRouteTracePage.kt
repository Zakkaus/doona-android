package dev.doona.android.feature.policies

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import dev.doona.android.R
import dev.doona.android.designsystem.rv.LocalFrameInsets
import dev.doona.android.designsystem.rv.RvCard
import dev.doona.android.designsystem.rv.RvDiffAt
import dev.doona.android.designsystem.rv.RvDiffKind
import dev.doona.android.designsystem.rv.RvDiffLine
import dev.doona.android.designsystem.rv.RvFieldStack
import dev.doona.android.designsystem.rv.RvGutter
import dev.doona.android.designsystem.rv.RvLargeTitle
import dev.doona.android.designsystem.rv.RvNotice
import dev.doona.android.designsystem.rv.RvNoticeKind
import dev.doona.android.designsystem.rv.RvPrimaryButton
import dev.doona.android.designsystem.rv.RvScrollPage
import dev.doona.android.designsystem.rv.RvSectionTitle
import dev.doona.android.designsystem.rv.RvSegmented
import dev.doona.android.designsystem.rv.RvSourceExcerpt
import dev.doona.android.designsystem.rv.RvSpace
import dev.doona.android.designsystem.rv.RvStatRow
import dev.doona.android.designsystem.rv.RvTextField
import dev.doona.android.feature.model.RV_BLOCK
import dev.doona.android.feature.model.RV_DIRECT

/** The transport a trace asks about. */
internal enum class RvTraceProto(val label: String) {
    Tcp("TCP"),
    Udp("UDP"),
}

/** The tuple to trace: a domain or address, a port and a transport, as typed. */
@Immutable
internal data class RvTraceQuery(val host: String, val port: String, val proto: RvTraceProto = RvTraceProto.Tcp)

/**
 * What `routeTrace` answers (config plan line 83): the statement that decided, by its [source] file, [line] and [text],
 * or the list's fallback ([fallback]); where the traffic goes ([target], a group, `direct` or `block`, and the [node]
 * the group picks); whether the VPN carries it ([captured]); and a [skipped] statement before the winner that the
 * phone does not apply (its file and line), which the page reports.
 */
@Immutable
internal data class RvTraceResult(
    val source: String,
    val line: Int,
    val text: String,
    val target: String,
    val node: String? = null,
    val fallback: Boolean = false,
    val captured: Boolean = true,
    val skipped: Pair<String, Int>? = null,
)

/**
 * 路由測試 (config plan row 17 and line 122; no prototype, sample only): the destination to trace, as fields; 測試 at
 * the bottom asks the core without dialing. The answer names the statement that decided, as the source line it is
 * (a tap opens the file there), under 符合的規則, or under 其餘流量 when no rule matched, so a fallback never reads as
 * a rule; then where the traffic goes (拒絕 for `block`), the mode, and whether it passes through the VPN. A skipped
 * statement before the winner heads the answer as a warning that opens its line. [banner] tells why a trace failed.
 */
@Composable
internal fun RvRouteTracePage(
    query: RvTraceQuery,
    onQuery: (RvTraceQuery) -> Unit,
    onBack: () -> Unit,
    result: RvTraceResult? = null,
    onTrace: () -> Unit = {},
    onOpenSource: (String, Int) -> Unit = { _, _ -> },
    banner: RvNotice? = null,
) {
    val insets = LocalFrameInsets.current ?: WindowInsets.systemBars
    val title = stringResource(R.string.rv_trace_title)
    val port = query.port.toIntOrNull()
    RvScrollPage(
        title,
        insets,
        onBack = onBack,
        bottomAction = { RvPrimaryButton(stringResource(R.string.rv_trace_run), onTrace, enabled = query.host.isNotBlank() && port != null && port in PORTS) },
    ) { titleBlock ->
        RvLargeTitle(title, titleBlock, insets)
        RvSectionTitle(stringResource(R.string.rv_trace_target), Modifier.padding(horizontal = RvSpace.gutter), first = true)
        RvGutter(banner = banner) {
            RvFieldStack {
                RvTextField(query.host, { onQuery(query.copy(host = it)) }, label = stringResource(R.string.rv_trace_host))
                RvTextField(
                    query.port,
                    { onQuery(query.copy(port = it.filter(Char::isDigit))) },
                    label = stringResource(R.string.rv_nodes_port),
                    keyboard = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                RvSegmented(RvTraceProto.entries.map { it.label }, query.proto.ordinal, { onQuery(query.copy(proto = RvTraceProto.entries[it])) })
            }
        }
        result?.let { Answer(it, onOpenSource) }
    }
}

@Composable
private fun Answer(result: RvTraceResult, onOpenSource: (String, Int) -> Unit) {
    val heading = if (result.fallback) R.string.rv_policy_rest else R.string.rv_trace_matched
    RvSectionTitle(stringResource(heading), Modifier.padding(horizontal = RvSpace.gutter))
    val banner = result.skipped?.let { (file, line) ->
        RvNotice(
            RvNoticeKind.Warning,
            stringResource(R.string.rv_trace_skipped_title),
            stringResource(R.string.rv_trace_skipped_reason, file, line),
            stringResource(R.string.rv_config_edit_source),
            { onOpenSource(file, line) },
            reasonKeepWords = false,
        )
    }
    RvGutter(banner = banner) {
        RvSourceExcerpt(result.source, listOf(RvDiffLine(RvDiffKind.Same, result.line, result.text)), { at: RvDiffAt -> onOpenSource(result.source, at.line) })
    }
    RvSectionTitle(stringResource(R.string.rv_trace_result), Modifier.padding(horizontal = RvSpace.gutter))
    RvGutter {
        RvCard {
            RvStatRow(stringResource(R.string.rv_policy_send_to), sendTo(result), valueKeepWords = false)
            RvStatRow(stringResource(R.string.rv_effective_mode), stringResource(R.string.rv_mode_rule))
            RvStatRow(stringResource(R.string.rv_trace_captured), stringResource(if (result.captured) R.string.rv_trace_yes else R.string.rv_trace_no))
        }
    }
}

/** 拒絕 for `block`, 直連 for `direct`, else the group and the node it picks. */
@Composable
private fun sendTo(result: RvTraceResult): String = when (result.target) {
    RV_BLOCK -> stringResource(R.string.rv_policy_reject)
    RV_DIRECT -> stringResource(R.string.mode_direct)
    else -> listOfNotNull(result.target, result.node).joinToString(TARGET_SEPARATOR)
}

private const val TARGET_SEPARATOR = " › "
private val PORTS = 1..65_535
