package dev.doona.android.feature.activity

import dev.doona.android.engine.ErrorCode
import dev.doona.android.engine.FailureReason
import dev.doona.android.engine.Mode
import dev.doona.android.engine.Status
import dev.doona.android.feature.model.NodePlaces
import dev.doona.android.feature.model.RvNode
import dev.doona.android.feature.model.RvRouteMode
import dev.doona.android.feature.model.RvSubscription
import dev.doona.android.feature.model.rvNode
import dev.doona.android.feature.policies.rvGroupKind
import dev.doona.android.feature.subscriptions.rvSubscription
import dev.doona.android.honk.Group
import dev.doona.android.honk.HealthState
import dev.doona.android.honk.Node
import dev.doona.android.honk.OutboundKind
import dev.doona.android.honk.OutboundTraffic
import dev.doona.android.honk.Provider
import dev.doona.android.honk.ProviderKind
import dev.doona.android.honk.RuntimeTraffic
import dev.doona.android.honk.TrafficHistory

/**
 * What 活動's lists are mapped from, read far less often than the second's traffic: honk's [profile] (null until read),
 * the [mode], the bytes each outbound carried since honk started ([outbounds], null until read), and now.
 */
internal class ActivityData(
    val profile: HonkProfile?,
    val mode: Mode,
    val outbounds: List<OutboundTraffic>?,
    val nowEpochMs: Long,
    val nowHour: Float,
)

/**
 * honk's profile as 活動 reads it: its subscriptions ([providers]), [nodes] and [groups], the group its rules fall back
 * to ([mainGroup], by name; null when they fall back to `direct` or `block`) and how many rules it has, its fallback among them (as 全部規則 lists it).
 */
internal class HonkProfile(
    val providers: List<Provider>,
    val nodes: List<Node>,
    val groups: List<Group>,
    val mainGroup: String?,
    val ruleCount: Int,
)

/**
 * The part of 活動 one catalog, usage read or clock step decides: the mode, the main node, the lists and the cards,
 * whether every node failed its test ([allFailed]) and whether a subscription exists. [mapped] requires a profile.
 */
internal class ActivityCatalog(
    val mode: RvRouteMode,
    val node: RvMainNode?,
    val nodes: List<RvNode>,
    val subscriptions: List<RvSubscription>,
    val rules: List<RvActivityRule>?,
    val cards: RvActivityCards,
    val allFailed: Boolean,
    val hasSubscriptions: Boolean,
    val mapped: Boolean,
)

/**
 * One catalog's nodes, mapped once per catalog revision and node read rather than on each usage read: every node by id
 * ([byId], built-ins too, so a group that selects `direct` still names it), the user's nodes ([nodes]: no built-in),
 * and whether each user node failed its test ([allFailed]).
 */
internal class ActivityNodes(all: List<RvNode>, val allFailed: Boolean) {
    val byId: Map<String, RvNode> = all.associateBy { it.id }
    val nodes = all.filter { it.subscriptionId != null }
}

/**
 * Every node of [profile] in honk's order, in a group or not, placed by its name ([places]), and whether honk found
 * each user node unavailable at its last probe ([ActivityNodes.allFailed]).
 */
internal fun honkActivityNodes(profile: HonkProfile?, places: NodePlaces): ActivityNodes {
    val leaves = profile?.nodes.orEmpty()
    val providers = profile?.providers.orEmpty().associateBy { it.id }
    val allFailed = leaves.any { it.providerId != null } && leaves.all { it.providerId == null || it.unavailable() }
    return ActivityNodes(leaves.map { rvNode(it, providers, places) }, allFailed)
}

/**
 * Maps [data] to 活動's lists over [profileNodes], the same profile's nodes. The main node is what the main group's TCP
 * selection ends on (the group the rules fall back to), none in Direct. honk keeps no usage by the day, so 今日用量 has
 * no bytes and yesterday none by now.
 */
internal fun activityCatalog(data: ActivityData, profileNodes: ActivityNodes): ActivityCatalog {
    val profile = data.profile
    val mode = data.mode.routeMode()
    val groups = profile?.groups.orEmpty().associateBy { it.id }
    val main = profile?.groups?.firstOrNull { it.name == profile.mainGroup }
    val byId = profileNodes.byId
    val mainLeaf = main?.runtime?.selection?.tcp?.resolvedLeafNodeId?.let(byId::get)
    val cards = RvActivityCards(
        today = null, // honk-gap:daily-usage
        nowHour = data.nowHour,
        yesterdayByNow = null,
        nowEpochMs = data.nowEpochMs,
        ruleCount = profile?.ruleCount ?: 0,
        mainNodeId = mainLeaf?.id.orEmpty(),
        node = byId::get,
        groupKind = { id -> groups[id]?.policy?.kind?.let(::rvGroupKind) },
    )
    val probe = mainLeaf?.history?.lastOrNull()
    return ActivityCatalog(
        mode = mode,
        node = mainLeaf?.takeIf { mode != RvRouteMode.Direct }?.let { RvMainNode(it.id, it.name, probe, failed = it.history.isNotEmpty() && probe == null) },
        nodes = profileNodes.nodes,
        subscriptions = profile?.providers.orEmpty().mapNotNull { if (it.kind == ProviderKind.Subscription) rvSubscription(it, userAgent = null) else null },
        rules = profile?.let { data.outbounds?.let { usage -> ruleRows(it, usage) } },
        cards = cards,
        allFailed = profileNodes.allFailed,
        // Until the profile is read nothing says there is none: the page waits rather than showing the empty state.
        hasSubscriptions = profile?.let { p -> p.providers.any { it.kind == ProviderKind.Subscription } || profileNodes.nodes.isNotEmpty() } ?: true,
        mapped = profile != null,
    )
}

/**
 * 活動's hero traffic: each way's rate now and the last minute's samples, oldest first, NaN where honk could not rate one
 * (a gap in the line). Before the first read, none.
 */
internal class ActivityTraffic(
    val upRate: Long = 0,
    val downRate: Long = 0,
    val upHistory: List<Float> = emptyList(),
    val downHistory: List<Float> = emptyList(),
)

/** honk's [traffic] now over its [history]; a read that failed (null) shows none. */
internal fun activityTraffic(traffic: RuntimeTraffic?, history: TrafficHistory?) = ActivityTraffic(
    upRate = traffic?.upRate ?: 0,
    downRate = traffic?.downRate ?: 0,
    upHistory = history?.up.orEmpty().map { it?.toFloat() ?: Float.NaN },
    downHistory = history?.down.orEmpty().map { it?.toFloat() ?: Float.NaN },
)

/**
 * 活動 as the page draws it: [catalog]'s lists under the live [status] and [traffic], with the one notice that applies
 * ([activityNotice], from the engine and the [signals] from outside it) and a failed action's [failure].
 */
internal fun activityModel(
    catalog: ActivityCatalog,
    status: Status,
    traffic: ActivityTraffic,
    crashOffered: Boolean,
    ready: Boolean,
    failure: RvActivityFailure?,
    signals: ActivitySignals = ActivitySignals(),
    readError: ErrorCode? = null,
): RvActivityUi {
    val loading = !ready || !catalog.mapped
    val state = RvActivityState(
        status = status,
        mode = catalog.mode,
        node = catalog.node,
        downRate = traffic.downRate,
        upRate = traffic.upRate,
        downHistory = traffic.downHistory,
        upHistory = traffic.upHistory,
    )
    return RvActivityUi(
        state = state,
        nodes = catalog.nodes,
        subscriptions = catalog.subscriptions,
        rules = catalog.rules,
        cards = catalog.cards,
        notice = if (loading && status !is Status.Failed) null else activityNotice(catalog.hasSubscriptions, status, crashOffered, catalog.allFailed, signals),
        failure = failure,
        readError = readError,
        loading = loading && readError == null,
        hasSubscriptions = catalog.hasSubscriptions,
    )
}

/**
 * The one notice 活動 shows, the first that applies: refused VPN consent while not connected (the user's latest
 * answer); a failed start or stopped tunnel by reason; no subscription (the empty state); no network;
 * a crash log to export; every node failed its test; last, a refused notification permission, which stops nothing.
 */
internal fun activityNotice(
    hasSubscriptions: Boolean,
    status: Status,
    crashOffered: Boolean,
    allFailed: Boolean,
    signals: ActivitySignals = ActivitySignals(),
): RvActivityNotice? = when {
    signals.vpnRefused && (status == Status.Stopped || status is Status.Failed) -> RvActivityNotice.ConsentDenied
    status is Status.Failed -> failureNotice(status)
    !hasSubscriptions -> RvActivityNotice.NoSubscription
    !signals.online -> RvActivityNotice.Offline
    crashOffered -> RvActivityNotice.Crash
    allFailed -> RvActivityNotice.NoNode
    signals.notifyRefused -> RvActivityNotice.NotifyDenied
    else -> null
}

private fun failureNotice(status: Status.Failed): RvActivityNotice = when (status.reason) {
    FailureReason.NoAllowedApps -> RvActivityNotice.StartNoApps
    FailureReason.IncompatibleCore -> RvActivityNotice.StartIncompatible
    FailureReason.StartFailed -> if (status.code == ErrorCode.NoNodes) RvActivityNotice.StartNoNodes else RvActivityNotice.StartFailed
    FailureReason.VpnNotEstablished -> RvActivityNotice.StartFailed
    FailureReason.LockdownEnabled -> RvActivityNotice.Lockdown
    FailureReason.CoreStopped -> RvActivityNotice.CoreStopped
    FailureReason.Revoked -> RvActivityNotice.VpnTaken
}

/**
 * A row per group or 直連 that carried traffic since honk started, the most first by honk's bytes per outbound. The rows
 * carry no bytes (honk counts none per rule, so the row shows [dev.doona.android.designsystem.rv.RV_NO_VALUE]: owner,
 * 2026-11-27, keep-1127). Left out: the main group (the main node's row stands for it), `block` (it reaches no outbound),
 * outbounds that carried nothing, single nodes (their bytes are counted again under their group) and groups the profile
 * no longer has.
 */
private fun ruleRows(profile: HonkProfile, outbounds: List<OutboundTraffic>): List<RvActivityRule> {
    val groups = profile.groups.associateBy { it.name }
    return outbounds.sortedByDescending { it.up + it.down }.mapNotNull { outbound ->
        when {
            outbound.up + outbound.down == 0L || outbound.name == profile.mainGroup -> null

            // honk-gap:rule-bytes
            outbound.kind == OutboundKind.Builtin -> if (outbound.name == DIRECT) RvActivityRule(null, null, null, null, null) else null

            outbound.kind == OutboundKind.Group -> groups[outbound.name]?.let(::groupRule)

            else -> null
        }
    }
}

/**
 * A rule row's words: the group's [label], or for 直連 the mode word ([direct]), over [line], the node or nodes it ends
 * on (also [direct] when it ends on none). A row never repeats its title as its subtitle (a rule that sends to 直連 is
 * 直連 over nothing), so [line] is dropped when it reads the same as the title.
 */
internal fun ruleRowText(label: String?, line: String, direct: String): RuleRowText {
    val title = label ?: direct
    return RuleRowText(title, line.takeUnless { it == title })
}

internal data class RuleRowText(val title: String, val subtitle: String?)

/** What leads a rule row ([ruleLead]). */
internal enum class RuleLead { Direct, GroupGlyph, Flag }

/**
 * A group's glyph stands for unknown nodes or two different places; a single known leaf supplies the flag.
 */
internal fun ruleLead(groupId: String?, node: RvNode?, udp: RvNode?): RuleLead = when {
    groupId == null -> RuleLead.Direct
    node == null && udp == null -> RuleLead.GroupGlyph
    node != null && udp != null && udp.place != node.place -> RuleLead.GroupGlyph
    else -> RuleLead.Flag
}

/** A group's row: its TCP node, and its UDP node when that is another one, as honk resolves each selection. */
private fun groupRule(group: Group): RvActivityRule {
    // honk-gap:rule-bytes
    val tcp = group.runtime.selection.tcp?.resolvedLeafNodeId
    val udp = group.runtime.selection.udp?.resolvedLeafNodeId
    return RvActivityRule(group.name, group.id, tcp, null, udp?.takeIf { it != tcp })
}

/** Whether honk found [this] unavailable at its last probe over TCP (or over any network when it has no TCP one). */
private fun Node.unavailable(): Boolean = latestProbe?.state == HealthState.Unavailable

private const val DIRECT = "direct"
