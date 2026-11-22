package dev.doona.android.prototype.rv

import android.content.res.Configuration
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import dev.doona.android.R
import dev.doona.android.designsystem.theme.LocalFrameInsets
import dev.doona.android.engine.FailureReason
import dev.doona.android.engine.Mode
import dev.doona.android.engine.Status
import dev.doona.android.feature.NOT_WIRED
import dev.doona.android.feature.activity.ActivityAction
import dev.doona.android.feature.activity.ActivityUiState
import dev.doona.android.prototype.rv.data.RvAmount
import dev.doona.android.prototype.rv.data.RvData
import dev.doona.android.prototype.rv.data.RvNode
import dev.doona.android.prototype.rv.data.RvPlace
import dev.doona.android.prototype.rv.data.RvRouteMode
import dev.doona.android.prototype.rv.data.RvRuleOutbound
import dev.doona.android.prototype.rv.data.RvSubscription
import dev.doona.android.prototype.rv.data.fastest
import kotlinx.coroutines.launch

/**
 * The 活動 screen in Revolut's home structure (#11, #15): a header with the connection state, the download rate as the
 * hero figure and the upload rate under it on a dot map of the world that marks the node's country (#07, #08), four round
 * actions, then the outbound card (item 24: the mode, the main node and the rule groups that sent traffic today),
 * download and upload as two of Revolut's square home tiles (either opens the traffic page), and three more cards as
 * Revolut's home goes on: 今日用量, 訂閱 and 最快節點. Over the content sit the glass top bar (measured §4) and the
 * floating glass bottom bar (§9), which shrinks while the content scrolls up. It renders [ActivityUiState]; the engine
 * has no rule mode yet, so [mode] comes from the sample, and the lower cards read [RvData].
 */
@Composable
internal fun RvActivityScreen(
    state: ActivityUiState,
    onAction: (ActivityAction) -> Unit,
    onConnect: () -> Unit,
    routes: RvActivityRoutes = RvActivityRoutes(),
    mode: RvRouteMode = if (state.mode == Mode.Direct) RvRouteMode.Direct else RvRouteMode.Rule,
    nodes: List<RvNode> = RvData.nodes,
    subscriptions: List<RvSubscription> = RvData.subscriptions,
    pull: RvPullLook = RvPullLook.None,
) {
    val insets = LocalFrameInsets.current ?: WindowInsets.systemBars
    val scroll = rememberScrollState()
    val glass = rememberRvGlass()
    Box(Modifier.fillMaxSize()) {
        // The pull blurs and fades the page; the bars stay sharp, so the search pill, the picker's anchor, can grow.
        Column(Modifier.fillMaxSize().rvPullBlur(pull.progress).rvGlassSource(glass).background(Rv.colors.page).verticalScroll(scroll)) {
            // The map stretches with the pull, and what lies under it moves down as far.
            val pushed = pull.stretch?.let { stretch -> Modifier.graphicsLayer { translationY = stretch() } }
            Header(state, mode, HeaderMotion(pull.stretch, pushed), insets) { HeroActions(state, onAction, onConnect, routes.test) }
            RvGutter(pushed ?: Modifier) {
                OutboundCard(state, mode, routes, nodes)
                TrafficTiles(state, routes.traffic)
                RvUsageCard(RvData.today, RvData.nowHour, RvData.YESTERDAY_BY_NOW)
                RvSubscriptionsCard(subscriptions)
                RvFastestCard(nodes.fastest(FASTEST), state.node?.id ?: RvData.CURRENT_ID, routes.nodes)
            }
            Spacer(Modifier.height(rvNavClearance(insets)))
        }
        RvTopBar(glass, insets, rvShade(scroll), onSearch = routes.nodes, onStats = routes.stats, Modifier.align(Alignment.TopCenter), pull)
        RvFloatingNav(RV_NAV_ACTIVITY, routes.tab, glass, rvCollapsedOnScroll(scroll), insets, Modifier.align(Alignment.BottomCenter))
    }
}

/**
 * Where 活動 leads: a traffic tile, 統計, 節點 (the search pill), the node row, the nav's tabs, the mode picker, a rule
 * row (by its group) and the full list of rules.
 */
internal class RvActivityRoutes(
    val traffic: () -> Unit = {},
    val stats: () -> Unit = {},
    val nodes: () -> Unit = {},
    val node: () -> Unit = {},
    val tab: (Int) -> Unit = {},
    val mode: () -> Unit = {},
    val rule: (Int) -> Unit = {},
    val rules: () -> Unit = {},
    val test: () -> Unit = NOT_WIRED,
)

/** The pull: [stretch] lengthens the map, [pushed] moves the header's text and everything under it down as far. */
private class HeaderMotion(val stretch: (() -> Float)?, val pushed: Modifier?)

@Composable
private fun Header(state: ActivityUiState, mode: RvRouteMode, motion: HeaderMotion, insets: WindowInsets, actions: @Composable () -> Unit) {
    val running = state.status is Status.Running || state.status is Status.Starting
    // Direct traffic uses no node, so the map marks no place.
    val place = state.node?.takeIf { mode != RvRouteMode.Direct }?.let { RvData.node(it.id)?.place }
    val statusBar = with(LocalDensity.current) { insets.getTop(this).toDp() }
    var heroBounds by remember { mutableStateOf<Rect?>(null) }
    val pan = rvMapPan(place)
    val live = RvDotMotion(rvHeroArt(), motion.stretch ?: { 0f }, rvPulse(state.status is Status.Running && place != null), pan?.first, pan?.second)
    // The dots run up behind the status bar, as Revolut's texture does, in the accent only while connected.
    RvHeader(place, statusBar + RvDots.focusTop, statusBar, if (running) Rv.colors.dots else Rv.colors.dotsIdle, heroBounds, live = live) {
        Spacer(Modifier.windowInsetsTopHeight(insets))
        Spacer(Modifier.height(RvSpace.headerTop))
        HeroFigure(state, mode, Modifier.onPlaced { heroBounds = it.boundsInParent() }.then(motion.pushed ?: Modifier))
        Spacer(Modifier.height(RvSpace.heroToActions))
        if (motion.pushed == null) actions() else Box(motion.pushed) { actions() }
        Spacer(Modifier.height(RvSpace.actionsToCard))
    }
}

/** The state and the mode over the download rate, the upload rate under it; a new state or each second's rates cross-fade in. */
@Composable
private fun HeroFigure(state: ActivityUiState, mode: RvRouteMode, modifier: Modifier) {
    val format = rememberRvFormat()
    val fade = Rv.motion.tween<Float>(RvMotion.FADE_MS)
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Crossfade("${stringResource(state.status.label)} · ${stringResource(mode.label)}", animationSpec = fade, label = "caption") { RvText(it, Rv.type.caption) }
        Spacer(Modifier.height(RvSpace.tight))
        val down = format.rate(state.downRate.toDouble())
        Crossfade(down.number to down.unit, animationSpec = fade, label = "rate") { (number, unit) -> RvHero(RvAmount(number, unit)) }
        Spacer(Modifier.height(RvSpace.subGap))
        // The hero is the download rate, so the line under it carries the other direction only (N-10).
        Crossfade(format.rate(state.upRate.toDouble()).toString(), animationSpec = fade, label = "up") { RvInline(R.drawable.rv_sym_arrow_upward, it, Rv.colors.textSecondary, Rv.type.sub) }
    }
}

/** Connect or disconnect (busy while either is under way), the outbound mode, a latency test ([onTest]) and more. */
@Composable
private fun HeroActions(state: ActivityUiState, onAction: (ActivityAction) -> Unit, onConnect: () -> Unit, onTest: () -> Unit) {
    val running = state.status is Status.Running || state.status is Status.Starting
    Row(Modifier.fillMaxWidth().padding(horizontal = RvSpace.gutter)) {
        val other = if (state.mode == Mode.Global) Mode.Direct else Mode.Global
        val toggle = if (running) ({ onAction(ActivityAction.Disconnect) }) else onConnect
        val busy = state.status == Status.Starting || state.status == Status.Stopping
        RvAction(R.drawable.rv_sym_power, stringResource(if (running) R.string.rv_disconnect else R.string.rv_connect), toggle, Modifier.weight(1f), busy = busy)
        RvAction(R.drawable.rv_sym_directions, stringResource(R.string.act_mode), { onAction(ActivityAction.SetMode(other)) }, Modifier.weight(1f))
        RvAction(R.drawable.rv_sym_speed, stringResource(R.string.rv_test_latency), onTest, Modifier.weight(1f))
        RvAction(R.drawable.rv_sym_more_horiz, stringResource(R.string.rv_more), NOT_WIRED, Modifier.weight(1f))
    }
    ConnectionTicks(state.status)
}

/** One haptic tick when [status] becomes connected, one when a disconnect ends; none on the first look or a failure. */
@Composable
private fun ConnectionTicks(status: Status) {
    val haptic = LocalHapticFeedback.current
    val last = remember { arrayOf(status) }
    LaunchedEffect(status) {
        val before = last[0]
        last[0] = status
        if (status is Status.Running && before !is Status.Running) haptic.performHapticFeedback(HapticFeedbackType.ToggleOn)
        if (status == Status.Stopped && before == Status.Stopping) haptic.performHapticFeedback(HapticFeedbackType.ToggleOff)
    }
}

/** Where the map is centred while it pans to [place] after a node switch, over [RvMotion.MOVE_MS] the short way round. */
@Composable
private fun rvMapPan(place: RvPlace?): Pair<() -> Float, () -> Float>? {
    val motion = Rv.motion
    val lat = remember { Animatable(place?.lat ?: 0f) }
    val lon = remember { Animatable(place?.lon ?: 0f) }
    LaunchedEffect(place) {
        place ?: return@LaunchedEffect
        var to = place.lon
        while (to - lon.value > HALF_TURN) to -= FULL_TURN
        while (to - lon.value < -HALF_TURN) to += FULL_TURN
        launch { lat.animateTo(place.lat, motion.tween(RvMotion.MOVE_MS)) }
        lon.animateTo(to, motion.tween(RvMotion.MOVE_MS))
    }
    if (place == null) return null
    return { lat.value } to {
        val at = lon.value
        if (at < -HALF_TURN) {
            at + FULL_TURN
        } else if (at >= HALF_TURN) {
            at - FULL_TURN
        } else {
            at
        }
    }
}

/** The marker's pulse while [on] (connected, with a place to mark): 0 to 1 each [RvMotion.PULSE_MS]; 0 off or without motion. */
@Composable
private fun rvPulse(on: Boolean): () -> Float {
    if (!on || Rv.motion.instant) return { 0f }
    val phase = rememberInfiniteTransition(label = "pulse").animateFloat(0f, 1f, infiniteRepeatable(tween(RvMotion.PULSE_MS, easing = RvMotion.Decelerate), RepeatMode.Restart), label = "ring")
    return { phase.value }
}

/**
 * The outbound card (item 24), in the 最快節點 card's rows, four at most: the mode, which opens the mode picker; then in
 * 規則 the main node and the two rule groups that sent the most traffic today, and 查看全部 for the rest; in 全域 the one
 * node; in 直連 a line that no node is in use.
 */
@Composable
private fun OutboundCard(state: ActivityUiState, mode: RvRouteMode, routes: RvActivityRoutes, nodes: List<RvNode>) {
    RvCard {
        RvRow({ RvIconDisc(R.drawable.rv_sym_directions) }, stringResource(R.string.act_mode), stringResource(mode.label), onClick = routes.mode) { RvChevron() }
        if (mode == RvRouteMode.Direct) {
            RvText(stringResource(R.string.rv_direct_note), Rv.type.rowSub, Modifier.padding(start = RvSpace.rowPadding, end = RvSpace.rowPadding, bottom = RvSpace.rowPadding), color = Rv.colors.textSecondary)
            return@RvCard
        }
        // A node switch cross-fades the row to the new flag and name.
        Crossfade(state.node, animationSpec = Rv.motion.tween(RvMotion.FADE_MS), label = "main node") { node ->
            val listed = node?.let { at -> nodes.firstOrNull { it.id == at.id } }
            RvRow(
                // The node in use: its flag carries the current node's check.
                { RvFlag(listed?.place?.name, RvSize.rowDisc, current = node != null) },
                node?.name ?: RV_NO_VALUE,
                stringResource(if (mode == RvRouteMode.Rule) R.string.rv_main_node else R.string.nav_nodes),
                onClick = routes.node,
                // Its latency over the change since the test before, as 節點 and the 最快節點 rows show it (L-12).
            ) { RvLatencyChange(node?.latencyMs?.takeUnless { node.failed }, listed?.changeMs?.takeUnless { node.failed }) }
        }
        if (mode != RvRouteMode.Rule) return@RvCard
        RvData.ruleOutbounds.take(RULE_ROWS).forEach { RuleRow(it, routes.rule) }
        RvSeeAll(pluralStringResource(R.plurals.rv_see_all_rules, RvData.RULES, RvData.RULES), routes.rules)
    }
}

/**
 * A rule group that sent traffic today: the node's flag, or for 直連 its own straight arrow (the diamond is the mode's), the
 * group, its node, its bytes.
 */
@Composable
private fun RuleRow(rule: RvRuleOutbound, onOpen: (Int) -> Unit) {
    val format = rememberRvFormat()
    val node = rule.nodeId?.let { RvData.node(it) }
    RvRow(
        { if (node != null) RvFlag(node.place.name, RvSize.rowDisc) else RvIconDisc(R.drawable.rv_sym_arrow_right_alt) },
        stringResource(rule.group),
        node?.name ?: stringResource(R.string.mode_direct),
        onClick = { onOpen(rule.group) },
    ) { RvText(format.bytes(rule.bytes).toString(), Rv.type.rowValue, align = TextAlign.End) }
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

/** The 最快節點 card lists the first three of the 節點 page's fastest; the outbound card, two rule groups after the main node. */
private const val FASTEST = 3
private const val HALF_TURN = 180f
private const val FULL_TURN = 360f
private const val RULE_ROWS = 2

private val Status.label: Int
    get() = when (this) {
        is Status.Running -> R.string.backend_connected

        Status.Starting -> R.string.status_starting

        Status.Stopping -> R.string.status_stopping

        Status.Stopped -> R.string.status_stopped

        is Status.Failed -> when (reason) {
            FailureReason.NoAllowedApps -> R.string.status_failed_no_allowed_apps
            FailureReason.StartFailed -> R.string.status_failed_start
            FailureReason.CoreStopped -> R.string.status_failed_core_stopped
            FailureReason.IncompatibleCore -> R.string.status_failed_incompatible_core
        }
    }

private val RvRouteMode.label: Int
    get() = when (this) {
        RvRouteMode.Rule -> R.string.rv_mode_rule
        RvRouteMode.Global -> R.string.mode_global
        RvRouteMode.Direct -> R.string.mode_direct
    }

@Preview(name = "Light", locale = "zh-rTW", widthDp = 412, heightDp = 915)
@Preview(name = "Dark", locale = "zh-rTW", widthDp = 412, heightDp = 915, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun RvActivityScreenPreview() {
    val dark = isSystemInDarkTheme()
    RvTheme(rvSystemScheme(dark), dark) { RvActivityScreen(rvSampleActivity(), onAction = {}, onConnect = {}) }
}
