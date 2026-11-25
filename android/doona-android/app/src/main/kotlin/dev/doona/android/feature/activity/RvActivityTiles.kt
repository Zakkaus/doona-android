package dev.doona.android.feature.activity

import android.content.res.Configuration
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.withInfiniteAnimationFrameNanos
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import dev.doona.android.R
import dev.doona.android.designsystem.rv.LocalFrameInsets
import dev.doona.android.designsystem.rv.LocalRvCovered
import dev.doona.android.designsystem.rv.RV_GHOST_DOWN
import dev.doona.android.designsystem.rv.RV_GHOST_UP
import dev.doona.android.designsystem.rv.RV_NAV_ACTIVITY
import dev.doona.android.designsystem.rv.RV_NO_VALUE
import dev.doona.android.designsystem.rv.Rv
import dev.doona.android.designsystem.rv.RvAction
import dev.doona.android.designsystem.rv.RvAmount
import dev.doona.android.designsystem.rv.RvCard
import dev.doona.android.designsystem.rv.RvChevron
import dev.doona.android.designsystem.rv.RvChoiceLine
import dev.doona.android.designsystem.rv.RvDelta
import dev.doona.android.designsystem.rv.RvDotMotion
import dev.doona.android.designsystem.rv.RvDots
import dev.doona.android.designsystem.rv.RvEmptyState
import dev.doona.android.designsystem.rv.RvFadeThrough
import dev.doona.android.designsystem.rv.RvFlag
import dev.doona.android.designsystem.rv.RvFloatingNav
import dev.doona.android.designsystem.rv.RvGutter
import dev.doona.android.designsystem.rv.RvHeader
import dev.doona.android.designsystem.rv.RvHero
import dev.doona.android.designsystem.rv.RvHeroSwipe
import dev.doona.android.designsystem.rv.RvIconDisc
import dev.doona.android.designsystem.rv.RvInline
import dev.doona.android.designsystem.rv.RvLatencyChange
import dev.doona.android.designsystem.rv.RvMotion
import dev.doona.android.designsystem.rv.RvNavFade
import dev.doona.android.designsystem.rv.RvNotice
import dev.doona.android.designsystem.rv.RvPageEnd
import dev.doona.android.designsystem.rv.RvPageEndRoom
import dev.doona.android.designsystem.rv.RvPullLook
import dev.doona.android.designsystem.rv.RvRow
import dev.doona.android.designsystem.rv.RvSeeAll
import dev.doona.android.designsystem.rv.RvSeries
import dev.doona.android.designsystem.rv.RvSize
import dev.doona.android.designsystem.rv.RvSkeletonCard
import dev.doona.android.designsystem.rv.RvSkeletonCards
import dev.doona.android.designsystem.rv.RvSkeletonEnd
import dev.doona.android.designsystem.rv.RvSkeletonFade
import dev.doona.android.designsystem.rv.RvSkeletonHero
import dev.doona.android.designsystem.rv.RvSkeletonRow
import dev.doona.android.designsystem.rv.RvSkeletonSeeAll
import dev.doona.android.designsystem.rv.RvSkeletonTiles
import dev.doona.android.designsystem.rv.RvSpace
import dev.doona.android.designsystem.rv.RvSparkTile
import dev.doona.android.designsystem.rv.RvStatsDots
import dev.doona.android.designsystem.rv.RvText
import dev.doona.android.designsystem.rv.RvTheme
import dev.doona.android.designsystem.rv.RvTileRow
import dev.doona.android.designsystem.rv.RvTopBar
import dev.doona.android.designsystem.rv.figure
import dev.doona.android.designsystem.rv.rememberRvFormat
import dev.doona.android.designsystem.rv.rememberRvGlass
import dev.doona.android.designsystem.rv.rvActionRowFit
import dev.doona.android.designsystem.rv.rvGlassSource
import dev.doona.android.designsystem.rv.rvHeroArt
import dev.doona.android.designsystem.rv.rvMapPan
import dev.doona.android.designsystem.rv.rvNavMiddle
import dev.doona.android.designsystem.rv.rvPullBlur
import dev.doona.android.designsystem.rv.rvShade
import dev.doona.android.designsystem.rv.rvSystemScheme
import dev.doona.android.engine.FailureReason
import dev.doona.android.engine.Status
import dev.doona.android.feature.model.RvGroupKind
import dev.doona.android.feature.model.RvNode
import dev.doona.android.feature.model.RvRouteMode
import dev.doona.android.feature.model.RvSubscription
import dev.doona.android.feature.model.fastest
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.collectLatest

/**
 * 活動's cards while it loads, each part on the lines of what replaces it: the outbound card's rows (出站模式 over the
 * [mode] and its chevron's room; the main [node] over its role and its latency and change; the rule groups, which today's
 * traffic picks, over their bytes) and its see-all line, then the two traffic tiles under their captions. The words the
 * page knows before its data (the mode, the node in use) take their lines, so a name that wraps keeps its row's height.
 */
@Composable
internal fun ActivitySkeleton(mode: RvRouteMode, node: String) {
    RvSkeletonCard {
        RvSkeletonRow(0, chevron = true, title = stringResource(R.string.act_mode), subtitleText = stringResource(mode.label))
        val role = stringResource(if (mode == RvRouteMode.Rule) R.string.rv_main_node else R.string.nav_nodes)
        RvSkeletonRow(1, end = RvSkeletonEnd.Latency, title = node, subtitleText = role, titleKeepWords = false)
        repeat(RULE_ROWS) { RvSkeletonRow(it + 2, end = RvSkeletonEnd.Value) }
        RvSkeletonSeeAll()
    }
    RvSkeletonTiles(listOf(stringResource(R.string.ui_download), stringResource(R.string.ui_upload)))
}

/**
 * Download and upload as two tiles side by side, each on its own scale: the rate, and the last minute's sparkline with
 * that minute's peak, so the label and the curve beside it cover the same window (S01; 流量詳情 marks the same peak on
 * the same minute). The session's peak is shown only where it is labelled 本次. Disconnected, or with no traffic in the
 * window, both tiles say so over ghost bars instead.
 */
@Composable
internal fun TrafficTiles(state: RvActivityState, onOpenTraffic: () -> Unit) {
    val quiet = state.status !is Status.Running || (state.downHistory.all { it == 0f } && state.upHistory.all { it == 0f })
    RvTileRow {
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
    val note = if (quiet) stringResource(R.string.rv_no_traffic) else stringResource(R.string.rv_peak, format.rate(peak.toDouble()).unbroken())
    val widest = remember(format) { format.widestRates().map { it.figure() } }
    RvSparkTile(stringResource(caption), icon, format.rate(rate.toDouble()).figure(), note, history, series, quiet, onClick, modifier, ghost, widest)
}

/** The fastest second of a minute's samples; a missing sample (NaN, a gap in the line) is not a rate. */
private fun minutePeak(history: List<Float>): Float = history.filterNot { it.isNaN() }.maxOrNull() ?: 0f
