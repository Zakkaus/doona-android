package dev.doona.android.prototype.rv.samples

import dev.doona.android.feature.model.RvNode
import dev.doona.android.feature.model.RvPolicyGroup
import dev.doona.android.feature.model.RvSubscription

/**
 * The data of the combined group-page samples: [RvGroupSectionsSample]'s groups with some names carrying emoji (emoji1: a
 * flag emoji, a brand's, one nothing knows), and the node each network uses while 分開選擇 TCP/UDP is on. [two] is two
 * subscriptions with nine nodes, [five] five with forty; TCP and UDP use nodes of different subscriptions.
 */
internal object RvGroupFullSample {
    class Sample(val group: RvPolicyGroup, val subscriptions: List<RvSubscription>, val tcpId: String, val udpId: String)

    val two: Sample = from(RvGroupSectionsSample.two, udp = UDP_TWO)

    val five: Sample = from(RvGroupSectionsSample.five, udp = UDP_FIVE)

    private fun from(base: RvGroupSectionsSample.Sample, udp: Int): Sample {
        val members = base.group.members.mapIndexed { i, node -> node.copy(name = named(i, node)) }
        val group = RvPolicyGroup(base.group.id, base.group.name, base.group.kind, members, base.group.selectedId)
        return Sample(group, base.subscriptions, base.group.selectedId, members[udp].id)
    }

    /** Every node [EVERY]th from [FIRST] gets an emoji in front of its name; the table cycles flag, Apple, YouTube, unknown. */
    private fun named(index: Int, node: RvNode): String {
        if (index < FIRST || (index - FIRST) % EVERY != 0) return node.name
        return when (((index - FIRST) / EVERY) % MARKS) {
            0 -> "${flagEmoji(node.place.flag)} ${node.name}"
            1 -> "🍎 Apple ${node.name.takeLast(2)}"
            2 -> "📺 YouTube"
            else -> "🦄 ${node.name}"
        }
    }

    private fun flagEmoji(code: String?): String = (code ?: "").uppercase().map { String(Character.toChars(REGIONAL_A + (it - 'A'))) }.joinToString("")

    private const val UDP_TWO = 5
    private const val UDP_FIVE = 33
    private const val FIRST = 1
    private const val EVERY = 3
    private const val MARKS = 4
    private const val REGIONAL_A = 0x1F1E6
}
