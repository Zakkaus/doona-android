package dev.doona.android.feature.policies

import dev.doona.android.engine.Group
import dev.doona.android.engine.Subscription
import dev.doona.android.feature.model.NodePlaces
import dev.doona.android.feature.model.RvNode
import dev.doona.android.feature.model.RvPolicyGroup
import dev.doona.android.feature.model.RvPolicyRule
import dev.doona.android.feature.nodes.rvNode
import dev.doona.android.feature.nodes.rvSource
import dev.doona.android.vpn.HonkRules
import dev.doona.android.vpn.NativeApi
import dev.doona.android.vpn.NativeCatalog
import dev.doona.android.vpn.NativeRouting

/**
 * honk's groups (in configuration order), subscriptions and running rules, as 策略, 全部規則 and 生效設定 draw them.
 * Blocking: read it on `Dispatchers.IO`.
 */
internal class HonkPolicyRead(val groups: List<Group>, val subscriptions: List<Subscription>, val rules: HonkRules) {
    /** The group Rule mode's fallback sends to, which the pages call 主節點; none when the fallback is `direct` or `block`. */
    val mainGroupId: String? = groups.firstOrNull { it.name == rules.fallback.outbound }?.id

    /** Every node member of the groups once, in group order, with its subscription; honk's node details are not read here. */
    fun nodes(places: NodePlaces): Map<String, RvNode> {
        val byId = subscriptions.associateBy { it.id }
        return groups.asSequence().flatMap { it.members }.filterNot { it.isGroup }.distinctBy { it.id }
            .associate { it.id to rvNode(it, null, rvSource(it, byId), places) }
    }

    /** Every group as the pages draw it, by id, in configuration order. */
    fun policyGroups(nodes: Map<String, RvNode>): Map<String, RvPolicyGroup> {
        val byId = groups.associateBy { it.id }
        return groups.associate { it.id to rvPolicyGroup(it, byId, nodes) }
    }

    /** Every rule in evaluation order, the fallback last, drawn through [groups] (by id, from [policyGroups]). */
    fun policyRules(groups: Map<String, RvPolicyGroup>): List<RvPolicyRule> {
        val byName = this.groups.mapNotNull { group -> groups[group.id]?.let { group.name to it } }.toMap()
        return rules.rules.map { rvPolicyRule(it, byName) }
    }

    companion object {
        fun read(api: NativeApi): HonkPolicyRead {
            val catalog = NativeCatalog(api)
            return HonkPolicyRead(catalog.groups(), catalog.subscriptions(), NativeRouting(api).rules())
        }
    }
}
