package dev.doona.android.feature.policies

import dev.doona.android.feature.model.NodePlaces
import dev.doona.android.feature.model.RvNode
import dev.doona.android.feature.model.RvPolicyGroup
import dev.doona.android.feature.model.RvPolicyRule
import dev.doona.android.vpn.NativeApi
import dev.doona.android.vpn.honk.Group
import dev.doona.android.vpn.honk.HonkRules
import dev.doona.android.vpn.honk.NativeCatalog
import dev.doona.android.vpn.honk.NativeRouting
import dev.doona.android.vpn.honk.Node
import dev.doona.android.vpn.honk.Provider

/**
 * honk's groups (in configuration order), nodes, providers and running rules, as 策略, 全部規則 and 生效設定 draw them.
 * Blocking: read it on `Dispatchers.IO`.
 */
internal class HonkPolicyRead(
    val groups: List<Group>,
    val nodes: List<Node>,
    val providers: List<Provider>,
    val rules: HonkRules,
) {
    /** The group Rule mode's fallback sends to, which the pages call 主節點; none when the fallback is `direct` or `block`. */
    val mainGroupId: String? = groups.firstOrNull { it.name == rules.fallback.outbound }?.id

    /** Every node honk holds, by id, with its provider's name. */
    fun nodes(places: NodePlaces): Map<String, RvNode> {
        val byId = providers.associateBy { it.id }
        return nodes.associate { it.id to rvNode(it, byId, places) }
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
            return HonkPolicyRead(catalog.groups(), catalog.nodes(), catalog.providers(), NativeRouting(api).rules())
        }
    }
}
