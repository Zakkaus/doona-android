package dev.doona.android.prototype.rv

import androidx.compose.runtime.Composable
import dev.doona.android.designsystem.rv.RvNotice
import dev.doona.android.designsystem.rv.RvPullLook
import dev.doona.android.engine.Member
import dev.doona.android.engine.Mode
import dev.doona.android.engine.Status
import dev.doona.android.feature.activity.ActivityAction
import dev.doona.android.feature.activity.ActivityUiState
import dev.doona.android.feature.model.RvLabel
import dev.doona.android.feature.model.RvNode
import dev.doona.android.feature.model.RvRouteMode
import dev.doona.android.feature.model.RvRuleOutbound
import dev.doona.android.feature.model.RvSubscription
import dev.doona.android.prototype.rv.data.RvData
import dev.doona.android.prototype.rv.policy.RvPolicyData

/**
 * 活動 connected, in main's [ActivityUiState] built from [RvData]: running since the session's start, Global through the
 * auto group to the [nodeId] node of [nodes], and the session's last minute, whose last second is the current rate; the
 * live sample moves that minute on by [second]s.
 */
internal fun rvSampleActivity(nodeId: String = RvData.CURRENT_ID, nodes: List<RvNode> = RvData.nodes, second: Int = 0): ActivityUiState {
    val session = RvData.session
    val node = nodes.first { it.id == nodeId }
    val down = session.minuteDown.movedOn(second)
    val up = session.minuteUp.movedOn(second)
    return ActivityUiState(
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
internal fun rvIdleActivity(): ActivityUiState =
    rvSampleActivity().copy(status = Status.Stopped, downRate = 0, upRate = 0, downHistory = emptyList(), upHistory = emptyList())

/** A group's id is `g-` plus its name. */
private const val AUTO_GROUP_ID = "g-" + RvData.AUTO_GROUP

/** 活動's cards as [RvData] has them at 10:24, the rule groups' glyphs as [RvPolicyData] draws them. */
internal val rvSampleActivityCards = RvActivityCards(
    today = RvData.today,
    nowHour = RvData.nowHour,
    yesterdayByNow = RvData.YESTERDAY_BY_NOW,
    nowEpochMs = RvData.nowEpochMs,
    ruleCount = RvData.RULES,
    mainNodeId = RvData.CURRENT_ID,
    node = RvData::node,
    groupKind = { name -> RvPolicyData.groups.firstOrNull { it.name == RvLabel.Res(name) }?.kind },
)

/**
 * 活動 on the sample: the page with [RvData]'s nodes, subscriptions and rule groups and [rvSampleActivityCards] unless the
 * caller gives its own. The page itself takes them all as parameters; the sandbox, its previews and tests call this.
 */
@Composable
internal fun RvActivityScreenSample(
    state: ActivityUiState,
    onAction: (ActivityAction) -> Unit,
    onConnect: () -> Unit,
    routes: RvActivityRoutes = RvActivityRoutes(),
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
) = RvActivityScreen(state, onAction, onConnect, routes, mode, nodes, subscriptions, pull, hero, banner, noSubscription, loading, navFade, rules, rvSampleActivityCards)
