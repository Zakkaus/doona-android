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
import dev.doona.android.designsystem.components.ControlCard
import dev.doona.android.designsystem.components.DoonaCard
import dev.doona.android.designsystem.components.DoonaErrorBanner
import dev.doona.android.designsystem.components.DoonaIcon
import dev.doona.android.designsystem.components.DoonaPage
import dev.doona.android.designsystem.components.DoonaSwitch
import dev.doona.android.designsystem.components.DoonaText
import dev.doona.android.designsystem.components.Gap
import dev.doona.android.designsystem.components.HGap
import dev.doona.android.designsystem.components.IconAction
import dev.doona.android.designsystem.components.Latency
import dev.doona.android.designsystem.components.NodeName
import dev.doona.android.designsystem.components.PageTabs
import dev.doona.android.designsystem.components.PageTitle
import dev.doona.android.designsystem.components.PickerButton
import dev.doona.android.designsystem.components.Segmented
import dev.doona.android.designsystem.components.Sparkline
import dev.doona.android.designsystem.components.StatusLight
import dev.doona.android.designsystem.components.Tone
import dev.doona.android.designsystem.theme.DoonaTheme
import dev.doona.android.engine.Engine
import dev.doona.android.engine.FailureReason
import dev.doona.android.engine.Mode
import dev.doona.android.engine.Status
import dev.doona.android.feature.messageRes

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
    val space = DoonaTheme.space
    DoonaPage(
        actions = {
            IconAction(R.drawable.ic_search, stringResource(R.string.ui_search))
            IconAction(R.drawable.ic_more_vertical, stringResource(R.string.ui_more_options))
        },
    ) {
        PageTitle(stringResource(R.string.hub_activity))
        Gap(space.s4)
        PageTabs(listOf(stringResource(R.string.hub_activity), stringResource(R.string.nav_overview)), selected = 0)
        Gap(space.s6)
        Column(verticalArrangement = Arrangement.spacedBy(space.s3)) {
            state.error?.let { code ->
                DoonaErrorBanner(stringResource(code.messageRes()), stringResource(R.string.ui_dismiss), { onAction(ActivityAction.DismissError) })
            }
            ConnectionCard(state.status, onSwitch = { if (it) onConnect() else onAction(ActivityAction.Disconnect) })
            ControlCard(R.drawable.ic_shuffle, DoonaTheme.colors.c3, stringResource(R.string.act_mode)) {
                Segmented(
                    Mode.entries.map { stringResource(it.label) },
                    selected = state.mode.ordinal,
                    onSelect = { onAction(ActivityAction.SetMode(Mode.entries[it])) },
                )
            }
            ControlCard(R.drawable.ic_filter, DoonaTheme.colors.c2, stringResource(R.string.act_global)) {
                PickerButton(state.outbound ?: NO_VALUE, onClick = { state.outboundGroupId?.let(onOpenGroup) })
            }
        }
        Gap(space.s6)
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
    Column(verticalArrangement = Arrangement.spacedBy(DoonaTheme.space.s3)) {
        TileRow {
            RateTile(R.drawable.ic_download, DoonaTheme.colors.c1, stringResource(R.string.ui_download), rateText(state.downRate), state.downHistory)
            RateTile(R.drawable.ic_upload, DoonaTheme.colors.c4, stringResource(R.string.ui_upload), rateText(state.upRate), state.upHistory)
        }
        // Direct traffic uses no node, so there is no latency to show.
        if (state.mode == Mode.Direct) return@Column
        val node = state.node
        val latency = node?.latencyMs?.takeUnless { node.failed }
        MetricTile(
            icon = R.drawable.ic_clock,
            tint = DoonaTheme.colors.c5,
            caption = { NodeName(node?.name ?: stringResource(R.string.act_latency), DoonaTheme.type.caption, color = DoonaTheme.colors.textSecondary) },
            modifier = Modifier.fillMaxWidth(),
            onClick = state.outboundGroupId?.let { id -> { onOpenGroup(id) } },
        ) {
            Latency(latency, DoonaTheme.type.tileValue)
        }
    }
}

@Composable
private fun rateText(bytesPerSecond: Long): String =
    stringResource(R.string.act_rate, Formatter.formatShortFileSize(LocalContext.current, bytesPerSecond))

/** The client's own control: the connection state, and the on/off switch at the end. */
@Composable
private fun ConnectionCard(status: Status, onSwitch: (Boolean) -> Unit) {
    val on = status is Status.Running || status is Status.Starting
    DoonaCard(Modifier.fillMaxWidth()) {
        Row(Modifier.heightIn(min = DoonaTheme.size.touch), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(DoonaTheme.space.s1)) {
                StatusLight(status.tone, stringResource(status.label), style = DoonaTheme.type.cardTitle)
                (status as? Status.Failed)?.let {
                    DoonaText(stringResource(it.reason.label), DoonaTheme.type.caption, color = DoonaTheme.colors.textSecondary)
                }
            }
            HGap(DoonaTheme.space.s3)
            DoonaSwitch(on, onSwitch, stringResource(R.string.login_submit))
        }
    }
}

private val Status.tone: Tone
    get() = when (this) {
        is Status.Running -> Tone.Ok
        Status.Starting, Status.Stopping -> Tone.Warn
        Status.Stopped -> Tone.Neutral
        is Status.Failed -> Tone.Err
    }

private val Status.label: Int
    get() = when (this) {
        is Status.Running -> R.string.backend_connected
        Status.Starting -> R.string.status_starting
        Status.Stopping -> R.string.status_stopping
        Status.Stopped, is Status.Failed -> R.string.status_stopped
    }

private val FailureReason.label: Int
    get() = when (this) {
        FailureReason.NoAllowedApps -> R.string.status_failed_no_allowed_apps
        FailureReason.StartFailed -> R.string.status_failed_start
        FailureReason.CoreStopped -> R.string.status_failed_core_stopped
        FailureReason.IncompatibleCore -> R.string.status_failed_incompatible_core
    }

@Composable
private fun TileRow(content: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(DoonaTheme.space.s3), content = content)
}

/** `.rp-tile-head`: 12/16 subtle caption. */
@Composable
private fun TileCaption(text: String) {
    DoonaText(text, style = DoonaTheme.type.caption, color = DoonaTheme.colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

/** A rate tile: the value and the last minute's series as a sparkline across the tile, 32 high. */
@Composable
private fun RowScope.RateTile(@DrawableRes icon: Int, tint: Color, caption: String, value: String, series: List<Float>) {
    MetricTile(icon, tint, { TileCaption(caption) }, Modifier.weight(1f).fillMaxHeight()) {
        DoonaText(value, style = DoonaTheme.type.tileValue, color = DoonaTheme.colors.text, maxLines = 1, softWrap = false)
        if (series.isNotEmpty()) Sparkline(series, tint, Modifier.fillMaxWidth().height(DoonaTheme.size.spark))
    }
}

/** Dashboard tile (§7): metric header (tinted icon and caption, control height), then the 22/28/700 value. */
@Composable
private fun MetricTile(
    @DrawableRes icon: Int,
    tint: Color,
    caption: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    value: @Composable () -> Unit,
) {
    // The whole tile is the touch target (the latency tile opens its group's page), so the header keeps doona's
    // control height.
    DoonaCard(modifier, onClick = onClick) {
        Row(Modifier.heightIn(min = DoonaTheme.size.control), verticalAlignment = Alignment.CenterVertically) {
            DoonaIcon(icon, tint)
            HGap(DoonaTheme.space.s2)
            caption()
        }
        value()
    }
}
