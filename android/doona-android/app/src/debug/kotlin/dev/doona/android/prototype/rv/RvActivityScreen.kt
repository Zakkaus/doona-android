package dev.doona.android.prototype.rv

import android.content.Context
import android.content.res.Configuration
import android.text.format.Formatter
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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

/**
 * The 活動 screen in Revolut's home structure (#11, #15): a header with the connection state, the download rate as the
 * hero figure and both rates under it on a dot map of the world that marks the node's country (#07, #08), four round
 * actions, then a grouped card with the global outbound and the node it uses, download and upload as two of Revolut's
 * square home tiles (either opens the traffic page), and three more cards as Revolut's home goes on: 今日用量, 訂閱 and
 * 最快節點. Over the content sit the glass top bar (measured §4) and the floating glass bottom bar (§9), which shrinks
 * while the content scrolls up. It renders [ActivityUiState] unchanged; the three lower cards show invented data.
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
                RvUsageCard(remember { RvUsageSample.today() })
                RvSubscriptionsCard(RV_SAMPLE_SUBSCRIPTIONS)
                RvFastestCard(RV_SAMPLE_FASTEST)
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
    val context = LocalContext.current
    val running = state.status is Status.Running || state.status is Status.Starting
    val (whole, unit) = rateParts(context, state.downRate)
    // Direct traffic uses no node, so the map marks no place.
    val place = state.node?.name?.takeIf { state.mode != Mode.Direct }?.let(::placeOf)
    val statusBar = with(LocalDensity.current) { insets.getTop(this).toDp() }
    // The dots run up behind the status bar, as Revolut's texture does.
    RvHeader(place, statusBar + RvDots.focusTop) {
        Spacer(Modifier.windowInsetsTopHeight(insets))
        Spacer(Modifier.height(RvSpace.headerTop))
        RvText("${stringResource(state.status.label)} · ${stringResource(state.mode.label)}", Rv.type.caption)
        Spacer(Modifier.height(RvSpace.tight))
        RvHero(whole, unit)
        Spacer(Modifier.height(RvSpace.subGap))
        Row(horizontalArrangement = Arrangement.spacedBy(RvSpace.rowPadding)) {
            RvInline(R.drawable.rv_sym_arrow_downward, rateText(context, state.downRate), Rv.colors.textSecondary, Rv.type.sub)
            RvInline(R.drawable.rv_sym_arrow_upward, rateText(context, state.upRate), Rv.colors.textSecondary, Rv.type.sub)
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
        ) { RvGlyph(R.drawable.rv_sym_chevron_right, RvSize.chevron, Rv.colors.textSecondary) }
        // Direct traffic uses no node, so there is no node or latency to show.
        if (state.mode == Mode.Direct) return@RvCard
        val node = state.node
        RvRow(
            { RvFlag(node?.name?.let(::placeOf)?.name, RvSize.rowDisc) },
            node?.name?.let(::withoutFlag) ?: RV_NO_VALUE,
            stringResource(R.string.nav_nodes),
            onClick = routes.node,
        ) { RvLatency(node?.latencyMs?.takeUnless { node.failed }) }
    }
}

/**
 * Download and upload as two tiles side by side, each on its own scale: the rate, the window's peak, and the minute's
 * sparkline. Disconnected, or with no traffic in the window, both tiles say so over ghost bars instead.
 */
@Composable
private fun TrafficTiles(state: ActivityUiState, onOpenTraffic: () -> Unit) {
    val quiet = state.status !is Status.Running || (state.downHistory.all { it == 0f } && state.upHistory.all { it == 0f })
    Row(horizontalArrangement = Arrangement.spacedBy(RvSpace.tileGap)) {
        TrafficTile(R.string.ui_download, R.drawable.rv_sym_arrow_downward, state.downRate, state.downHistory, Rv.colors.down, quiet, onOpenTraffic, Modifier.weight(1f))
        TrafficTile(R.string.ui_upload, R.drawable.rv_sym_arrow_upward, state.upRate, state.upHistory, Rv.colors.up, quiet, onOpenTraffic, Modifier.weight(1f))
    }
}

@Composable
private fun TrafficTile(
    @StringRes caption: Int,
    @DrawableRes icon: Int,
    rate: Long,
    history: List<Float>,
    series: RvSeries,
    quiet: Boolean,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val (whole, unit) = rateParts(context, rate)
    val note = if (quiet) stringResource(R.string.rv_no_traffic) else stringResource(R.string.rv_peak, rateText(context, history.maxOrNull()?.toLong() ?: 0L))
    RvSparkTile(stringResource(caption), icon, RvFigure(whole, unit), note, history, series, quiet, onClick, modifier)
}

/** A rate split for the hero: the figure, and its unit with "/s" set small after it. */
private fun rateParts(context: Context, bytesPerSecond: Long): Pair<String, String> {
    val size = Formatter.formatShortFileSize(context, bytesPerSecond)
    val cut = size.lastIndexOf(' ')
    return if (cut < 0) size to PER_SECOND else size.substring(0, cut) to size.substring(cut) + PER_SECOND
}

private fun rateText(context: Context, bytesPerSecond: Long) = Formatter.formatShortFileSize(context, bytesPerSecond) + PER_SECOND

private const val PER_SECOND = "/s"

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
