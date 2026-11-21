package dev.doona.android.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import dev.doona.android.R
import dev.doona.android.model.Activity
import dev.doona.android.model.Tone
import dev.doona.android.model.latencyTone
import dev.doona.android.theme.DoonaTheme

/**
 * 活動 (doona `src/features/activity`): the connection card this client adds, doona's quick controls (outbound mode,
 * global outbound), then the metric tiles two per row and CPU across both columns.
 */
@Composable
fun ActivityScreen(state: Activity, onHub: (Hub) -> Unit) {
    val space = DoonaTheme.space
    HubPage(
        hub = Hub.Activity,
        onHub = onHub,
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
            ConnectionCard(state)
            ModeCard(state)
            ControlCard(R.drawable.ic_filter, DoonaTheme.colors.c2, stringResource(R.string.act_global)) {
                PickerButton(state.globalOutbound, onClick = {})
            }
        }
        Gap(space.s6)
        Column(verticalArrangement = Arrangement.spacedBy(space.s3)) {
            TileRow {
                MetricTile(R.drawable.ic_download, DoonaTheme.colors.c1, { TileCaption(stringResource(R.string.ui_download)) }, state.downloadRate, state.download)
                MetricTile(R.drawable.ic_upload, DoonaTheme.colors.c4, { TileCaption(stringResource(R.string.ui_upload)) }, state.uploadRate, state.upload)
            }
            TileRow {
                MetricTile(R.drawable.ic_link, DoonaTheme.colors.c3, { TileCaption(stringResource(R.string.act_active)) }, state.connections.toString(), state.connectionHistory)
                MetricTile(
                    icon = R.drawable.ic_clock,
                    tint = DoonaTheme.colors.c5,
                    caption = { NodeName(state.latencyNode, DoonaTheme.type.caption, DoonaTheme.colors.textSecondary) },
                    value = "${state.latencyNode.latencyMs} ms",
                    series = state.latencyHistory,
                    tone = latencyTone(state.latencyNode.latencyMs),
                )
            }
            MetricTile(R.drawable.ic_cpu, DoonaTheme.colors.c2, { TileCaption(stringResource(R.string.act_cpu)) }, state.cpu, state.cpuHistory, modifier = Modifier.fillMaxWidth())
        }
    }
}

/** The client's own control: profile name over the connection state, and the on/off switch at the end. */
@Composable
private fun ConnectionCard(state: Activity) {
    var on by remember { mutableStateOf(state.connected) }
    val c = DoonaTheme.colors
    DoonaCard(Modifier.fillMaxWidth()) {
        Row(Modifier.heightIn(min = DoonaTheme.size.touch), verticalAlignment = Alignment.CenterVertically) {
            Column(
                Modifier
                    .weight(1f)
                    .heightIn(min = DoonaTheme.size.touch)
                    .clickable(role = Role.Button, onClickLabel = stringResource(R.string.settings_profile)) {},
                verticalArrangement = Arrangement.spacedBy(DoonaTheme.space.s1, Alignment.CenterVertically),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(state.profile, style = DoonaTheme.type.cardTitle, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    HGap(DoonaTheme.space.s2)
                    DoonaIcon(R.drawable.ic_chevron_down, c.text, size = DoonaTheme.size.iconInline)
                }
                StatusLight(if (on) Tone.Ok else Tone.Neutral, stringResource(R.string.backend_connected))
            }
            HGap(DoonaTheme.space.s3)
            DoonaSwitch(on, { on = it }, stringResource(R.string.login_submit))
        }
    }
}

@Composable
private fun ModeCard(state: Activity) {
    var mode by remember { mutableIntStateOf(state.mode.ordinal) }
    ControlCard(R.drawable.ic_shuffle, DoonaTheme.colors.c3, stringResource(R.string.act_mode)) {
        Segmented(
            listOf(stringResource(R.string.mode_rule), stringResource(R.string.mode_direct), stringResource(R.string.mode_global)),
            selected = mode,
            onSelect = { mode = it },
        )
    }
}

@Composable
private fun TileRow(content: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(DoonaTheme.space.s3), content = content)
}

/** `.rp-tile-head`: 12/16 subtle caption. */
@Composable
private fun TileCaption(text: String) {
    Text(text, style = DoonaTheme.type.caption, color = DoonaTheme.colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

/**
 * Dashboard tile (§7): metric header (tinted icon and caption, control height), the 22/28/700 value, and the series
 * as a sparkline across the tile, 32 high. A latency value carries its tone dot and text colour (§2).
 */
@Composable
private fun RowScope.MetricTile(
    @DrawableRes icon: Int,
    tint: Color,
    caption: @Composable () -> Unit,
    value: String,
    series: List<Float>,
    tone: Tone? = null,
) = MetricTile(icon, tint, caption, value, series, tone, Modifier.weight(1f).fillMaxHeight())

@Composable
private fun MetricTile(
    @DrawableRes icon: Int,
    tint: Color,
    caption: @Composable () -> Unit,
    value: String,
    series: List<Float>,
    tone: Tone? = null,
    modifier: Modifier = Modifier,
) {
    val c = DoonaTheme.colors
    val valueStyle: TextStyle = DoonaTheme.type.tileValue.copy(fontFeatureSettings = DoonaTheme.type.tabular)
    // The whole tile is the touch target (the latency tile opens its node picker), so the header keeps doona's
    // control height.
    DoonaCard(modifier, onClick = {}) {
        Row(Modifier.heightIn(min = DoonaTheme.size.control), verticalAlignment = Alignment.CenterVertically) {
            DoonaIcon(icon, tint)
            HGap(DoonaTheme.space.s2)
            caption()
        }
        if (tone != null) {
            StatusLight(
                tone,
                value,
                style = valueStyle,
                color = when (tone) {
                    Tone.Ok -> c.positiveText
                    Tone.Warn -> c.noticeText
                    Tone.Err -> c.negativeText
                    Tone.Neutral -> c.textSecondary
                },
                small = true,
                gap = DoonaTheme.space.s1h,
            )
        } else {
            Text(value, style = valueStyle, color = c.text, maxLines = 1, softWrap = false)
        }
        Sparkline(series, tint, Modifier.fillMaxWidth().height(DoonaTheme.size.spark))
    }
}
