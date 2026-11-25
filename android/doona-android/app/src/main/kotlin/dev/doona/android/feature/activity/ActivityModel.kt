package dev.doona.android.feature.activity

import dev.doona.android.designsystem.rv.RvPlace
import dev.doona.android.engine.Catalog
import dev.doona.android.engine.ErrorCode
import dev.doona.android.engine.FailureReason
import dev.doona.android.engine.Group
import dev.doona.android.engine.GroupKind
import dev.doona.android.engine.Member
import dev.doona.android.engine.NodeInfo
import dev.doona.android.engine.RuleBytes
import dev.doona.android.engine.RuleRow
import dev.doona.android.engine.RuleTarget
import dev.doona.android.engine.Settings
import dev.doona.android.engine.Status
import dev.doona.android.engine.Subscription
import dev.doona.android.engine.SubscriptionFormat
import dev.doona.android.engine.Traffic
import dev.doona.android.engine.Usage
import dev.doona.android.feature.leafOf
import dev.doona.android.feature.model.RV_TESTS
import dev.doona.android.feature.model.RvDay
import dev.doona.android.feature.model.RvGroupKind
import dev.doona.android.feature.model.RvNode
import dev.doona.android.feature.model.RvRouteMode
import dev.doona.android.feature.model.RvSubFormat
import dev.doona.android.feature.model.RvSubscription
import java.net.URI

/**
 * What 活動's lists are mapped from, read far less often than the second's traffic: the core's [catalog] (null until
 * read; [settings] stand in for its mode until then), today's [usage] (null until the first read), the rule rows its
 * `byRule` ids resolve to ([rules], by id), yesterday's bytes by now, and now.
 */
internal class ActivityData(
    val catalog: Catalog?,
    val settings: Settings,
    val usage: Usage?,
    val rules: Map<String, RuleRow>,
    val yesterdayByNow: Long,
    val nowEpochMs: Long,
    val nowHour: Float,
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
    val members = catalog?.groups.orEmpty().flatMap { it.members }.filterNot { it.isGroup }.distinctBy { it.id }
    return ActivityNodes(members.map { rvNode(it, nodeInfo[it.id], subscriptionNames) }, allFailed = members.isNotEmpty() && members.all { it.failed })
}

/**
 * Maps [data] to 活動's lists over [catalogNodes], the same catalog's nodes. The main node is what the main group's TCP
 * selection ends on (`catalog.mainGroupId`, not the first group), none in Direct.
 */
internal fun activityCatalog(data: ActivityData, catalogNodes: ActivityNodes): ActivityCatalog {
    val catalog = data.catalog
    val mode = (catalog?.mode ?: data.settings.mode).routeMode()
    val groups = catalog?.groups.orEmpty().associateBy { it.id }
    val main = catalog?.let { groups[it.mainGroupId] }
    val mainLeaf = main?.let { selectedLeaf(it, it.tcp.selectedId, groups) }
    val byId = catalogNodes.byId
    val cards = RvActivityCards(
        today = rvDay(data.usage, data.nowHour),
        nowHour = data.nowHour,
        yesterdayByNow = data.yesterdayByNow,
        nowEpochMs = data.nowEpochMs,
        ruleCount = catalog?.ruleSource?.rules ?: 0,
        mainNodeId = mainLeaf?.id.orEmpty(),
        node = byId::get,
        groupKind = { id -> groups[id]?.kind?.rvKind() },
    )
    return ActivityCatalog(
        mode = mode,
        node = mainLeaf?.takeIf { mode != RvRouteMode.Direct }?.let { RvMainNode(it.id, it.name, it.latencyMs, it.failed) },
        nodes = catalogNodes.nodes,
        subscriptions = catalog?.subscriptions.orEmpty().map(::rvSubscription),
        rules = catalog?.let { ruleRows(it, data.usage?.today?.byRule.orEmpty(), data.rules, groups) }.orEmpty(),
        cards = cards,
        allFailed = catalogNodes.allFailed,
        // Until the catalog is read nothing says there is none: the page waits rather than showing the empty state.
        hasSubscriptions = catalog?.subscriptions?.isNotEmpty() ?: true,
    )
}

/**
 * 活動 as the page draws it: [catalog]'s lists under the live [status] and [traffic], with the one notice that applies
 * ([activityNotice], from the engine and the [signals] from outside it) and a failed action's [failure].
 */
internal fun activityModel(
    catalog: ActivityCatalog,
    status: Status,
    traffic: Traffic,
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
        downHistory = traffic.downHistory.map(Long::toFloat),
        upHistory = traffic.upHistory.map(Long::toFloat),
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
 * Today's rule bytes by target: each `byRule` id joined with its rule row, the bytes both ways summed per target, the
 * most first. Left out: `unmatched` and the rules that send to the main group (the main node's row stands for them),
 * `other` (ids past the day's 64, with no row), ids whose row is gone, rejected flows (they reach no outbound) and
 * groups the catalog no longer has.
 */
private fun ruleRows(catalog: Catalog, byRule: List<RuleBytes>, rows: Map<String, RuleRow>, groups: Map<String, Group>): List<RvActivityRule> {
    val bytes = LinkedHashMap<RuleTarget, Long>()
    byRule.forEach { entry ->
        if (entry.id == UNMATCHED || entry.id == OTHER) return@forEach
        val target = rows[entry.id]?.target ?: return@forEach
        if (target == RuleTarget.Reject || target == RuleTarget.Group(catalog.mainGroupId)) return@forEach
        bytes[target] = (bytes[target] ?: 0L) + entry.up + entry.down
    }
    return bytes.mapNotNull { (target, sum) ->
        when (target) {
            RuleTarget.Direct -> RvActivityRule(null, null, null, sum, null)
            is RuleTarget.Group -> groups[target.id]?.let { groupRule(it, sum, groups) }
            RuleTarget.Reject -> null
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

/** A group's row: its TCP node, and its UDP node when that is another one. */
private fun groupRule(group: Group, bytes: Long, groups: Map<String, Group>): RvActivityRule {
    val tcp = selectedLeaf(group, group.tcp.selectedId, groups)
    val udp = selectedLeaf(group, group.udp.selectedId, groups)
    return RvActivityRule(group.name, group.id, tcp?.id, bytes, udp?.id?.takeIf { it != tcp?.id })
}

/** The node [group]'s [selected] member ends on, through any nested groups. */
private fun selectedLeaf(group: Group, selected: String?, groups: Map<String, Group>): Member? =
    group.members.firstOrNull { it.id == selected }?.let { leafOf(it, groups) }

private fun rvNode(member: Member, info: NodeInfo?, subscriptions: Map<String, String>): RvNode {
    val history = info?.tests?.map { it.ms }?.takeLast(RV_TESTS)?.takeIf { it.isNotEmpty() } ?: memberHistory(member)
    return RvNode(
        id = member.id,
        name = member.name,
        // The core gives no short name or city yet; the narrow tiles show the whole name.
        short = member.name,
        place = member.region?.let(::placeOfRegion) ?: RvPlace.Other,
        city = null,
        protocol = protocolLabel(info?.protocol.orEmpty()),
        host = info?.server.orEmpty(),
        port = info?.port ?: 0,
        transport = info?.transport.orEmpty(),
        tls = info?.tls ?: false,
        subscription = member.subscriptionId?.let(subscriptions::get).orEmpty(),
        history = history,
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

private fun rvSubscription(sub: Subscription) = RvSubscription(
    id = sub.id,
    name = sub.name,
    nodes = sub.nodes,
    updatedAtEpochMs = sub.updatedAtEpochMs ?: 0L,
    usedBytes = sub.usedBytes ?: 0L,
    totalBytes = sub.totalBytes ?: 0L,
    expiresAtEpochMs = sub.expiresAtEpochMs,
    // Only the host: the rest of a link carries its token.
    host = runCatching { URI(sub.url).host }.getOrNull().orEmpty(),
    format = when (sub.format) {
        SubscriptionFormat.Clash -> RvSubFormat.Clash
        SubscriptionFormat.SingBox -> RvSubFormat.SingBox
        SubscriptionFormat.Sip008 -> RvSubFormat.Sip008
        SubscriptionFormat.ShareLinks, SubscriptionFormat.Base64, null -> RvSubFormat.Links
    },
    userAgent = sub.userAgent,
)

/**
 * Today by the hour up to the current one, [nowHour]'s, which is still filling: the core sends all 24, and the chart
 * draws a step for each hour it is given, so the hours still to come would run its lines on past now. Before the first
 * read, a day with no bytes.
 */
private fun rvDay(usage: Usage?, nowHour: Float): RvDay {
    val hours = (nowHour.toInt() + 1).coerceIn(1, HOURS)
    val day = usage?.today ?: return RvDay(List(hours) { 0L }, List(hours) { 0L }, null)
    return RvDay(day.down.take(hours), day.up.take(hours), day.flows.take(hours).map(Long::toInt))
}

/** A fallback or load-balance group picks for itself, as a url-test does. */
private fun GroupKind.rvKind(): RvGroupKind = when (this) {
    GroupKind.Selector -> RvGroupKind.Select
    GroupKind.UrlTest, GroupKind.LoadBalance, GroupKind.Fallback -> RvGroupKind.Auto
    GroupKind.Score -> RvGroupKind.Score
}

/**
 * The rule ids today's `byRule` names that have a rule row: `unmatched` and `other` have none, so asking for them would
 * read the whole list to its end on every revision.
 */
internal fun ruleIds(usage: Usage?): List<String> = usage?.today?.byRule.orEmpty().map { it.id }.filterNot { it == UNMATCHED || it == OTHER }

/** The ids of every leaf member of the catalog's groups, once each: what `Engine.nodes` is asked for. */
internal fun leafIds(catalog: Catalog): List<String> = catalog.groups.flatMap { it.members }.filterNot { it.isGroup }.map { it.id }.distinct()

private const val UNMATCHED = "unmatched"
private const val OTHER = "other"

/**
 * A protocol as the pages name it (VLESS, Hysteria2), from the core's lower-case id; an id this list does not know is
 * shown as the core sends it.
 */
internal fun protocolLabel(id: String): String = PROTOCOL_LABELS[id] ?: id

private val PROTOCOL_LABELS = mapOf(
    "vless" to "VLESS",
    "vmess" to "VMess",
    "trojan" to "Trojan",
    "ss" to "Shadowsocks",
    "shadowsocks" to "Shadowsocks",
    "hysteria2" to "Hysteria2",
    "tuic" to "TUIC",
)

private const val HOURS = 24
