package dev.doona.android.prototype.rv

import android.content.Context
import android.content.res.Configuration
import android.text.format.Formatter
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import dev.doona.android.R
import dev.doona.android.designsystem.components.Tone
import dev.doona.android.designsystem.components.latencyTone
import dev.doona.android.designsystem.theme.LocalFrameInsets
import dev.doona.android.engine.Mode
import dev.doona.android.engine.Status
import dev.doona.android.feature.NOT_WIRED
import dev.doona.android.feature.activity.ActivityAction
import dev.doona.android.feature.activity.ActivityUiState
import dev.doona.android.feature.activity.sampleActivityState
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

/**
 * The 活動 screen in Revolut's home structure (#11, #15): a tinted header with the connection state, the download rate
 * as the hero figure and the upload beside it, four round actions, then a grouped card with the global outbound and the
 * node it uses, and the traffic chart. It renders [ActivityUiState] unchanged.
 */
@Composable
internal fun RvActivityScreen(state: ActivityUiState, onAction: (ActivityAction) -> Unit, onConnect: () -> Unit, onOpenGroup: (String) -> Unit) {
    val insets = LocalFrameInsets.current ?: WindowInsets.systemBars
    Column(Modifier.fillMaxSize().background(Rv.colors.page).verticalScroll(rememberScrollState())) {
        Header(state, onAction, onConnect, insets)
        RvGutter {
            OutboundCard(state, onOpenGroup)
            TrafficCard(state)
        }
        Spacer(Modifier.height(RvSpace.cardGap))
        Spacer(Modifier.windowInsetsBottomHeight(insets))
    }
}

@Composable
private fun Header(state: ActivityUiState, onAction: (ActivityAction) -> Unit, onConnect: () -> Unit, insets: WindowInsets) {
    val context = LocalContext.current
    val running = state.status is Status.Running || state.status is Status.Starting
    val (whole, unit) = rateParts(context, state.downRate)
    // The tint runs up behind the status bar, as Revolut's header does.
    RvHeader {
        Spacer(Modifier.windowInsetsTopHeight(insets))
        Spacer(Modifier.height(RvSpace.headerTop))
        RvText("${stringResource(state.status.label)} · ${stringResource(state.mode.label)}", Rv.type.caption)
        Spacer(Modifier.height(RvSpace.tight))
        RvHero(whole, unit)
        Spacer(Modifier.height(RvSpace.subGap))
        Row(horizontalArrangement = Arrangement.spacedBy(RvSpace.rowPadding)) {
            RvInline(R.drawable.ic_download, stringResource(R.string.ui_download), Rv.colors.textSecondary, Rv.type.sub)
            RvInline(R.drawable.ic_upload, rateText(context, state.upRate), Rv.colors.textSecondary, Rv.type.sub)
        }
        Spacer(Modifier.height(RvSpace.heroToActions))
        Row(Modifier.fillMaxWidth().padding(horizontal = RvSpace.gutter)) {
            val other = if (state.mode == Mode.Global) Mode.Direct else Mode.Global
            val toggle = if (running) ({ onAction(ActivityAction.Disconnect) }) else onConnect
            RvAction(R.drawable.ic_link, stringResource(if (running) R.string.rv_disconnect else R.string.rv_connect), toggle, Modifier.weight(1f))
            RvAction(R.drawable.ic_shuffle, stringResource(R.string.act_mode), { onAction(ActivityAction.SetMode(other)) }, Modifier.weight(1f))
            RvAction(R.drawable.ic_speed_fast, stringResource(R.string.rv_test_latency), NOT_WIRED, Modifier.weight(1f))
            RvAction(R.drawable.ic_more_vertical, stringResource(R.string.rv_more), NOT_WIRED, Modifier.weight(1f), iconRotation = QUARTER_TURN)
        }
        Spacer(Modifier.height(RvSpace.actionsToCard))
    }
}

@Composable
private fun OutboundCard(state: ActivityUiState, onOpenGroup: (String) -> Unit) {
    RvCard {
        RvRow(
            R.drawable.ic_filter,
            stringResource(R.string.act_global),
            state.outbound ?: NO_VALUE,
            onClick = state.outboundGroupId?.let { id -> { onOpenGroup(id) } },
        ) { RvGlyph(R.drawable.ic_chevron_right, RvSize.chevron, Rv.colors.textSecondary) }
        // Direct traffic uses no node, so there is no node or latency to show.
        if (state.mode == Mode.Direct) return@RvCard
        val node = state.node
        val latency = node?.latencyMs?.takeUnless { node.failed }
        val tone = latencyTone(latency)
        RvRow(R.drawable.ic_clock, node?.name ?: NO_VALUE, stringResource(R.string.nav_nodes)) {
            RvTrailingValue(
                latency?.let { stringResource(R.string.latency_ms, it) } ?: NO_VALUE,
                stringResource(tone.quality),
                if (tone == Tone.Err) {
                    Rv.colors.negative
                } else if (tone == Tone.Ok) {
                    Rv.colors.positive
                } else {
                    Rv.colors.notice
                },
            )
        }
    }
}

@Composable
private fun TrafficCard(state: ActivityUiState) {
    val context = LocalContext.current
    RvCard {
        Column(Modifier.padding(RvSpace.rowPadding), verticalArrangement = Arrangement.spacedBy(RvSpace.legendGap)) {
            Row(Modifier.fillMaxWidth()) {
                Legend(stringResource(R.string.ui_download), rateText(context, state.downRate), Rv.colors.seriesDown, Modifier.weight(1f))
                Legend(stringResource(R.string.ui_upload), rateText(context, state.upRate), Rv.colors.seriesUp, Modifier.weight(1f))
            }
            val top = niceMax(maxOf(state.downHistory.maxOrNull() ?: 0f, state.upHistory.maxOrNull() ?: 0f))
            RvTrafficChart(
                state.downHistory,
                state.upHistory,
                top,
                axis = { if (it == 0f) ZERO else Formatter.formatShortFileSize(context, it.toLong()) },
                start = stringResource(R.string.rv_seconds_ago, state.downHistory.size),
                end = stringResource(R.string.rv_now),
            )
        }
    }
}

@Composable
private fun Legend(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(RvSpace.tight / 2)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(RvSpace.subGap)) {
            Box(Modifier.size(RvSize.dot).clip(CircleShape).background(color))
            RvText(label, Rv.type.rowSub, color = Rv.colors.textSecondary)
        }
        RvText(value, Rv.type.rowValue)
    }
}

/** A rate split for the hero: the figure, and its unit with "/s" set small after it. */
private fun rateParts(context: Context, bytesPerSecond: Long): Pair<String, String> {
    val size = Formatter.formatShortFileSize(context, bytesPerSecond)
    val cut = size.lastIndexOf(' ')
    return if (cut < 0) size to PER_SECOND else size.substring(0, cut) to size.substring(cut) + PER_SECOND
}

private fun rateText(context: Context, bytesPerSecond: Long) = Formatter.formatShortFileSize(context, bytesPerSecond) + PER_SECOND

/** The smallest 1, 2, 2.5 or 5 times a power of ten at or above [value], so the axis reads in round steps. */
private fun niceMax(value: Float): Float {
    if (value <= 0f) return 1f
    val power = 10f.pow(floor(log10(value)))
    return NICE_STEPS.first { it * power >= value } * power
}

private val NICE_STEPS = listOf(1f, 2f, 2.5f, 5f, 10f)
private const val NO_VALUE = "—"
private const val ZERO = "0"
private const val PER_SECOND = "/s"
private const val QUARTER_TURN = 90f

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

private val Tone.quality: Int
    get() = when (this) {
        Tone.Ok -> R.string.latency_good
        Tone.Warn -> R.string.latency_fair
        Tone.Err -> R.string.latency_slow
        Tone.Neutral -> R.string.latency_untested
    }

@Preview(name = "Light", locale = "zh-rTW", widthDp = 412, heightDp = 915)
@Preview(name = "Dark", locale = "zh-rTW", widthDp = 412, heightDp = 915, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun RvActivityScreenPreview() {
    val dark = isSystemInDarkTheme()
    RvTheme(rvSystemScheme(dark), dark) { RvActivityScreen(sampleActivityState(), onAction = {}, onConnect = {}, onOpenGroup = {}) }
}
