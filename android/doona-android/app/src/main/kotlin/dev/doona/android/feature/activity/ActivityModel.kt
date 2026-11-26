package dev.doona.android.feature.activity

import dev.doona.android.designsystem.rv.RvPlace
import dev.doona.android.engine.Catalog
import dev.doona.android.engine.ErrorCode
import dev.doona.android.engine.FailureReason
import dev.doona.android.engine.Member
import dev.doona.android.engine.Mode
import dev.doona.android.engine.NodeInfo
import dev.doona.android.engine.Status
import dev.doona.android.feature.model.NodePlaces
import dev.doona.android.feature.model.RV_TESTS
import dev.doona.android.feature.model.RvDay
import dev.doona.android.feature.model.RvNode
import dev.doona.android.feature.model.RvRouteMode
import dev.doona.android.feature.model.RvSubscription
import dev.doona.android.feature.model.protocolName
import dev.doona.android.feature.model.rvLeafMembers
import dev.doona.android.feature.model.rvNode
import dev.doona.android.feature.policies.rvGroupKind
import dev.doona.android.feature.subscriptions.rvSubscription
import dev.doona.android.vpn.honk.Group
import dev.doona.android.vpn.honk.HealthState
import dev.doona.android.vpn.honk.MemberKind
import dev.doona.android.vpn.honk.Node
import dev.doona.android.vpn.honk.OutboundKind
import dev.doona.android.vpn.honk.OutboundTraffic
import dev.doona.android.vpn.honk.Provider
import dev.doona.android.vpn.honk.RuntimeTraffic
import dev.doona.android.vpn.honk.TrafficHistory
import dev.doona.android.vpn.honk.Transport

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
 * whether every node failed its test ([allFailed]) and whether a subscription exists. [mapped] is false only for the
 * stand-in before the first mapping, which the page shows as loading.
 */
internal class ActivityCatalog(
    val mode: RvRouteMode,
    val node: RvMainNode?,
    val nodes: List<RvNode>,
    val subscriptions: List<RvSubscription>,
    val rules: List<RvActivityRule>,
    val cards: RvActivityCards,
    val allFailed: Boolean,
    val hasSubscriptions: Boolean,
    val mapped: Boolean = true,
)

/**
 * One catalog's nodes, mapped once per catalog revision and node read rather than on each usage read: every leaf
 * member of any group, once ([nodes], and [byId]), and whether each failed its test ([allFailed]).
 */
internal class ActivityNodes(val nodes: List<RvNode>, val allFailed: Boolean) {
    val byId: Map<String, RvNode> = nodes.associateBy { it.id }
}

/**
 * [catalog]'s nodes, each with its endpoint and tests from [nodeInfo] or, before they are read, its member's last
 * result, and its place: the region the core reads from its name ([Member.region]), else [RvPlace.Other].
 */
internal fun activityNodes(catalog: Catalog?, nodeInfo: Map<String, NodeInfo>): ActivityNodes {
    val subscriptionNames = catalog?.subscriptions.orEmpty().associate { it.id to it.name }
    val members = catalog?.let(::rvLeafMembers).orEmpty()
    return ActivityNodes(members.map { rvNode(it, nodeInfo[it.id], subscriptionNames) }, allFailed = members.isNotEmpty() && members.all { it.failed })
}

/**
 * Every node of [profile] some group has as a member, once, in the groups' order, placed by its name ([places]), and
 * whether honk found each unavailable at its last probe ([ActivityNodes.allFailed]).
 */
internal fun honkActivityNodes(profile: HonkProfile?, places: NodePlaces): ActivityNodes {
    val byId = profile?.nodes.orEmpty().associateBy { it.id }
    val leaves = profile?.groups.orEmpty().asSequence().flatMap { it.members }.filter { it.kind == MemberKind.Node }.mapNotNull { byId[it.id] }.distinctBy { it.id }.toList()
    val providers = profile?.providers.orEmpty().associateBy { it.id }
    return ActivityNodes(leaves.map { rvNode(it, providers, places) }, allFailed = leaves.isNotEmpty() && leaves.all { it.unavailable() })
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
        today = rvDay(data.nowHour),
        nowHour = data.nowHour,
        yesterdayByNow = 0L,
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
        subscriptions = profile?.providers.orEmpty().map { rvSubscription(it, userAgent = null) },
        rules = profile?.let { ruleRows(it, data.outbounds.orEmpty()) }.orEmpty(),
        cards = cards,
        allFailed = profileNodes.allFailed,
        // Until the profile is read nothing says there is none: the page waits rather than showing the empty state.
        hasSubscriptions = profile?.let { it.providers.isNotEmpty() || it.nodes.isNotEmpty() } ?: true,
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
        notice = if (loading) null else activityNotice(catalog.hasSubscriptions, status, crashOffered, catalog.allFailed, signals),
        failure = failure,
        loading = loading,
        hasSubscriptions = catalog.hasSubscriptions,
    )
}

/**
 * The one notice 活動 shows, the first that applies: no subscription (the page's empty state); a refused VPN consent
 * while not connected (the user's latest answer); a failed start or a stopped tunnel by its reason (a start the system
 * refused because another app keeps the VPN locked down is its own notice: connecting again cannot help); no network;
 * a crash log to export; every node failed its test; last, a refused notification permission, which stops nothing.
 */
internal fun activityNotice(
    hasSubscriptions: Boolean,
    status: Status,
    crashOffered: Boolean,
    allFailed: Boolean,
    signals: ActivitySignals = ActivitySignals(),
): RvActivityNotice? = when {
    !hasSubscriptions -> RvActivityNotice.NoSubscription
    signals.vpnRefused && (status == Status.Stopped || status is Status.Failed) -> RvActivityNotice.ConsentDenied
    status is Status.Failed -> failureNotice(status)
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
 * The bytes each outbound carried since honk started, a row per group or 直連, the most first. Left out: the main group
 * (the main node's row stands for it), `block` (it reaches no outbound), outbounds that carried nothing, single nodes
 * (their bytes are counted again under their group) and groups the profile no longer has.
 */
private fun ruleRows(profile: HonkProfile, outbounds: List<OutboundTraffic>): List<RvActivityRule> {
    val groups = profile.groups.associateBy { it.name }
    return outbounds.mapNotNull { outbound ->
        val bytes = outbound.up + outbound.down
        when {
            bytes == 0L || outbound.name == profile.mainGroup -> null
            outbound.kind == OutboundKind.Builtin -> if (outbound.name == DIRECT) RvActivityRule(null, null, null, bytes, null) else null
            outbound.kind == OutboundKind.Group -> groups[outbound.name]?.let { groupRule(it, bytes) }
            else -> null
        }
    }.sortedByDescending { it.bytes }
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
 * What leads a rule row that ends on [node] (TCP) and [udp] (its UDP node, when that is another one): 直連's arrow when
 * it ends on no node; the group's glyph when the two nodes are in different places, since no one flag stands for both;
 * else the node's flag.
 */
internal fun ruleLead(node: RvNode?, udp: RvNode?): RuleLead = when {
    node == null -> RuleLead.Direct
    udp != null && udp.place != node.place -> RuleLead.GroupGlyph
    else -> RuleLead.Flag
}

/** A group's row: its TCP node, and its UDP node when that is another one, as honk resolves each selection. */
private fun groupRule(group: Group, bytes: Long): RvActivityRule {
    val tcp = group.runtime.selection.tcp?.resolvedLeafNodeId
    val udp = group.runtime.selection.udp?.resolvedLeafNodeId
    return RvActivityRule(group.name, group.id, tcp, bytes, udp?.takeIf { it != tcp })
}

/** Whether honk found [this] unavailable at its last probe over TCP (or over any network when it has no TCP one). */
private fun Node.unavailable(): Boolean = (health.firstOrNull { it.transport == Transport.Tcp } ?: health.firstOrNull())?.state == HealthState.Unavailable

private fun rvNode(member: Member, info: NodeInfo?, subscriptions: Map<String, String>): RvNode {
    val history = info?.tests?.map { it.ms }?.takeLast(RV_TESTS)?.takeIf { it.isNotEmpty() } ?: memberHistory(member)
    return RvNode(
        id = member.id,
        name = member.name,
        // The core gives no short name or city yet; the narrow tiles show the whole name.
        short = member.name,
        place = member.region?.let(::placeOfRegion) ?: RvPlace.Other,
        city = null,
        protocol = protocolName(info?.protocol.orEmpty()),
        host = info?.server.orEmpty(),
        port = info?.port ?: 0,
        transport = info?.transport.orEmpty(),
        tls = info?.tls ?: false,
        subscription = member.subscriptionId?.let(subscriptions::get).orEmpty(),
        subscriptionId = member.subscriptionId.orEmpty(),
        history = history,
        shareable = false,
    )
}

/** The place whose ISO code is [code]; null for a code the place table does not list. */
internal fun placeOfRegion(code: String): RvPlace? = RvPlace.entries.firstOrNull { it.flag.equals(code, ignoreCase = true) }

/** A member's last result as a one-test history: its latency, a timeout when it failed, nothing when never tested. */
private fun memberHistory(member: Member): List<Int?> = when {
    member.latencyMs != null -> listOf(member.latencyMs)
    member.failed -> listOf(null)
    else -> emptyList()
}

/** Today by the hour up to the current one, [nowHour]'s: honk keeps no usage by the day, so every hour has no bytes. */
private fun rvDay(nowHour: Float): RvDay {
    val hours = (nowHour.toInt() + 1).coerceIn(1, HOURS)
    return RvDay(List(hours) { 0L }, List(hours) { 0L }, null)
}

private const val DIRECT = "direct"

private const val HOURS = 24
