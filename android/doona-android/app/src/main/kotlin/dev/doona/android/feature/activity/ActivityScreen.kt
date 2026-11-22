package dev.doona.android.feature.activity

import android.text.format.Formatter
import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.doona.android.R
import dev.doona.android.designsystem.components.DoonaBarAction
import dev.doona.android.designsystem.components.DoonaCard
import dev.doona.android.designsystem.components.DoonaControlCard
import dev.doona.android.designsystem.components.DoonaErrorBanner
import dev.doona.android.designsystem.components.DoonaGap
import dev.doona.android.designsystem.components.DoonaHGap
import dev.doona.android.designsystem.components.DoonaIcon
import dev.doona.android.designsystem.components.DoonaMetricLatency
import dev.doona.android.designsystem.components.DoonaMetricTile
import dev.doona.android.designsystem.components.DoonaMetricValue
import dev.doona.android.designsystem.components.DoonaNodeName
import dev.doona.android.designsystem.components.DoonaPage
import dev.doona.android.designsystem.components.DoonaPageTabs
import dev.doona.android.designsystem.components.DoonaPageTitle
import dev.doona.android.designsystem.components.DoonaPicker
import dev.doona.android.designsystem.components.DoonaSegmented
import dev.doona.android.designsystem.components.DoonaSettingsRow
import dev.doona.android.designsystem.components.DoonaSparkline
import dev.doona.android.designsystem.components.DoonaSwitchRow
import dev.doona.android.designsystem.components.DoonaTileCaption
import dev.doona.android.designsystem.components.DoonaTileRow
import dev.doona.android.designsystem.theme.DoonaTheme
import dev.doona.android.engine.Engine
import dev.doona.android.engine.Mode
import dev.doona.android.engine.Status
import dev.doona.android.feature.NOT_WIRED
import dev.doona.android.feature.messageRes
import dev.doona.android.icons.R as IconsR

/**
 * The 活動 entry: builds the ViewModel and collects its state while the screen is started. Connecting (which needs
 * the activity for consent) and opening a group (navigation) go straight to the shell's callbacks.
 */
@Composable
fun ActivityRoute(engine: Engine, onConnect: () -> Unit, onOpenGroup: (String) -> Unit) {
    val viewModel: ActivityViewModel = viewModel(factory = ActivityViewModel.factory(engine))
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    ActivityScreen(state, viewModel::onAction, onConnect, onOpenGroup)
}

/**
 * 活動 (doona `src/features/activity`): the connection card this client adds, doona's quick controls (outbound mode,
 * global outbound), then the traffic tiles two per row and the current node's latency across both columns.
 */
@Composable
fun ActivityScreen(state: ActivityUiState, onAction: (ActivityAction) -> Unit, onConnect: () -> Unit, onOpenGroup: (String) -> Unit) {
    val space = DoonaTheme.spacing
    DoonaPage(
        actions = listOf(
            DoonaBarAction(IconsR.drawable.sym_search, stringResource(R.string.ui_search), NOT_WIRED),
            DoonaBarAction(IconsR.drawable.sym_more_vert, stringResource(R.string.ui_more_options), NOT_WIRED),
        ),
    ) {
        DoonaPageTitle(stringResource(R.string.hub_activity))
        DoonaGap(space.afterTitle)
        DoonaPageTabs(listOf(stringResource(R.string.hub_activity), stringResource(R.string.nav_overview)), selected = 0)
        DoonaGap(space.section)
        Column(verticalArrangement = Arrangement.spacedBy(space.block)) {
            state.error?.let { code ->
                DoonaErrorBanner(stringResource(code.messageRes()), stringResource(R.string.ui_dismiss), { onAction(ActivityAction.DismissError) })
            }
            ConnectionCard(state.status, state.hasSubscriptions, onSwitch = { if (it) onConnect() else onAction(ActivityAction.Disconnect) })
            DoonaControlCard(IconsR.drawable.sym_shuffle, stringResource(R.string.act_mode)) {
                DoonaSegmented(
                    Mode.entries.map { stringResource(it.label) },
                    selected = state.mode.ordinal,
                    onSelect = { onAction(ActivityAction.SetMode(Mode.entries[it])) },
                    modifier = Modifier.weight(1f),
                )
            }
            DoonaControlCard(IconsR.drawable.sym_filter_alt, stringResource(R.string.act_global)) {
                DoonaPicker(state.outbound ?: NO_VALUE, onClick = { state.outboundGroupId?.let(onOpenGroup) })
            }
        }
        DoonaGap(space.section)
        TrafficTiles(state, onOpenGroup)
    }
}

private const val NO_VALUE = "—"

private val Mode.label: Int
    get() = when (this) {
        Mode.Global -> R.string.mode_global
        Mode.Direct -> R.string.mode_direct
    }

@Composable
private fun TrafficTiles(state: ActivityUiState, onOpenGroup: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(DoonaTheme.spacing.block)) {
        DoonaTileRow {
            RateTile(IconsR.drawable.sym_download, DoonaTheme.colors.down.line, stringResource(R.string.ui_download), rateText(state.downRate), state.downHistory)
            RateTile(IconsR.drawable.sym_upload, DoonaTheme.colors.up.line, stringResource(R.string.ui_upload), rateText(state.upRate), state.upHistory)
        }
        // Direct traffic uses no node, so there is no latency to show.
        if (state.mode == Mode.Direct) return@Column
        val node = state.node
        val latency = node?.latencyMs?.takeUnless { node.failed }
        DoonaMetricTile(
            icon = IconsR.drawable.sym_schedule,
            caption = { DoonaNodeName(node?.name ?: stringResource(R.string.act_latency), DoonaTheme.type.footnote, color = DoonaTheme.colors.textSecondary) },
            modifier = Modifier.fillMaxWidth(),
            onClick = state.outboundGroupId?.let { id -> { onOpenGroup(id) } },
        ) {
            DoonaMetricLatency(latency)
        }
    }
}

@Composable
private fun rateText(bytesPerSecond: Long): String =
    stringResource(R.string.act_rate, Formatter.formatShortFileSize(LocalContext.current, bytesPerSecond))

/**
 * The client's own control: the connection state, and the on/off switch at the end. Without a subscription there is
 * nothing to start from, so the switch is there only while the tunnel is up, to stop it; in any other state, a tunnel that
 * is stopping included, the card shows the state alone.
 */
@Composable
private fun ConnectionCard(status: Status, canStart: Boolean, onSwitch: (Boolean) -> Unit) {
    val on = status is Status.Running || status is Status.Starting
    val title = stringResource(status.label)
    val reason = (status as? Status.Failed)?.let { stringResource(it.reason.messageRes()) }
    DoonaCard(Modifier.fillMaxWidth()) {
        if (on || canStart) DoonaSwitchRow(title, on, onSwitch, subtitle = reason) else DoonaSettingsRow(title, subtitle = reason)
    }
}

private val Status.label: Int
    get() = when (this) {
        is Status.Running -> R.string.backend_connected
        Status.Starting -> R.string.status_starting
        Status.Stopping -> R.string.status_stopping
        Status.Stopped, is Status.Failed -> R.string.status_stopped
    }

/** A rate tile: the value and the last minute's series as a sparkline across the tile. */
@Composable
private fun RowScope.RateTile(@DrawableRes icon: Int, seriesColor: Color, caption: String, value: String, series: List<Float>) {
    DoonaMetricTile(icon, { DoonaTileCaption(caption) }, Modifier.weight(1f).fillMaxHeight()) {
        DoonaMetricValue(value)
        if (series.isNotEmpty()) DoonaSparkline(series, seriesColor, Modifier.fillMaxWidth())
    }
}
