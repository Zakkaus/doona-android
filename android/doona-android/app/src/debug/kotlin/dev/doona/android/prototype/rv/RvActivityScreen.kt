package dev.doona.android.prototype.rv

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import dev.doona.android.R
import dev.doona.android.designsystem.theme.LocalFrameInsets
import dev.doona.android.engine.Mode
import dev.doona.android.engine.Status
import dev.doona.android.feature.NOT_WIRED
import dev.doona.android.feature.activity.ActivityAction
import dev.doona.android.feature.activity.ActivityUiState
import dev.doona.android.prototype.rv.data.RvData
import dev.doona.android.prototype.rv.data.RvRouteMode
import dev.doona.android.prototype.rv.data.RvRuleOutbound
import dev.doona.android.prototype.rv.data.fastest
import java.util.Locale

/**
 * The 活動 screen in Revolut's home structure (#11, #15): the download rate as the hero figure and the upload rate under
 * it on a dot map of the world that marks the node's country (#07, #08); under them doona's own connection control, a
 * glass capsule with the connection's state and its one button, over 切換節點 and 測試延遲 (O1). Then the outbound card
 * (item 24: the mode, the main node and the rule groups that sent traffic today), 即時速度 (O3, it opens the traffic
 * page), and three more cards as Revolut's home goes on: 今日用量, 訂閱 and 最快節點. Over the content sit the glass top
 * bar (measured §4), whose duck shows the connection (O5), and the floating glass bottom bar (§9), which shrinks while the
 * content scrolls up. It renders [ActivityUiState]; the engine has no rule mode yet, so [mode] comes from the sample, and
 * the lower cards read [RvData].
 */
@Composable
internal fun RvActivityScreen(
    state: ActivityUiState,
    onAction: (ActivityAction) -> Unit,
    onConnect: () -> Unit,
    routes: RvActivityRoutes = RvActivityRoutes(),
    mode: RvRouteMode = if (state.mode == Mode.Direct) RvRouteMode.Direct else RvRouteMode.Rule,
) {
    val insets = LocalFrameInsets.current ?: WindowInsets.systemBars
    val scroll = rememberScrollState()
    val glass = rememberRvGlass()
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().rvGlassSource(glass).background(Rv.colors.page).verticalScroll(scroll)) {
            Header(state, mode, onAction, onConnect, routes, insets)
            RvGutter {
                OutboundCard(state, mode, routes)
                val quiet = state.status !is Status.Running || (state.downHistory.all { it == 0f } && state.upHistory.all { it == 0f })
                RvLiveSpeedCard(state.downHistory, state.upHistory, quiet, routes.traffic)
                RvUsageCard(RvData.today, RvData.nowHour, RvData.YESTERDAY_BY_NOW)
                RvSubscriptionsCard(RvData.subscriptions)
                RvFastestCard(RvData.nodes.fastest(FASTEST), RvData.current.id, routes.nodes)
            }
            Spacer(Modifier.height(rvNavClearance(insets)))
        }
        RvTopBar(glass, insets, rvShade(scroll), state.status is Status.Running, onSearch = routes.nodes, onStats = routes.stats, Modifier.align(Alignment.TopCenter))
        RvFloatingNav(RV_NAV_ACTIVITY, routes.tab, glass, rvCollapsedOnScroll(scroll), insets, Modifier.align(Alignment.BottomCenter))
    }
}

/**
 * Where 活動 leads: 即時速度, 統計, 節點 (the search pill and 切換節點), the node row, the nav's tabs, the mode picker,
 * a rule row (by its group) and the full list of rules.
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
)

@Composable
private fun Header(state: ActivityUiState, mode: RvRouteMode, onAction: (ActivityAction) -> Unit, onConnect: () -> Unit, routes: RvActivityRoutes, insets: WindowInsets) {
    val format = rememberRvFormat()
    val running = state.status is Status.Running || state.status is Status.Starting
    // Direct traffic uses no node, so the map marks no place.
    val place = state.node?.takeIf { mode != RvRouteMode.Direct }?.let { RvData.node(it.id)?.place }
    val statusBar = with(LocalDensity.current) { insets.getTop(this).toDp() }
    var heroBounds by remember { mutableStateOf<Rect?>(null) }
    // The capsules are glass over the map, so the map is recorded on its own: the page's recording holds the capsules.
    val mapGlass = rememberRvGlass()
    // The dots run up behind the status bar, as Revolut's texture does, in the accent only while connected.
    RvHeader(place, statusBar + RvDots.focusTop, statusBar, if (running) Rv.colors.dots else Rv.colors.dotsIdle, heroBounds, glass = mapGlass) {
        Spacer(Modifier.windowInsetsTopHeight(insets))
        Spacer(Modifier.height(RvSpace.headerTop))
        // The capsule states the connection and the outbound card the mode right under it, so the hero carries only the
        // rates: a lone mode word over the figure would read as the figure's label (O1).
        Column(Modifier.onPlaced { heroBounds = it.boundsInParent() }, horizontalAlignment = Alignment.CenterHorizontally) {
            RvHero(format.rate(state.downRate.toDouble()))
            Spacer(Modifier.height(RvSpace.subGap))
            // The hero is the download rate, so the line under it carries the other direction only (N-10).
            RvInline(R.drawable.rv_sym_arrow_upward, format.rate(state.upRate.toDouble()).toString(), Rv.colors.textSecondary, Rv.type.sub)
        }
        Spacer(Modifier.height(RvSpace.heroToActions))
        ConnectionCapsule(mapGlass, state.status, if (running) ({ onAction(ActivityAction.Disconnect) }) else onConnect)
        Spacer(Modifier.height(RvConnect.gap))
        Row(Modifier.fillMaxWidth().padding(horizontal = RvSpace.gutter), horizontalArrangement = Arrangement.spacedBy(RvConnect.gap)) {
            SecondaryCapsule(mapGlass, stringResource(R.string.rv_switch_node), routes.nodes, Modifier.weight(1f))
            SecondaryCapsule(mapGlass, stringResource(R.string.rv_test_latency), NOT_WIRED, Modifier.weight(1f))
        }
        Spacer(Modifier.height(RvSpace.actionsToCard))
    }
}

/**
 * The connection capsule (O1): glass across the content width with a status dot, the state and, while connected, how long
 * it has run in tabular figures; at the end the stop disc while running or starting, else a filled 連線 button ([onToggle]).
 */
@Composable
private fun ConnectionCapsule(glass: RvGlass, status: Status, onToggle: () -> Unit) {
    val colors = Rv.colors
    Row(
        Modifier
            .padding(horizontal = RvSpace.gutter)
            .fillMaxWidth()
            .height(RvConnect.capsule)
            .rvControl(glass, CircleShape)
            .padding(start = RvConnect.start, end = RvConnect.buttonInset),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(RvSize.dot).clip(CircleShape).background(if (status is Status.Running) colors.good else colors.control))
        Spacer(Modifier.width(RvSpace.trailingGap))
        RvText(stringResource(status.label), Rv.type.rowTitle)
        if (status is Status.Running) {
            Spacer(Modifier.width(RvConnect.gap))
            RvText(rvClock((RvData.nowEpochMs - status.sinceEpochMs) / MS_PER_SECOND), Rv.type.rowValue, color = colors.textSecondary)
        }
        Spacer(Modifier.weight(1f))
        if (status is Status.Running || status is Status.Starting) StopDisc(onToggle) else ConnectButton(onToggle)
    }
}

/**
 * The capsule's stop button: a 40 dp disc with the power glyph in the back control's fill and ring; in dark, where that
 * fill matches the capsule's glass, the glass controls' rim outlines it.
 */
@Composable
private fun StopDisc(onClick: () -> Unit) {
    val colors = Rv.colors
    val label = stringResource(R.string.rv_disconnect)
    val ring = (colors.backRing ?: colors.controlRim)?.let { Modifier.border(RvChrome.ring, it, CircleShape) } ?: Modifier
    Box(
        Modifier
            .size(RvConnect.button)
            .clip(CircleShape)
            .background(colors.backDisc)
            .then(ring)
            .rvPress(CircleShape, Role.Button, onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { RvGlyph(R.drawable.rv_sym_power, RvSize.rowGlyph, colors.text) }
}

/** The capsule's connect button: a filled accent pill as tall as the stop disc. */
@Composable
private fun ConnectButton(onClick: () -> Unit) {
    Box(
        Modifier
            .height(RvConnect.button)
            .clip(CircleShape)
            .background(Rv.colors.positive)
            .rvPress(CircleShape, Role.Button, onClick)
            .padding(horizontal = RvConnect.buttonPadH),
        contentAlignment = Alignment.Center,
    ) { RvText(stringResource(R.string.rv_connect), Rv.type.action, color = Rv.colors.onPositive) }
}

/** A 44 dp glass capsule under the connection capsule, its [label] centred. */
@Composable
private fun SecondaryCapsule(glass: RvGlass, label: String, onClick: () -> Unit, modifier: Modifier) {
    Box(
        modifier.height(RvConnect.secondary).rvControl(glass, CircleShape).rvPress(CircleShape, Role.Button, onClick),
        contentAlignment = Alignment.Center,
    ) { RvText(label, Rv.type.action, align = TextAlign.Center) }
}

/**
 * The outbound card (item 24), in the 最快節點 card's rows: the mode, which opens the mode picker; then in 規則 the main
 * node and up to three rule groups that sent traffic today, most bytes first, and 查看全部; in 全域 the one node; in
 * 直連 a line that no node is in use.
 */
@Composable
private fun OutboundCard(state: ActivityUiState, mode: RvRouteMode, routes: RvActivityRoutes) {
    RvCard {
        RvRow({ RvIconDisc(R.drawable.rv_sym_directions) }, stringResource(R.string.act_mode), stringResource(mode.label), onClick = routes.mode) { RvChevron() }
        if (mode == RvRouteMode.Direct) {
            RvText(stringResource(R.string.rv_direct_note), Rv.type.rowSub, Modifier.padding(start = RvSpace.rowPadding, end = RvSpace.rowPadding, bottom = RvSpace.rowPadding), color = Rv.colors.textSecondary)
            return@RvCard
        }
        val node = state.node
        val listed = node?.let { RvData.node(it.id) }
        RvRow(
            // The node in use: its flag carries the current node's check.
            { RvFlag(listed?.place?.name, RvSize.rowDisc, current = node != null) },
            node?.name ?: RV_NO_VALUE,
            stringResource(if (mode == RvRouteMode.Rule) R.string.rv_main_node else R.string.nav_nodes),
            onClick = routes.node,
            // Its latency over the change since the test before, as 節點 and the 最快節點 rows show it (L-12).
        ) { RvLatencyChange(node?.latencyMs?.takeUnless { node.failed }, listed?.changeMs?.takeUnless { node.failed }) }
        if (mode != RvRouteMode.Rule) return@RvCard
        RvData.ruleOutbounds.take(RULE_ROWS).forEach { RuleRow(it, routes.rule) }
        RvSeeAll(pluralStringResource(R.plurals.rv_see_all_rules, RvData.RULES, RvData.RULES), routes.rules)
    }
}

/** A rule group that sent traffic today: the node's flag (or the route disc for 直連), the group, its node, its bytes. */
@Composable
private fun RuleRow(rule: RvRuleOutbound, onOpen: (Int) -> Unit) {
    val format = rememberRvFormat()
    val node = rule.nodeId?.let { RvData.node(it) }
    RvRow(
        { if (node != null) RvFlag(node.place.name, RvSize.rowDisc) else RvIconDisc(R.drawable.rv_sym_directions) },
        stringResource(rule.group),
        node?.name ?: stringResource(R.string.mode_direct),
        onClick = { onOpen(rule.group) },
    ) { RvText(format.bytes(rule.bytes).toString(), Rv.type.rowValue, align = TextAlign.End) }
}

/** A duration as a clock, `1:42:05`. */
private fun rvClock(seconds: Long): String = "%d:%02d:%02d".format(Locale.ROOT, seconds / SECONDS_PER_HOUR, seconds % SECONDS_PER_HOUR / SECONDS_PER_MINUTE, seconds % SECONDS_PER_MINUTE)

/** The 最快節點 card lists the first three of the 節點 page's fastest; the outbound card, three rule groups. */
private const val FASTEST = 3
private const val RULE_ROWS = 3
private const val MS_PER_SECOND = 1_000L
private const val SECONDS_PER_MINUTE = 60L
private const val SECONDS_PER_HOUR = 3_600L

private val Status.label: Int
    get() = when (this) {
        is Status.Running -> R.string.backend_connected
        Status.Starting -> R.string.status_starting
        Status.Stopping -> R.string.status_stopping
        Status.Stopped, is Status.Failed -> R.string.status_stopped
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
