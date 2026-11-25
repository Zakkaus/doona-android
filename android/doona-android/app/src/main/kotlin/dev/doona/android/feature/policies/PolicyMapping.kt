package dev.doona.android.feature.policies

import dev.doona.android.designsystem.rv.RvPlace
import dev.doona.android.engine.Catalog
import dev.doona.android.engine.Group
import dev.doona.android.engine.GroupKind
import dev.doona.android.engine.Member
import dev.doona.android.engine.Mode
import dev.doona.android.engine.NodeInfo
import dev.doona.android.engine.RuleKind
import dev.doona.android.engine.RuleRow
import dev.doona.android.engine.RuleTarget
import dev.doona.android.engine.Subscription
import dev.doona.android.engine.SubscriptionFormat
import dev.doona.android.feature.leafOf
import dev.doona.android.feature.model.RV_TESTS
import dev.doona.android.feature.model.RvCity
import dev.doona.android.feature.model.RvGroupKind
import dev.doona.android.feature.model.RvLabel
import dev.doona.android.feature.model.RvMatchKind
import dev.doona.android.feature.model.RvNode
import dev.doona.android.feature.model.RvPolicyGroup
import dev.doona.android.feature.model.RvPolicyRule
import dev.doona.android.feature.model.RvRouteMode
import dev.doona.android.feature.model.RvRuleMatch
import dev.doona.android.feature.model.RvRuleState
import dev.doona.android.feature.model.RvSubFormat
import dev.doona.android.feature.model.RvSubscription
import java.net.URI

/** Where a node's name places it: its country or region, its city when the name gives one, and its short tile name. */
internal data class RvNodePlace(val place: RvPlace, val city: RvCity?, val short: String)

/**
 * Places a node by its name. The real mapper reads the core's region table on 節點 (P3c); this seam lets the policy pages
 * take it once it is wired, and until then they place every node under 其他 with its full name as the short one.
 */
internal fun interface RvNodePlaces {
    fun of(name: String): RvNodePlace
}

/** Every node under 其他, its full name standing for the short one: what the pages show before the real mapper is wired. */
internal val RV_UNKNOWN_PLACES = RvNodePlaces { RvNodePlace(RvPlace.Other, null, it) }

/** How many rules 策略's card lists: as many as the approved sample's card (`RvPolicyData.rules` in the debug samples). */
internal const val RV_POLICY_CARD_RULES = 5

/**
 * A node as the pages show it. The history is the core's last tests, oldest first; a node the core has no details for
 * shows the catalog's last probe alone, so a probed node never reads as untested. [subscription] is the subscription's
 * name, which the pages group nodes by, and empty for a static node.
 */
internal fun rvNode(member: Member, info: NodeInfo?, subscription: String, places: RvNodePlaces): RvNode {
    val placed = places.of(member.name)
    val history = when {
        info != null -> info.tests.map { it.ms }.takeLast(RV_TESTS)
        member.latencyMs != null || member.failed -> listOf(member.latencyMs)
        else -> emptyList()
    }
    return RvNode(
        id = member.id,
        name = member.name,
        short = placed.short,
        place = placed.place,
        city = placed.city,
        protocol = info?.protocol.orEmpty(),
        host = info?.server.orEmpty(),
        port = info?.port ?: 0,
        transport = info?.transport.orEmpty(),
        tls = info?.tls ?: false,
        subscription = subscription,
        history = history,
    )
}

/** The pages know two automatic kinds as one: url-test and fallback both pick for the user, so both read as 自動. */
internal fun rvGroupKind(kind: GroupKind): RvGroupKind = when (kind) {
    GroupKind.Selector -> RvGroupKind.Select
    GroupKind.UrlTest, GroupKind.Fallback -> RvGroupKind.Auto
    GroupKind.Score -> RvGroupKind.Score
}

internal fun rvRouteMode(mode: Mode): RvRouteMode = when (mode) {
    Mode.Rule -> RvRouteMode.Rule
    Mode.Global -> RvRouteMode.Global
    Mode.Direct -> RvRouteMode.Direct
}

internal fun engineMode(mode: RvRouteMode): Mode = when (mode) {
    RvRouteMode.Rule -> Mode.Rule
    RvRouteMode.Global -> Mode.Global
    RvRouteMode.Direct -> Mode.Direct
}

/**
 * A subscription as the pages show it. A missing quota is a total of 0, which [RvSubscription.hasQuota] reads as none, and
 * a subscription never fetched was refreshed at 0. Only the URL's host is kept: the rest of a link can carry its token.
 * A format not known yet reads as a list of links, the format the core falls back to.
 */
internal fun rvSubscription(subscription: Subscription): RvSubscription = RvSubscription(
    id = subscription.id,
    name = subscription.name,
    nodes = subscription.nodes,
    updatedAtEpochMs = subscription.updatedAtEpochMs ?: 0L,
    usedBytes = subscription.usedBytes ?: 0L,
    totalBytes = subscription.totalBytes ?: 0L,
    expiresAtEpochMs = subscription.expiresAtEpochMs,
    host = runCatching { URI(subscription.url).host }.getOrNull().orEmpty(),
    format = when (subscription.format) {
        SubscriptionFormat.Clash -> RvSubFormat.Clash
        SubscriptionFormat.SingBox -> RvSubFormat.SingBox
        SubscriptionFormat.Sip008 -> RvSubFormat.Sip008
        SubscriptionFormat.ShareLinks, SubscriptionFormat.Base64, null -> RvSubFormat.Links
    },
    userAgent = subscription.userAgent,
)

/**
 * The nodes [group] can carry traffic through, in the group's order: a node member as itself and a nested group as the
 * node its selection ends on, each node once. [groups] is every group by id.
 */
internal fun leafNodes(group: Group, groups: Map<String, Group>): List<Member> =
    group.members.mapNotNull { leafOf(it, groups) }.distinctBy { it.id }

/** The node a selection of [group] ends on: the selected member's leaf, or null when the selection names no member. */
internal fun selectedLeaf(group: Group, selectedId: String?, groups: Map<String, Group>): Member? =
    group.members.firstOrNull { it.id == selectedId }?.let { leafOf(it, groups) }

/**
 * The member of [group] to select so that it carries [nodeId]: the node itself when it is a member, else the first nested
 * group whose selection ends on it. The pages list nested groups by their leaf node, so a tap names a node that may only
 * be reachable through a group; selecting the node itself there would name no member.
 */
internal fun memberFor(group: Group, nodeId: String, groups: Map<String, Group>): String =
    group.members.firstOrNull { it.id == nodeId }?.id
        ?: group.members.firstOrNull { it.isGroup && leafOf(it, groups)?.id == nodeId }?.id
        ?: nodeId

/**
 * A group as the pages show it, its name as the profile gives it. Its members are [leafNodes]; it uses the node its TCP
 * selection ends on, or its first member when that is unknown, since [RvPolicyGroup] requires a member. Null when the
 * group leads to no node at all, which the pages cannot draw. [nodes] holds every node by id.
 */
internal fun rvPolicyGroup(group: Group, groups: Map<String, Group>, nodes: Map<String, RvNode>): RvPolicyGroup? {
    val members = leafNodes(group, groups).mapNotNull { nodes[it.id] }
    if (members.isEmpty()) return null
    val selected = selectedLeaf(group, group.tcp.selectedId, groups)?.id?.takeIf { id -> members.any { it.id == id } } ?: members.first().id
    return RvPolicyGroup(group.id, RvLabel.Text(group.name), rvGroupKind(group.kind), members, selected)
}

/**
 * What a rule row matches, as the pages say it. The core writes the private ranges as GeoIP `private` (as
 * [dev.doona.android.engine.fake.RvSampleRules] does), so that, not an IP range, is 區域網路; a single range stays a range,
 * since one range cannot be told from the private set. Ports, networks and kinds the core does not compile have no match
 * of their own on the pages yet, so they show their value (or their kind's name) as a full value.
 */
internal fun rvRuleMatch(row: RuleRow): RvRuleMatch {
    val sample = row.sample.orEmpty()
    if (row.kindName.equals(GEOSITE, ignoreCase = true)) return RvRuleMatch.GeoSite(sample)
    return when (row.kind) {
        RuleKind.Match -> RvRuleMatch.Rest
        RuleKind.GeoIp -> if (sample.equals(PRIVATE, ignoreCase = true)) RvRuleMatch.Lan else RvRuleMatch.GeoIp(sample.uppercase())
        RuleKind.Domain -> if (row.count > 1) RvRuleMatch.Domains(sample, row.count) else RvRuleMatch.Value(RvMatchKind.Full, sample)
        RuleKind.DomainSuffix -> if (row.count > 1) RvRuleMatch.Domains(sample, row.count) else RvRuleMatch.Value(RvMatchKind.Suffix, sample)
        RuleKind.DomainKeyword -> RvRuleMatch.Value(RvMatchKind.Keyword, sample)
        RuleKind.IpCidr -> RvRuleMatch.Value(RvMatchKind.Cidr, sample)
        RuleKind.DstPort, RuleKind.Network, null -> RvRuleMatch.Value(RvMatchKind.Full, row.sample ?: row.kindName)
    }
}

/**
 * A rule as 策略 lists it, with [bytes] routed today. Null for a row that refuses its flows: [RvPolicyRule] has no place
 * for 拒絕, and its null group means 直連, so showing the row would say the opposite of what it does. A group the pages
 * cannot draw ([groups] lacks it) also shows as null. All of a row's lines skipped is Skipped; part of them still routes.
 */
internal fun rvPolicyRule(row: RuleRow, groups: Map<String, RvPolicyGroup>, bytes: Long): RvPolicyRule? {
    val group = when (val target = row.target) {
        RuleTarget.Reject -> return null
        RuleTarget.Direct -> null
        is RuleTarget.Group -> groups[target.id]
    }
    val state = if (row.skipped != null && row.skippedLines == row.count) RvRuleState.Skipped else RvRuleState.On
    return RvPolicyRule(rvRuleMatch(row), group, bytes, state)
}

/**
 * What 策略 shows of the profile, as `RvPolicyProfile` (debug, rv/policy) holds it; the page move replaces this holder with
 * RvPolicyProfile. [rejectedRules] counts the card's rows left out because they refuse their flows, which RvPolicyProfile
 * has no place for yet.
 */
internal data class PolicyProfileData(
    val main: RvPolicyGroup,
    val groups: List<RvPolicyGroup>,
    val rules: List<RvPolicyRule>,
    val ruleCount: Int,
    val nodes: List<RvNode>,
    val subscriptions: List<RvSubscription>,
    val nowEpochMs: Long,
    val rejectedRules: Int = 0,
)

/**
 * Every node member of [catalog]'s groups once, in catalog order, mapped with its details from [infos] and its
 * subscription's name.
 */
internal fun rvNodes(catalog: Catalog, infos: Map<String, NodeInfo>, places: RvNodePlaces): Map<String, RvNode> {
    val names = catalog.subscriptions.associate { it.id to it.name }
    return catalog.groups.asSequence().flatMap { it.members }.filterNot { it.isGroup }.distinctBy { it.id }
        .associate { it.id to rvNode(it, infos[it.id], it.subscriptionId?.let(names::get).orEmpty(), places) }
}

/**
 * 策略's profile from the catalog, the nodes' details [infos], the card's [rules] (the source has [ruleTotal]) and today's
 * bytes per rule id [byRule]. Null when the main group cannot be drawn. The card lists the first [RV_POLICY_CARD_RULES]
 * rows that do not refuse their flows; the other groups keep the catalog's order.
 */
internal fun rvPolicyProfile(
    catalog: Catalog,
    infos: Map<String, NodeInfo>,
    rules: List<RuleRow>,
    ruleTotal: Int,
    byRule: Map<String, Long>,
    nowEpochMs: Long,
    places: RvNodePlaces,
): PolicyProfileData? {
    val nodes = rvNodes(catalog, infos, places)
    val byId = catalog.groups.associateBy { it.id }
    val mapped = catalog.groups.mapNotNull { rvPolicyGroup(it, byId, nodes) }.associateBy { it.id }
    val main = mapped[catalog.mainGroupId] ?: return null
    val (rejected, routed) = rules.partition { it.target == RuleTarget.Reject }
    return PolicyProfileData(
        main = main,
        groups = mapped.values.filter { it.id != main.id },
        rules = routed.take(RV_POLICY_CARD_RULES).mapNotNull { rvPolicyRule(it, mapped, byRule[it.id] ?: 0L) },
        ruleCount = ruleTotal,
        nodes = nodes.values.toList(),
        subscriptions = catalog.subscriptions.map(::rvSubscription),
        nowEpochMs = nowEpochMs,
        rejectedRules = rejected.size,
    )
}

private const val GEOSITE = "GEOSITE"
private const val PRIVATE = "private"
