package dev.doona.android.feature.activity

import androidx.compose.runtime.Immutable
import dev.doona.android.engine.ErrorCode
import dev.doona.android.engine.Mode
import dev.doona.android.engine.Status
import dev.doona.android.feature.model.RvDay
import dev.doona.android.feature.model.RvGroupKind
import dev.doona.android.feature.model.RvNode
import dev.doona.android.feature.model.RvRouteMode
import dev.doona.android.feature.model.RvSubscription

/**
 * What 活動's header, hero and traffic tiles read: the connection [status], the routing [mode], the [node] that carries
 * the traffic no rule picks (null in Direct, or while nothing resolves), and the second's rates with the last minute's
 * samples. Only the rates and the samples change on a second's tick ([steady] drops them).
 */
@Immutable
internal data class RvActivityState(
    val status: Status = Status.Stopped,
    val mode: RvRouteMode = RvRouteMode.Rule,
    val node: RvMainNode? = null,
    val downRate: Long = 0,
    val upRate: Long = 0,
    val downHistory: List<Float> = emptyList(),
    val upHistory: List<Float> = emptyList(),
) {
    /** This state without what a second's tick changes: the rates and the minute's samples. */
    fun steady(): RvActivityState = copy(downRate = 0, upRate = 0, downHistory = emptyList(), upHistory = emptyList())
}

/** The node the main group's TCP selection ends on, as 活動 shows it: its name and its last test. */
@Immutable
internal data class RvMainNode(val id: String, val name: String, val latencyMs: Int?, val failed: Boolean)

/** The engine mode the mode action switches to from [this]: 規則, 全域, 直連, then 規則 again. */
internal fun RvRouteMode.next(): Mode = when (this) {
    RvRouteMode.Rule -> Mode.Global
    RvRouteMode.Global -> Mode.Direct
    RvRouteMode.Direct -> Mode.Rule
}

internal fun Mode.routeMode(): RvRouteMode = when (this) {
    Mode.Rule -> RvRouteMode.Rule
    Mode.Global -> RvRouteMode.Global
    Mode.Direct -> RvRouteMode.Direct
}

/**
 * A rule target that sent traffic today: the group's [label] and [groupId] (both null for 直連, which the page names),
 * the node the group's TCP selection ends on and, when the group's UDP ends on another node, that one; the bytes both
 * ways that today's rules sent there.
 */
@Immutable
internal data class RvActivityRule(val label: String?, val groupId: String?, val nodeId: String?, val bytes: Long?, val udpNodeId: String?)

/**
 * What 活動's cards read besides [RvActivityState] and its lists: today's bytes by the hour, where now falls in the day
 * and yesterday's bytes by now (今日用量); now, for the subscriptions' end dates; how many rules the profile has (the
 * see-all line); the node the main group's selection ends on, which 最快節點 checks while no node is in use; a node by
 * its id, for the map's spot and the rule rows; and how a rule group picks, by its id.
 */
@Immutable
internal class RvActivityCards(
    val today: RvDay,
    val nowHour: Float,
    val yesterdayByNow: Long,
    val nowEpochMs: Long,
    val ruleCount: Int,
    val mainNodeId: String,
    val node: (String) -> RvNode?,
    val groupKind: (String) -> RvGroupKind?,
)

/**
 * What 活動 says when it cannot carry traffic, one at a time; the page's banner names each (see the sample's
 * `rvActivityBanner`). [route] is the sample's name for it.
 */
internal enum class RvActivityNotice(val route: String) {
    NoSubscription("empty"),
    NoNode("no-node"),
    VpnTaken("vpn-taken"),
    Offline("offline"),
    CoreStopped("core-stopped"),
    Crash("crash"),
    StartFailed("failed-start"),
    StartNoApps("failed-no-apps"),
    StartIncompatible("failed-incompatible"),
    StartNoNodes("failed-no-nodes"),
    Lockdown("lockdown"),
    ConsentDenied("consent-denied"),
    NotifyDenied("notify-denied"),
}

/**
 * Where a notice's one action leads (owner decisions q-fail and q-startfail): what can mend its cause. Only a cause that
 * may pass on its own is retried by connecting again ([Reconnect]); no node leads to 新增訂閱, no allowed app to the
 * per-app list, an incompatible core to the update check, another app's lockdown to the system's VPN settings.
 */
internal enum class ActivityRemedy { AddSubscription, PerApp, CheckUpdate, Reconnect, Retest, ExportReport, NetworkSettings, NotifySettings, VpnSettings }

internal val RvActivityNotice.remedy: ActivityRemedy
    get() = when (this) {
        RvActivityNotice.NoSubscription, RvActivityNotice.StartNoNodes -> ActivityRemedy.AddSubscription
        RvActivityNotice.StartNoApps -> ActivityRemedy.PerApp
        RvActivityNotice.StartIncompatible -> ActivityRemedy.CheckUpdate
        RvActivityNotice.Lockdown -> ActivityRemedy.VpnSettings
        RvActivityNotice.StartFailed, RvActivityNotice.CoreStopped, RvActivityNotice.VpnTaken, RvActivityNotice.ConsentDenied -> ActivityRemedy.Reconnect
        RvActivityNotice.NoNode -> ActivityRemedy.Retest
        RvActivityNotice.Crash -> ActivityRemedy.ExportReport
        RvActivityNotice.Offline -> ActivityRemedy.NetworkSettings
        RvActivityNotice.NotifyDenied -> ActivityRemedy.NotifySettings
    }

/** An action of 活動's that failed: which one, the engine's [code], and the action that tries it again. */
@Immutable
internal data class RvActivityFailure(val attempt: RvActivityAttempt, val code: ErrorCode, val retry: ActivityAction)

/** The actions whose failure 活動 shows as a banner where it happened (plan 8). */
internal enum class RvActivityAttempt { SwitchMode, TestLatency }

/**
 * Everything 活動 shows: the header's [state], the lists its cards read ([nodes], [subscriptions], [rules] by bytes),
 * the [cards]' own values, the one [notice] that heads the cards, a failed action's [failure], and whether the page
 * still waits for its data ([loading]).
 */
@Immutable
internal data class RvActivityUi(
    val state: RvActivityState,
    val nodes: List<RvNode>,
    val subscriptions: List<RvSubscription>,
    val rules: List<RvActivityRule>,
    val cards: RvActivityCards,
    val notice: RvActivityNotice?,
    val failure: RvActivityFailure?,
    val loading: Boolean,
    val hasSubscriptions: Boolean,
)
