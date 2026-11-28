package dev.doona.android.prototype.rv

import android.content.res.Configuration
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import dev.doona.android.designsystem.rv.RvHeroSwipe
import dev.doona.android.designsystem.rv.RvNotice
import dev.doona.android.designsystem.rv.RvPullLook
import dev.doona.android.designsystem.rv.RvTheme
import dev.doona.android.designsystem.rv.rvSystemScheme
import dev.doona.android.engine.Member
import dev.doona.android.engine.Mode
import dev.doona.android.engine.Status
import dev.doona.android.feature.NOT_WIRED
import dev.doona.android.feature.activity.ActivityAction
import dev.doona.android.feature.activity.RvActivityCards
import dev.doona.android.feature.activity.RvActivityRoutes
import dev.doona.android.feature.activity.RvActivityRule
import dev.doona.android.feature.activity.RvActivityScreen
import dev.doona.android.feature.activity.RvActivityState
import dev.doona.android.feature.activity.RvMainNode
import dev.doona.android.feature.model.RvLabel
import dev.doona.android.feature.model.RvNode
import dev.doona.android.feature.model.RvRouteMode
import dev.doona.android.feature.model.RvRuleOutbound
import dev.doona.android.feature.model.RvSubscription
import dev.doona.android.prototype.rv.data.RvData
import dev.doona.android.prototype.rv.policy.RvPolicyData

/**
 * 活動 as the sample keeps it, in engine terms (the old screen's state, which the page no longer takes): the status, the
 * engine's mode, the group that carries Global traffic with the member it uses now, the node traffic ends on (null in
 * Direct), and the second's rates with the minute's samples. [RvActivityScreenSample] turns it into the page's model.
 */
@Immutable
internal data class RvActivitySampleState(
    val status: Status = Status.Stopped,
    val mode: Mode = Mode.Global,
    val outboundGroupId: String? = null,
    val outbound: String? = null,
    val node: Member? = null,
    val downRate: Long = 0,
    val upRate: Long = 0,
    val downHistory: List<Float> = emptyList(),
    val upHistory: List<Float> = emptyList(),
)

/**
 * 活動 connected, built from [RvData]: running since the session's start, Global through the
 * auto group to the [nodeId] node of [nodes], and the session's last minute, whose last second is the current rate; the
 * live sample moves that minute on by [second]s.
 */
internal fun rvSampleActivity(nodeId: String = RvData.CURRENT_ID, nodes: List<RvNode> = RvData.nodes, second: Int = 0): RvActivitySampleState {
    val session = RvData.session
    val node = nodes.first { it.id == nodeId }
    val down = session.minuteDown.movedOn(second)
    val up = session.minuteUp.movedOn(second)
    return RvActivitySampleState(
        status = Status.Running(sinceEpochMs = session.startEpochMs),
        mode = Mode.Global,
        outboundGroupId = AUTO_GROUP_ID,
        outbound = RvData.AUTO_GROUP,
        node = Member(node.id, node.name, node.latencyMs, failed = node.latencyMs == null, subscriptionId = RvData.subscriptions.first { it.name == node.subscription }.id),
        downRate = down.last().toLong(),
        upRate = up.last().toLong(),
        downHistory = down,
        upHistory = up,
    )
}

/** The minute [seconds] later, as the sample replays it: the window moved on, its first samples coming round again. */
private fun List<Float>.movedOn(seconds: Int): List<Float> {
    val by = seconds.mod(size)
    return if (by == 0) this else drop(by) + take(by)
}

/** The same screen disconnected: no rates and no history. */
internal fun rvIdleActivity(): RvActivitySampleState =
    rvSampleActivity().copy(status = Status.Stopped, downRate = 0, upRate = 0, downHistory = emptyList(), upHistory = emptyList())

/** A group's id is `g-` plus its name. */
private const val AUTO_GROUP_ID = "g-" + RvData.AUTO_GROUP

/** 活動's cards as [RvData] has them at 10:24, the rule groups' glyphs as [RvPolicyData] draws them, by group id. */
internal val rvSampleActivityCards = RvActivityCards(
    today = RvData.today,
    nowHour = RvData.nowHour,
    yesterdayByNow = RvData.YESTERDAY_BY_NOW,
    nowEpochMs = RvData.nowEpochMs,
    ruleCount = RvData.RULES,
    mainNodeId = RvData.CURRENT_ID,
    node = RvData::node,
    groupKind = { id -> RvPolicyData.groups.firstOrNull { it.id == id }?.kind },
)

/** The id of the sample's rule group named [name]: [RvPolicyData]'s group of that name, or one made from the name. */
internal fun rvSampleRuleGroupId(name: Int): String = RvPolicyData.groups.firstOrNull { it.name == RvLabel.Res(name) }?.id ?: "rule-$name"

/** [state] as the page reads it, in the [mode] the page shows. */
private fun pageState(state: RvActivitySampleState, mode: RvRouteMode) = RvActivityState(
    status = state.status,
    mode = mode,
    node = state.node?.let { RvMainNode(it.id, it.name, it.latencyMs, it.failed) },
    downRate = state.downRate,
    upRate = state.upRate,
    downHistory = state.downHistory,
    upHistory = state.upHistory,
)

/**
 * 活動 on the sample: the page with [RvData]'s nodes, subscriptions and rule groups and [rvSampleActivityCards] unless the
 * caller gives its own. The page itself takes them all as parameters, in main's model: the sample's state and its rule
 * groups (named by string resources) are turned into it here. The sandbox, its previews and tests call this.
 */
@Composable
internal fun RvActivityScreenSample(
    state: RvActivitySampleState,
    onAction: (ActivityAction) -> Unit,
    onConnect: () -> Unit,
    routes: RvActivityRoutes = rvSampleActivityRoutes(),
    mode: RvRouteMode = if (state.mode == Mode.Direct) RvRouteMode.Direct else RvRouteMode.Rule,
    nodes: List<RvNode> = RvData.nodes,
    subscriptions: List<RvSubscription> = RvData.subscriptions,
    pull: RvPullLook = RvPullLook.None,
    hero: RvHeroSwipe = RvHeroSwipe.Off,
    banner: RvNotice? = null,
    noSubscription: (() -> Unit)? = null,
    loading: Boolean = false,
    navFade: Boolean = false,
    rules: List<RvRuleOutbound> = RvData.ruleOutbounds,
) {
    val rows = rules.map { RvActivityRule(stringResource(it.group), it.nodeId?.let { _ -> rvSampleRuleGroupId(it.group) }, it.nodeId, it.bytes, it.udpNodeId) }
    RvActivityScreen(pageState(state, mode), onAction, onConnect, routes, nodes, subscriptions, pull, hero, banner, noSubscription, loading, navFade, rows, rvSampleActivityCards)
}

/** The sample's routes: none leads anywhere, and the latency test and 更多 are not wired. */
internal fun rvSampleActivityRoutes() = RvActivityRoutes(
    traffic = {},
    stats = {},
    search = {},
    nodes = {},
    node = {},
    tab = {},
    mode = {},
    rule = {},
    rules = {},
    test = NOT_WIRED,
    subscriptions = {},
    more = NOT_WIRED,
)

@Preview(name = "Light", locale = "zh-rTW", widthDp = 412, heightDp = 915)
@Preview(name = "Dark", locale = "zh-rTW", widthDp = 412, heightDp = 915, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun RvActivityScreenPreview() {
    val dark = isSystemInDarkTheme()
    RvTheme(rvSystemScheme(dark), dark) { RvActivityScreenSample(rvSampleActivity(), onAction = {}, onConnect = {}) }
}
