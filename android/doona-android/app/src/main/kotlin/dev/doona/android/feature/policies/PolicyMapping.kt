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
import dev.doona.android.engine.RuleSource
import dev.doona.android.engine.RuleTarget
import dev.doona.android.engine.Subscription
import dev.doona.android.feature.leafOf
import dev.doona.android.feature.model.RV_TESTS
import dev.doona.android.feature.model.RvCity
import dev.doona.android.feature.model.RvGroupKind
import dev.doona.android.feature.model.RvLabel
import dev.doona.android.feature.model.RvMatchKind
import dev.doona.android.feature.model.RvMemberGroup
import dev.doona.android.feature.model.RvNode
import dev.doona.android.feature.model.RvPolicyGroup
import dev.doona.android.feature.model.RvPolicyRule
import dev.doona.android.feature.model.RvRouteMode
import dev.doona.android.feature.model.RvRuleEnd
import dev.doona.android.feature.model.RvRuleMatch
import dev.doona.android.feature.model.RvRuleSource
import dev.doona.android.feature.model.RvRuleState
import dev.doona.android.feature.model.protocolName
import dev.doona.android.feature.model.rvTemplate
import dev.doona.android.feature.subscriptions.rvSubscription

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

/** How many rules 策略's card lists: as many as the approved sample's card lists. */
internal const val RV_POLICY_CARD_RULES = 5

/**
 * A node as the pages show it. The history is the core's last tests, oldest first; a node the core has no details for
 * shows the catalog's last probe alone, so a probed node never reads as untested. [subscription] is the subscription's
 * name, empty for a node added by hand; the member's subscription id, null for such a node, is what the pages group by.
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
        protocol = protocolName(info?.protocol.orEmpty()),
        host = info?.server.orEmpty(),
        port = info?.port ?: 0,
        transport = info?.transport.orEmpty(),
        tls = info?.tls ?: false,
        subscription = subscription,
        subscriptionId = member.subscriptionId,
        history = history,
    )
}

internal fun rvGroupKind(kind: GroupKind): RvGroupKind = when (kind) {
    GroupKind.Selector -> RvGroupKind.Select
    GroupKind.UrlTest, GroupKind.LoadBalance -> RvGroupKind.Auto
    GroupKind.Fallback -> RvGroupKind.Fallback
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
 * The nodes [group] can carry traffic through, in the group's order: a node member as itself and a nested group as the
 * node its selection ends on, each node once. [groups] is every group by id.
 */
internal fun leafNodes(group: Group, groups: Map<String, Group>): List<Member> =
    group.members.mapNotNull { leafOf(it, groups) }.distinctBy { it.id }

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
 * A group as the pages show it, its name as the profile gives it: its node members, then the groups among its members,
 * each with the node its choice ends on (a member naming no group in [groups] is left out). It uses the member its TCP
 * selection names, or its first member when that names none. A group with no member at all is drawn too: honk allows it.
 * [nodes] holds every node by id.
 */
internal fun rvPolicyGroup(group: Group, groups: Map<String, Group>, nodes: Map<String, RvNode>): RvPolicyGroup {
    val members = group.members.filterNot { it.isGroup }.mapNotNull { nodes[it.id] }.distinctBy { it.id }
    val nested = group.members.filter { it.isGroup }.distinctBy { it.id }.mapNotNull { member ->
        groups[member.id]?.let { RvMemberGroup(it.id, RvLabel.Text(it.name), rvGroupKind(it.kind), leafOf(member, groups)?.let { leaf -> nodes[leaf.id] }) }
    }
    val ids = members.map { it.id } + nested.map { it.id }
    val selected = group.tcp.selectedId?.takeIf { it in ids } ?: ids.firstOrNull().orEmpty()
    return RvPolicyGroup(group.id, RvLabel.Text(group.name), rvGroupKind(group.kind), members, selected, nested)
}

/**
 * What a rule row matches, as the pages say it. The core writes the private ranges as GeoIP `private` (as
 * `engine.fake.RvSampleRules` does), so that, not an IP range, is 區域網路; a single range stays a range,
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
 * A rule as 策略 lists it, with [bytes] routed today. A row that refuses its flows is 拒絕, and one whose group is not in
 * [groups] names its group as missing: neither is 直連. All of a row's lines skipped is Skipped; part of them still routes.
 */
internal fun rvPolicyRule(row: RuleRow, groups: Map<String, RvPolicyGroup>, bytes: Long): RvPolicyRule {
    val state = if (row.skipped != null && row.skippedLines == row.count) RvRuleState.Skipped else RvRuleState.On
    val match = rvRuleMatch(row)
    return when (val target = row.target) {
        RuleTarget.Direct -> RvPolicyRule(match, null, bytes, state)

        RuleTarget.Reject -> RvPolicyRule(match, null, bytes, state, RvRuleEnd.Reject)

        is RuleTarget.Group -> groups[target.id]?.let { RvPolicyRule(match, it, bytes, state) }
            ?: RvPolicyRule(match, null, bytes, state, RvRuleEnd.Missing)
    }
}

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
 * bytes per rule id [byRule]. The main group is null when no group has the catalog's main id: honk needs no such group.
 * The card lists the first [RV_POLICY_CARD_RULES] rows, 拒絕 among them, and [ruleTotal] counts every row; the other
 * groups keep the catalog's order.
 */
internal fun rvPolicyProfile(
    catalog: Catalog,
    infos: Map<String, NodeInfo>,
    rules: List<RuleRow>,
    ruleTotal: Int,
    byRule: Map<String, Long>,
    nowEpochMs: Long,
    places: RvNodePlaces,
): RvPolicyProfile {
    val nodes = rvNodes(catalog, infos, places)
    val byId = catalog.groups.associateBy { it.id }
    val mapped = catalog.groups.map { rvPolicyGroup(it, byId, nodes) }.associateBy { it.id }
    val main = mapped[catalog.mainGroupId]
    return RvPolicyProfile(
        main = main,
        groups = mapped.values.filter { it.id != main?.id },
        rules = rules.take(RV_POLICY_CARD_RULES).map { rvPolicyRule(it, mapped, byRule[it.id] ?: 0L) },
        ruleCount = ruleTotal,
        nodes = nodes.values.toList(),
        subscriptions = catalog.subscriptions.map(::rvSubscription),
        nowEpochMs = nowEpochMs,
        source = rvRuleSource(catalog.ruleSource, catalog.subscriptions),
    )
}

/**
 * Where [source]'s rules come from: its template, the subscription of its id from [subscriptions] by name, or else the
 * configuration file.
 */
internal fun rvRuleSource(source: RuleSource, subscriptions: List<Subscription>): RvRuleSource {
    source.template?.let { id -> return RvRuleSource.Template(rvTemplate(id)) }
    val subscription = source.subscriptionId ?: return RvRuleSource.File
    return RvRuleSource.Subscription(subscriptions.firstOrNull { it.id == subscription }?.name ?: subscription)
}

private const val GEOSITE = "GEOSITE"
private const val PRIVATE = "private"
