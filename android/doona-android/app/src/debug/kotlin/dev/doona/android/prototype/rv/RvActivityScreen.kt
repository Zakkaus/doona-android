package dev.doona.android.prototype.rv

import android.content.Context
import android.content.res.Configuration
import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
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
import dev.doona.android.feature.activity.sampleActivityState

/**
 * The 活動 screen in Revolut's home structure (#11, #15): a header with the connection state, the download rate as the
 * hero figure and both rates under it on a dot map of the world that marks the node's country (#07, #08), four round
 * actions, then a grouped card with the global outbound and the node it uses, download and upload as two of Revolut's
 * home tiles (either opens the traffic page), and the bottom navigation. It renders [ActivityUiState] unchanged.
 */
@Composable
internal fun RvActivityScreen(
    state: ActivityUiState,
    onAction: (ActivityAction) -> Unit,
    onConnect: () -> Unit,
    onOpenGroup: (String) -> Unit,
    onOpenTraffic: () -> Unit,
) {
    val insets = LocalFrameInsets.current ?: WindowInsets.systemBars
    Column(Modifier.fillMaxSize().background(Rv.colors.page)) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Header(state, onAction, onConnect, insets)
            RvGutter {
                OutboundCard(state, onOpenGroup)
                TrafficTiles(state, onOpenTraffic)
            }
            Spacer(Modifier.height(RvSpace.cardGap))
        }
        RvBottomNav(NAV, selected = 0, onSelect = { NOT_WIRED() }, insets)
    }
}

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
            RvInline(R.drawable.ic_download, rateText(context, state.downRate), Rv.colors.textSecondary, Rv.type.sub)
            RvInline(R.drawable.ic_upload, rateText(context, state.upRate), Rv.colors.textSecondary, Rv.type.sub)
        }
        Spacer(Modifier.height(RvSpace.heroToActions))
        Row(Modifier.fillMaxWidth().padding(horizontal = RvSpace.gutter)) {
            val other = if (state.mode == Mode.Global) Mode.Direct else Mode.Global
            val toggle = if (running) ({ onAction(ActivityAction.Disconnect) }) else onConnect
            RvAction(R.drawable.rv_ic_power, stringResource(if (running) R.string.rv_disconnect else R.string.rv_connect), toggle, Modifier.weight(1f))
            RvAction(R.drawable.rv_ic_route, stringResource(R.string.act_mode), { onAction(ActivityAction.SetMode(other)) }, Modifier.weight(1f))
            RvAction(R.drawable.rv_ic_speed, stringResource(R.string.rv_test_latency), NOT_WIRED, Modifier.weight(1f))
            RvAction(R.drawable.rv_ic_more, stringResource(R.string.rv_more), NOT_WIRED, Modifier.weight(1f))
        }
        Spacer(Modifier.height(RvSpace.actionsToCard))
    }
}

@Composable
private fun OutboundCard(state: ActivityUiState, onOpenGroup: (String) -> Unit) {
    RvCard {
        RvRow(
            { RvIconDisc(R.drawable.ic_filter) },
            stringResource(R.string.act_global),
            state.outbound ?: RV_NO_VALUE,
            onClick = state.outboundGroupId?.let { id -> { onOpenGroup(id) } },
        ) { RvGlyph(R.drawable.ic_chevron_right, RvSize.chevron, Rv.colors.textSecondary) }
        // Direct traffic uses no node, so there is no node or latency to show.
        if (state.mode == Mode.Direct) return@RvCard
        val node = state.node
        RvRow({ RvIconDisc(R.drawable.ic_clock) }, node?.name ?: RV_NO_VALUE, stringResource(R.string.nav_nodes)) {
            RvLatency(node?.latencyMs?.takeUnless { node.failed })
        }
    }
}

/** Download and upload as two tiles side by side: the rate, the window's peak, and the minute's sparkline. */
@Composable
private fun TrafficTiles(state: ActivityUiState, onOpenTraffic: () -> Unit) {
    val context = LocalContext.current
    Row(horizontalArrangement = Arrangement.spacedBy(RvSpace.tileGap)) {
        RvSparkTile(
            stringResource(R.string.ui_download),
            R.drawable.ic_download,
            rateText(context, state.downRate),
            peakLine(state.downHistory),
            state.downHistory,
            Rv.colors.down,
            onOpenTraffic,
            Modifier.weight(1f),
        )
        RvSparkTile(
            stringResource(R.string.ui_upload),
            R.drawable.ic_upload,
            rateText(context, state.upRate),
            peakLine(state.upHistory),
            state.upHistory,
            Rv.colors.up,
            onOpenTraffic,
            Modifier.weight(1f),
        )
    }
}

/** The highest rate of the window, after its label: Peak 5.2 MB/s. */
@Composable
private fun peakLine(history: List<Float>): String = stringResource(R.string.rv_peak, rateText(LocalContext.current, history.maxOrNull()?.toLong() ?: 0L))

/** A rate split for the hero: the figure, and its unit with "/s" set small after it. */
private fun rateParts(context: Context, bytesPerSecond: Long): Pair<String, String> {
    val size = Formatter.formatShortFileSize(context, bytesPerSecond)
    val cut = size.lastIndexOf(' ')
    return if (cut < 0) size to PER_SECOND else size.substring(0, cut) to size.substring(cut) + PER_SECOND
}

private fun rateText(context: Context, bytesPerSecond: Long) = Formatter.formatShortFileSize(context, bytesPerSecond) + PER_SECOND

private const val PER_SECOND = "/s"

private val NAV = listOf(
    RvNavItem(R.drawable.rv_ic_nav_activity, R.string.hub_activity),
    RvNavItem(R.drawable.rv_ic_nav_policies, R.string.nav_policies),
    RvNavItem(R.drawable.rv_ic_nav_settings, R.string.hub_settings),
)

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
    RvTheme(rvSystemScheme(dark), dark) { RvActivityScreen(sampleActivityState(), onAction = {}, onConnect = {}, onOpenGroup = {}, onOpenTraffic = {}) }
}
