package dev.doona.android.feature.activity

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.withInfiniteAnimationFrameNanos
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import dev.doona.android.R
import dev.doona.android.designsystem.rv.RV_NO_VALUE
import dev.doona.android.designsystem.rv.Rv
import dev.doona.android.designsystem.rv.RvAction
import dev.doona.android.designsystem.rv.RvDelta
import dev.doona.android.designsystem.rv.RvDotMotion
import dev.doona.android.designsystem.rv.RvDots
import dev.doona.android.designsystem.rv.RvFadeThrough
import dev.doona.android.designsystem.rv.RvHeader
import dev.doona.android.designsystem.rv.RvHero
import dev.doona.android.designsystem.rv.RvHeroSwipe
import dev.doona.android.designsystem.rv.RvMotion
import dev.doona.android.designsystem.rv.RvSize
import dev.doona.android.designsystem.rv.RvSkeletonFade
import dev.doona.android.designsystem.rv.RvSkeletonHero
import dev.doona.android.designsystem.rv.RvSpace
import dev.doona.android.designsystem.rv.RvStatsDots
import dev.doona.android.designsystem.rv.RvSteadyHero
import dev.doona.android.designsystem.rv.RvSteadyInline
import dev.doona.android.designsystem.rv.RvText
import dev.doona.android.designsystem.rv.figure
import dev.doona.android.designsystem.rv.rememberRvFormat
import dev.doona.android.designsystem.rv.rvActionRowFit
import dev.doona.android.designsystem.rv.rvGlidingRate
import dev.doona.android.designsystem.rv.rvHeroArt
import dev.doona.android.designsystem.rv.rvMapPan
import dev.doona.android.engine.FailureReason
import dev.doona.android.engine.Status
import dev.doona.android.feature.model.RvNode
import dev.doona.android.feature.model.RvRouteMode
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.collectLatest

/** [state] without the second's rates, which only the hero's figures read, from [ticking]. */
@Composable
internal fun Header(state: RvActivityState, ticking: State<RvActivityState>, mode: RvRouteMode, motion: HeaderMotion, insets: WindowInsets, hero: HeroLook, node: (String) -> RvNode?, actions: @Composable () -> Unit) {
    val running = state.status is Status.Running || state.status is Status.Starting
    // Direct traffic uses no node, so the map marks no place; a node marks its city, or its country's centroid.
    val spot = state.node?.takeIf { mode != RvRouteMode.Direct }?.let { node(it.id)?.spot }
    val statusBar = with(LocalDensity.current) { insets.getTop(this).toDp() }
    var heroBounds by remember { mutableStateOf<Rect?>(null) }
    val pan = rvMapPan(spot)
    val live = RvDotMotion(rvHeroArt(), motion.stretch ?: { 0f }, rvPulse(state.status is Status.Running && spot != null, motion.resting), pan.first, pan.second)
    // The dots run up behind the status bar, as Revolut's texture does, in the accent only while connected and in the fair
    // tone while it connects or disconnects (PV-c v3, the hero tinted by the connection state).
    val dots = when {
        state.status.busy -> Rv.colors.fair
        running -> Rv.colors.dots
        else -> Rv.colors.dotsIdle
    }
    RvHeader(spot, statusBar + RvDots.focusTop, statusBar, dots, heroBounds, live = live) {
        Spacer(Modifier.windowInsetsTopHeight(insets))
        Spacer(Modifier.height(RvSpace.headerTop))
        HeroBlock(state, ticking, mode, hero, Modifier.onPlaced { heroBounds = it.boundsInParent() }, motion.pushed)
        if (motion.pushed == null) actions() else Box(motion.pushed) { actions() }
        Spacer(Modifier.height(RvSpace.tabBlockGap))
    }
}

/**
 * The hero ([placed] where the map keeps its dots faint) and the gap under it, both [pushed] by the pull. H0 shows the
 * traffic. H1 shows it and the main node as two views a horizontal swipe turns between ([RvHeroPages]); 統計's pager dots
 * at the gap's top mark which one, and a tap on a dot shows its view. The dots take [RvSize.touch] of the gap, so the
 * actions stay where they are.
 */
@Composable
private fun HeroBlock(state: RvActivityState, ticking: State<RvActivityState>, mode: RvRouteMode, hero: HeroLook, placed: Modifier, pushed: Modifier?) {
    val moved = pushed ?: Modifier
    if (hero.swipe == RvHeroSwipe.Off) {
        HeroFigure(ticking, mode, placed.then(moved), hero.loading)
        Spacer(Modifier.height(RvSpace.heroToActions))
        return
    }
    val pager = rememberRvHeroPager(HERO_VIEWS)
    val names = listOf(stringResource(R.string.rv_hero_all_traffic), stringResource(R.string.rv_main_node))
    RvHeroPages(pager, names, placed.then(moved)) { if (it == 0) HeroFigure(ticking, mode, Modifier, hero.loading) else MainNodeFigure(state, mode, hero.nodes) }
    RvStatsDots(names.size, pager.view, moved, names, pager::show)
    Spacer(Modifier.height(RvSpace.heroToActions - RvSize.touch))
}

/**
 * The state and the mode over the download rate, the upload rate under it; a new state fades through the old (the two
 * captions differ in length, so a cross-fade would lay them over each other), the rates glide to each second's value
 * ([rvGlidingRate]). Read
 * from [ticking] here, so that a tick recomposes the figures and not the header around them. While [loading], their
 * skeleton.
 */
@Composable
private fun HeroFigure(ticking: State<RvActivityState>, mode: RvRouteMode, modifier: Modifier, loading: Boolean) {
    RvSkeletonFade(loading, { RvSkeletonHero() }, modifier) { HeroLines(ticking, mode) }
}

@Composable
private fun HeroLines(ticking: State<RvActivityState>, mode: RvRouteMode) {
    val state = ticking.value
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        RvFadeThrough("${stringResource(state.status.label)} · ${stringResource(mode.label)}", "caption", alignment = Alignment.TopCenter) { RvText(it, Rv.type.caption) }
        Spacer(Modifier.height(RvSpace.tight))
        val down = rvGlidingRate(state.liveRate(state.downRate, state.downHistory))
        val spoken = stringResource(R.string.rv_a11y_download, down.toString())
        val format = rememberRvFormat()
        val widest = remember(format) { format.widestRates() }
        RvSteadyHero(down, widest, spoken)
        Spacer(Modifier.height(RvSpace.subGap))
        // The hero is the download rate, so the line under it carries the other direction only (N-10).
        val up = rvGlidingRate(state.liveRate(state.upRate, state.upHistory))
        RvSteadyInline(R.drawable.rv_sym_arrow_upward, up, widest, Rv.colors.textSecondary, Rv.type.sub, stringResource(R.string.rv_a11y_upload, up.toString()))
    }
}

/**
 * H1's second view in the hero's three slots: 主節點 and the node's place, its latency as the figure (the number large,
 * the unit small, as the rate's), its name and the change since the test before under it. Direct traffic uses no node,
 * so it shows dashes. A node switch cross-fades, as the outbound card's row does.
 */
@Composable
private fun MainNodeFigure(state: RvActivityState, mode: RvRouteMode, nodes: List<RvNode>) {
    Crossfade(state.node?.takeIf { mode != RvRouteMode.Direct }, animationSpec = Rv.motion.tween(RvMotion.FADE_MS), label = "hero node") { node ->
        val listed = node?.let { at -> nodes.firstOrNull { it.id == at.id } }
        val latency = node?.latencyMs?.takeUnless { node.failed }
        val figure = latency?.let { stringResource(R.string.latency_ms, it) }
        val whole = latency?.toString()?.takeIf { figure.orEmpty().startsWith(it) } ?: figure ?: RV_NO_VALUE
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            RvText("${stringResource(R.string.rv_main_node)} · ${listed?.let { stringResource(it.place.label) } ?: RV_NO_VALUE}", Rv.type.caption)
            Spacer(Modifier.height(RvSpace.tight))
            RvHero(whole, figure?.removePrefix(whole).orEmpty())
            Spacer(Modifier.height(RvSpace.subGap))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(RvSpace.tight)) {
                RvText(node?.name ?: RV_NO_VALUE, Rv.type.sub, color = Rv.colors.textSecondary)
                RvDelta(listed?.changeMs?.takeUnless { node.failed })
            }
        }
    }
}

/**
 * Connect or disconnect (busy while either is under way), the outbound mode and a latency test (Mode and Latency
 * drawn, their full names read, so the four labels keep their size) and more, their labels one size ([rvActionRowFit]). 中斷連線 holds from the connect's start to the disconnect's end, so a disconnect
 * under way keeps its word beside its busy bar and turns to 連線 with the glyph when it has ended; while it is under way
 * the action takes no tap.
 */
@Composable
internal fun HeroActions(state: RvActivityState, onAction: (ActivityAction) -> Unit, onConnect: () -> Unit, routes: RvActivityRoutes) {
    val on = state.status is Status.Running || state.status.busy
    val labels = listOf(
        stringResource(if (on) R.string.rv_disconnect else R.string.rv_connect),
        stringResource(R.string.rv_action_mode),
        stringResource(R.string.rv_action_latency),
        stringResource(R.string.rv_more),
    )
    val modeName = stringResource(R.string.act_mode)
    val testName = stringResource(R.string.rv_test_latency)
    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = RvSpace.gutter)) {
        val style = rvActionRowFit(labels, maxWidth / labels.size)
        Row(Modifier.fillMaxWidth()) {
            val next = state.mode.next()
            val toggle = if (on) ({ onAction(ActivityAction.Disconnect) }) else onConnect
            // A disconnect under way takes no second one: the tap would only start it again.
            RvAction(R.drawable.rv_sym_power, labels[0], toggle, Modifier.weight(1f), busy = state.status.busy, labelStyle = style, enabled = state.status != Status.Stopping)
            RvAction(R.drawable.rv_sym_directions, labels[1], { onAction(ActivityAction.SetMode(next)) }, Modifier.weight(1f), labelStyle = style, description = modeName)
            RvAction(R.drawable.rv_sym_speed, labels[2], routes.test, Modifier.weight(1f), labelStyle = style, description = testName)
            RvAction(R.drawable.rv_sym_more_horiz, labels[3], routes.more, Modifier.weight(1f), labelStyle = style)
        }
    }
    ConnectionTicks(state.status)
}

/** A connect or a disconnect is under way. */
private val Status.busy: Boolean get() = this == Status.Starting || this == Status.Stopping

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

/**
 * The marker's pulse while [on] (connected, with a place to mark): 0 to 1 each [RvMotion.PULSE_MS]; 0 off, without
 * motion, or while [resting] (read where it is drawn, so resting redraws nothing but the ring, once).
 */
@Composable
internal fun rvPulse(on: Boolean, resting: () -> Boolean): () -> Float {
    if (!on || Rv.motion.instant) return { 0f }
    var phase by remember { mutableFloatStateOf(0f) }
    // The loop runs only while the ring is not resting: a resting pulse asks for no frame (it used to keep an infinite
    // transition going and only stop reading it). It starts again from the ring's first frame. Each frame is an infinite
    // animation's frame, so a test that waits for idle stops the loop, as it stops an infinite transition.
    LaunchedEffect(resting) {
        snapshotFlow { resting() }.collectLatest { paused ->
            phase = 0f
            if (paused) return@collectLatest
            val scale = currentCoroutineContext()[MotionDurationScale]?.scaleFactor ?: 1f
            val period = RvMotion.PULSE_MS * 1_000_000L * scale
            var start = -1L
            while (true) {
                withInfiniteAnimationFrameNanos { now ->
                    if (start < 0) start = now
                    phase = RvMotion.Decelerate.transform(((now - start) % period.toLong().coerceAtLeast(1L)) / period)
                }
            }
        }
    }
    return { if (resting()) 0f else phase }
}

/** H1's hero: 全部流量, then 主節點. */
private const val HERO_VIEWS = 2

/**
 * The header's status: a short word, never a reason sentence (owner decision q-actcaption, 2026-11-26), which the
 * notice below gives. Every failed start reads 連線失敗, the VPN notification's word; a VPN another app or the system
 * turned off reads 未連線, as the approved vpn-taken sample does; another app's lockdown is VPN 已鎖定.
 */
private val Status.label: Int
    get() = when (this) {
        is Status.Running -> R.string.backend_connected

        Status.Starting -> R.string.status_starting

        Status.Stopping -> R.string.status_stopping

        Status.Stopped -> R.string.status_stopped

        is Status.Failed -> when (reason) {
            FailureReason.NoAllowedApps, FailureReason.StartFailed, FailureReason.IncompatibleCore, FailureReason.VpnNotEstablished -> R.string.vpn_status_failed
            FailureReason.CoreStopped -> R.string.status_failed_core_stopped
            FailureReason.Revoked -> R.string.status_stopped
            FailureReason.LockdownEnabled -> R.string.status_vpn_locked
        }
    }
