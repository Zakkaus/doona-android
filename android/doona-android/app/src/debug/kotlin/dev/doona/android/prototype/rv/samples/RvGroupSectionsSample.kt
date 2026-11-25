package dev.doona.android.prototype.rv.samples

import dev.doona.android.R
import dev.doona.android.feature.model.RvGroupKind
import dev.doona.android.feature.model.RvNode
import dev.doona.android.feature.model.RvPolicyGroup
import dev.doona.android.feature.model.RvSubscription
import dev.doona.android.prototype.rv.data.RV_HARBOR
import dev.doona.android.prototype.rv.data.RV_LANTERN
import dev.doona.android.prototype.rv.data.RvData

/**
 * The data of the nbmany v2 samples: a selector group over some of the sample nodes, split among subscriptions. [two] is
 * two subscriptions with nine nodes, [five] five with forty (eight each, the chosen node in the third).
 */
internal object RvGroupSectionsSample {
    private val names = listOf(RV_HARBOR, RV_LANTERN, "Orchard Link", "Meridian", "Tidewater")
    private val usedGb = listOf(61.7, 36.4, 12.9, 88.2, 4.1)
    private val totalGb = listOf(200.0, 100.0, 50.0, 120.0, 30.0)

    class Sample(val group: RvPolicyGroup, val subscriptions: List<RvSubscription>)

    val two: Sample = sample(RvData.nodes.take(NODES_TWO), subscriptions = 2, chosen = CHOSEN_TWO)

    val five: Sample = sample(RvData.nodes.take(NODES_FIVE).mapIndexed { i, node -> node.copy(subscription = names[i / PER_SUBSCRIPTION], subscriptionId = "s${i / PER_SUBSCRIPTION + 1}") }, subscriptions = 5, chosen = CHOSEN_FIVE)

    private fun sample(nodes: List<RvNode>, subscriptions: Int, chosen: Int): Sample {
        val group = RvPolicyGroup("sections", R.string.rv_rule_streaming, RvGroupKind.Select, nodes, nodes[chosen].id)
        val subs = names.take(subscriptions).mapIndexed { i, name ->
            RvSubscription(
                "s${i + 1}",
                name,
                nodes.count { it.subscription == name },
                RvData.subscriptions.first().updatedAtEpochMs,
                (usedGb[i] * GB).toLong(),
                (totalGb[i] * GB).toLong(),
                RvData.subscriptions.first().expiresAtEpochMs,
                "sub.${name.lowercase().replace(' ', '-')}.example",
            )
        }
        return Sample(group, subs)
    }

    private const val NODES_TWO = 9
    private const val NODES_FIVE = 40
    private const val PER_SUBSCRIPTION = 8
    private const val CHOSEN_TWO = 4
    private const val CHOSEN_FIVE = 19
    private const val GB = 1_000_000_000L
}
