package dev.doona.android.prototype.rv

import android.content.res.Configuration
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import dev.doona.android.R
import dev.doona.android.designsystem.theme.LocalFrameInsets
import dev.doona.android.engine.Mode
import dev.doona.android.engine.Status
import dev.doona.android.feature.NOT_WIRED
import dev.doona.android.feature.activity.ActivityAction
import dev.doona.android.feature.activity.ActivityUiState
import dev.doona.android.prototype.rv.data.RvData
import dev.doona.android.prototype.rv.data.fastest

/**
 * The 活動 screen in Revolut's home structure (#11, #15): a header with the connection state, the download rate as the
 * hero figure and the upload rate under it on a dot map of the world that marks the node's country (#07, #08), four round
 * actions, then a grouped card with the global outbound and the node it uses, download and upload as two of Revolut's
 * square home tiles (either opens the traffic page), and three more cards as Revolut's home goes on: 今日用量, 訂閱 and
 * 最快節點. Over the content sit the glass top bar (measured §4) and the floating glass bottom bar (§9), which shrinks
 * while the content scrolls up. It renders [ActivityUiState] unchanged; the three lower cards read [RvData].
 */
@Composable
internal fun RvActivityScreen(state: ActivityUiState, onAction: (ActivityAction) -> Unit, onConnect: () -> Unit, routes: RvActivityRoutes = RvActivityRoutes()) {
    val insets = LocalFrameInsets.current ?: WindowInsets.systemBars
    val scroll = rememberScrollState()
    val glass = rememberRvGlass()
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().rvGlassSource(glass).background(Rv.colors.page).verticalScroll(scroll)) {
            Header(state, onAction, onConnect, insets)
            RvGutter {
                OutboundCard(state, routes)
                TrafficTiles(state, routes.traffic)
                RvUsageCard(RvData.today, RvData.nowHour, RvData.YESTERDAY_BY_NOW)
                RvSubscriptionsCard(RvData.subscriptions)
                RvFastestCard(RvData.nodes.fastest(FASTEST), RvData.current.id, routes.nodes)
            }
            Spacer(Modifier.height(rvNavClearance(insets)))
        }
        RvTopBar(glass, insets, rvShade(scroll), onSearch = routes.nodes, onStats = routes.stats, Modifier.align(Alignment.TopCenter))
        RvFloatingNav(RV_NAV_ACTIVITY, routes.tab, glass, rvCollapsedOnScroll(scroll), insets, Modifier.align(Alignment.BottomCenter))
    }
}

/** Where 活動 leads: a traffic tile, 統計, 節點 (the search pill and the outbound row), the node row, and the nav's tabs. */
internal class RvActivityRoutes(
    val traffic: () -> Unit = {},
    val stats: () -> Unit = {},
    val nodes: () -> Unit = {},
    val node: () -> Unit = {},
    val tab: (Int) -> Unit = {},
)

@Composable
private fun Header(state: ActivityUiState, onAction: (ActivityAction) -> Unit, onConnect: () -> Unit, insets: WindowInsets) {
    val format = rememberRvFormat()
    val running = state.status is Status.Running || state.status is Status.Starting
    // Direct traffic uses no node, so the map marks no place.
    val place = state.node?.takeIf { state.mode != Mode.Direct }?.let { RvData.node(it.id)?.place }
    val statusBar = with(LocalDensity.current) { insets.getTop(this).toDp() }
    var heroBounds by remember { mutableStateOf<Rect?>(null) }
    // The dots run up behind the status bar, as Revolut's texture does, in the accent only while connected.
    RvHeader(place, statusBar + RvDots.focusTop, statusBar, if (running) Rv.colors.dots else Rv.colors.dotsIdle, heroBounds) {
        Spacer(Modifier.windowInsetsTopHeight(insets))
        Spacer(Modifier.height(RvSpace.headerTop))
        Column(Modifier.onPlaced { heroBounds = it.boundsInParent() }, horizontalAlignment = Alignment.CenterHorizontally) {
            RvText("${stringResource(state.status.label)} · ${stringResource(state.mode.label)}", Rv.type.caption)
            Spacer(Modifier.height(RvSpace.tight))
            RvHero(format.rate(state.downRate.toDouble()))
            Spacer(Modifier.height(RvSpace.subGap))
            // The hero is the download rate, so the line under it carries the other direction only (N-10).
            RvInline(R.drawable.rv_sym_arrow_upward, format.rate(state.upRate.toDouble()).toString(), Rv.colors.textSecondary, Rv.type.sub)
        }
        Spacer(Modifier.height(RvSpace.heroToActions))
        Row(Modifier.fillMaxWidth().padding(horizontal = RvSpace.gutter)) {
            val other = if (state.mode == Mode.Global) Mode.Direct else Mode.Global
            val toggle = if (running) ({ onAction(ActivityAction.Disconnect) }) else onConnect
            RvAction(R.drawable.rv_sym_power, stringResource(if (running) R.string.rv_disconnect else R.string.rv_connect), toggle, Modifier.weight(1f))
            RvAction(R.drawable.rv_sym_directions, stringResource(R.string.act_mode), { onAction(ActivityAction.SetMode(other)) }, Modifier.weight(1f))
            RvAction(R.drawable.rv_sym_speed, stringResource(R.string.rv_test_latency), NOT_WIRED, Modifier.weight(1f))
            RvAction(R.drawable.rv_sym_more_horiz, stringResource(R.string.rv_more), NOT_WIRED, Modifier.weight(1f))
        }
        Spacer(Modifier.height(RvSpace.actionsToCard))
    }
}

@Composable
private fun OutboundCard(state: ActivityUiState, routes: RvActivityRoutes) {
    RvCard {
        RvRow(
            { RvIconDisc(R.drawable.rv_sym_directions) },
            stringResource(R.string.act_global),
            state.outbound ?: RV_NO_VALUE,
            onClick = routes.nodes,
        ) { RvChevron() }
        // Direct traffic uses no node, so there is no node or latency to show.
        if (state.mode == Mode.Direct) return@RvCard
        val node = state.node
        val listed = node?.let { RvData.node(it.id) }
        RvRow(
            // The node in use: its flag carries the current node's check.
            { RvFlag(listed?.place?.name, RvSize.rowDisc, current = node != null) },
            node?.name ?: RV_NO_VALUE,
            stringResource(R.string.nav_nodes),
            onClick = routes.node,
            // Its latency over the change since the test before, as 節點 and the 最快節點 rows show it (L-12).
        ) { RvLatencyChange(node?.latencyMs?.takeUnless { node.failed }, listed?.changeMs?.takeUnless { node.failed }) }
    }
}

/**
 * Download and upload as two tiles side by side, each on its own scale: the rate, and the last minute's sparkline with
 * that minute's peak, so the label and the curve beside it cover the same window (S01; 流量詳情 marks the same peak on
 * the same minute). The session's peak is shown only where it is labelled 本次. Disconnected, or with no traffic in the
 * window, both tiles say so over ghost bars instead.
 */
@Composable
private fun TrafficTiles(state: ActivityUiState, onOpenTraffic: () -> Unit) {
    val quiet = state.status !is Status.Running || (state.downHistory.all { it == 0f } && state.upHistory.all { it == 0f })
    Row(horizontalArrangement = Arrangement.spacedBy(RvSpace.tileGap)) {
        TrafficTile(R.string.ui_download, R.drawable.rv_sym_arrow_downward, state.downRate, minutePeak(state.downHistory), state.downHistory, Rv.colors.down, quiet, onOpenTraffic, Modifier.weight(1f))
        TrafficTile(R.string.ui_upload, R.drawable.rv_sym_arrow_upward, state.upRate, minutePeak(state.upHistory), state.upHistory, Rv.colors.up, quiet, onOpenTraffic, Modifier.weight(1f), RV_GHOST_UP)
    }
}

@Composable
private fun TrafficTile(
    @StringRes caption: Int,
    @DrawableRes icon: Int,
    rate: Long,
    peak: Float,
    history: List<Float>,
    series: RvSeries,
    quiet: Boolean,
    onClick: () -> Unit,
    modifier: Modifier,
    ghost: FloatArray = RV_GHOST_DOWN,
) {
    val format = rememberRvFormat()
    val note = if (quiet) stringResource(R.string.rv_no_traffic) else stringResource(R.string.rv_peak, format.rate(peak.toDouble()).toString())
    RvSparkTile(stringResource(caption), icon, format.rate(rate.toDouble()).figure(), note, history, series, quiet, onClick, modifier, ghost)
}

/** The fastest second of a minute's samples; a missing sample (NaN, a gap in the line) is not a rate. */
private fun minutePeak(history: List<Float>): Float = history.filterNot { it.isNaN() }.maxOrNull() ?: 0f

/** The 最快節點 card lists the first three of the 節點 page's fastest. */
private const val FASTEST = 3

private val Status.label: Int
    get() = when (this) {
        is Status.Running -> R.string.backend_connected
        Status.Starting -> R.string.status_starting
        Status.Stopping -> R.string.status_stopping
        Status.Stopped, is Status.Failed -> R.string.status_stopped
    }

private val Mode.label: Int
    get() = when (this) {
        Mode.Global -> R.string.mode_global
        Mode.Direct -> R.string.mode_direct
    }

@Preview(name = "Light", locale = "zh-rTW", widthDp = 412, heightDp = 915)
@Preview(name = "Dark", locale = "zh-rTW", widthDp = 412, heightDp = 915, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun RvActivityScreenPreview() {
    val dark = isSystemInDarkTheme()
    RvTheme(rvSystemScheme(dark), dark) { RvActivityScreen(rvSampleActivity(), onAction = {}, onConnect = {}) }
}
