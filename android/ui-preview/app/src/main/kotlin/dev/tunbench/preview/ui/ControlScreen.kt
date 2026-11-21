package dev.tunbench.preview.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import dev.tunbench.preview.R
import dev.tunbench.preview.model.CHART_MAX_GBPS
import dev.tunbench.preview.model.ControlState
import dev.tunbench.preview.model.Engine
import dev.tunbench.preview.theme.CHART_FILL_ALPHA
import dev.tunbench.preview.theme.HomeDims
import dev.tunbench.preview.theme.HomeShapes
import dev.tunbench.preview.theme.HomeType

/**
 * The Control screen as Google Home's home tab with our data: the engine chips, the live chart where the
 * camera is, the engine's control card where the thermostat is, and neutral tiles for the measures.
 * Every colour is a scheme role. The one tinted block is the control card while the engine runs
 * (primaryContainer); the chips already show the engine, so Home's device-tile row is left out.
 */
@Composable
fun ControlScreen(state: ControlState, onOpenSettings: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(top = HomeDims.chipRowTop, bottom = HomeDims.margin),
        verticalArrangement = Arrangement.spacedBy(HomeDims.gap),
    ) {
        Row(
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = HomeDims.margin)
                .padding(bottom = HomeDims.chipRowBottom),
            horizontalArrangement = Arrangement.spacedBy(HomeDims.gap),
        ) {
            Engine.entries.forEach { EngineChip(state, it) }
        }
        val cards = Modifier.padding(horizontal = HomeDims.margin)
        LiveCard(state, cards)
        ControlCard(state, onOpenSettings, cards)
        MeasureTiles(state, cards)
    }
}

/** Home's chip: a 56 dp pill, selected on secondaryContainer with a filled icon, others on surfaceContainer. */
@Composable
private fun EngineChip(state: ControlState, engine: Engine) {
    val colors = MaterialTheme.colorScheme
    val selected = state.engine == engine
    Surface(
        selected = selected,
        onClick = { state.engine = engine },
        shape = HomeShapes.pill,
        color = if (selected) colors.secondaryContainer else colors.surfaceContainer,
        contentColor = if (selected) colors.onSecondaryContainer else colors.onSurface,
        modifier = Modifier.height(HomeDims.chipHeight).semantics { role = Role.RadioButton },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = HomeDims.chipPadding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(if (selected) R.drawable.speed_fill1_24px else R.drawable.speed_24px),
                contentDescription = null,
                modifier = Modifier.size(HomeDims.chipIcon),
            )
            Spacer(Modifier.width(HomeDims.chipIconGap))
            Text(stringResource(engine.label), style = HomeType.chip)
        }
    }
}

/**
 * The camera card's place, filled edge to edge like Home's camera picture: the live throughput as an
 * area from zero under its line (primary, its fill at the dragged state-layer opacity), with 「即時」 and
 * the current value on top. The chart's full scale
 * reaches the bottom of the label band, so the line never runs through the labels.
 */
@Composable
private fun LiveCard(state: ControlState, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val maxY = CHART_MAX_GBPS * (HomeDims.liveHeight / (HomeDims.liveHeight - HomeDims.liveHeadroom))
    Surface(shape = HomeShapes.card, color = colors.surfaceContainer, modifier = modifier.fillMaxWidth().height(HomeDims.liveHeight)) {
        Box {
            ThroughputArea(
                points = state.series,
                maxY = maxY,
                lineColor = colors.primary,
                fillColor = colors.primary.copy(alpha = CHART_FILL_ALPHA),
                modifier = Modifier.fillMaxSize(),
            )
            Row(
                modifier = Modifier.padding(start = HomeDims.liveInset, end = HomeDims.liveInset, top = HomeDims.liveTop),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(HomeDims.liveDot).clip(HomeShapes.pill).background(if (state.running) colors.primary else colors.onSurfaceVariant))
                Spacer(Modifier.width(HomeDims.liveDotGap))
                Text(stringResource(R.string.label_live), style = HomeType.liveLabel, color = colors.onSurface, modifier = Modifier.weight(1f))
                FigureText(
                    number = state.figures.gbps.takeIf { state.running }?.let(::twoDecimals),
                    unit = stringResource(R.string.unit_gbps),
                    style = HomeType.liveLabel,
                    unitStyle = HomeType.liveLabel,
                    color = colors.onSurface,
                    unitColor = colors.onSurface,
                )
            }
        }
    }
}

/**
 * The thermostat card's place: the engine as header, its throughput as the figure, and one start/stop
 * pill. Running, it is the screen's one tinted block (primaryContainer); stopped, it is neutral. Home's thermostat has two step pills around a centred figure; with one control the figure
 * block sits at the bottom start as in Home's 「Upcoming」 cards and the pill at the row's end as in
 * Home's automation rows (home-2), so neither side is empty.
 */
@Composable
private fun ControlCard(state: ControlState, onOpenSettings: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val running = state.running
    val container = if (running) colors.primaryContainer else colors.surfaceContainer
    val text = if (running) colors.onPrimaryContainer else colors.onSurface
    Surface(shape = HomeShapes.card, color = container, contentColor = text, modifier = modifier.fillMaxWidth().height(HomeDims.controlHeight)) {
        Column(Modifier.padding(HomeDims.controlPadding)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(painter = painterResource(R.drawable.speed_24px), contentDescription = null)
                Spacer(Modifier.width(HomeDims.tileIconGap))
                Text(stringResource(state.engine.label), style = HomeType.cardHeader, modifier = Modifier.weight(1f))
                IconButton(onClick = onOpenSettings) {
                    Icon(
                        painter = painterResource(R.drawable.chevron_right_24px),
                        contentDescription = stringResource(R.string.action_settings),
                        tint = text,
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    FigureText(
                        number = state.figures.gbps.takeIf { running }?.let(::twoDecimals),
                        unit = null,
                        style = HomeType.figure,
                        unitStyle = HomeType.figure,
                        color = text,
                    )
                    Text(
                        text = if (running) {
                            stringResource(
                                R.string.joined,
                                stringResource(R.string.joined, stringResource(R.string.unit_gbps), stringResource(R.string.workload_bulk_down)),
                                clock(state.uptimeSeconds),
                            )
                        } else {
                            stringResource(R.string.status_stopped)
                        },
                        style = HomeType.figureCaption,
                        maxLines = 1,
                    )
                }
                StartStopPill(running = running, onClick = state::toggle)
            }
        }
    }
}

/** The thermostat's step pill, 70 × 56, carrying start or stop; primary, the screen's one action. */
@Composable
private fun StartStopPill(running: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = HomeShapes.pill,
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        modifier = Modifier.width(HomeDims.pillWidth).height(HomeDims.pillHeight),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                painter = painterResource(if (running) R.drawable.stop_fill1_24px else R.drawable.play_arrow_fill1_24px),
                contentDescription = stringResource(if (running) R.string.action_stop else R.string.action_start),
            )
        }
    }
}

@Composable
private fun MeasureTiles(state: ControlState, modifier: Modifier = Modifier) {
    val figures = state.figures.takeIf { state.running }
    val none = stringResource(R.string.value_none)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(HomeDims.gap)) {
        Row(horizontalArrangement = Arrangement.spacedBy(HomeDims.gap)) {
            NeutralTile(
                R.drawable.memory_24px,
                stringResource(R.string.metric_cpu),
                figures?.cpuPercent?.let { stringResource(R.string.value_percent, it) } ?: none,
                Modifier.weight(1f),
            )
            NeutralTile(
                R.drawable.alarm_24px,
                stringResource(R.string.metric_wakeups),
                figures?.wakeupsPerSecond?.let { stringResource(R.string.value_wakeups, thousands(it)) } ?: none,
                Modifier.weight(1f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(HomeDims.gap)) {
            NeutralTile(
                R.drawable.bolt_24px,
                stringResource(R.string.metric_energy),
                state.figures.joulesPerGb?.let { stringResource(R.string.value_energy, twoDecimals(it)) } ?: none,
                Modifier.weight(1f),
            )
            NeutralTile(
                R.drawable.link_24px,
                stringResource(R.string.metric_connections),
                figures?.connections?.toString() ?: none,
                Modifier.weight(1f),
            )
        }
    }
}

/** Home's neutral tile (weather, air quality): outlined icon and two lines on surfaceContainerLow. */
@Composable
private fun NeutralTile(@DrawableRes icon: Int, title: String, value: String, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Surface(shape = HomeShapes.card, color = colors.surfaceContainerLow, contentColor = colors.onSurface, modifier = modifier.height(HomeDims.neutralHeight)) {
        Row(modifier = Modifier.padding(horizontal = HomeDims.tilePadding), verticalAlignment = Alignment.CenterVertically) {
            Icon(painter = painterResource(icon), contentDescription = null, modifier = Modifier.size(HomeDims.tileIcon))
            Spacer(Modifier.width(HomeDims.tileIconGap))
            Column {
                Text(title, style = HomeType.neutral, maxLines = 1)
                Text(value, style = HomeType.neutral, maxLines = 1)
            }
        }
    }
}
