package dev.doona.android.prototype.rv

import dev.doona.android.engine.Group
import dev.doona.android.engine.GroupKind
import dev.doona.android.engine.Member
import dev.doona.android.engine.Subscription

/**
 * The 「Proxy」 group of the node screen sample: eight nodes of one subscription whose names point at their country in
 * each way subscriptions spell it (a region code, a city, a flag emoji) and one that names none, with good, fair, slow
 * and untested latencies; hk-01, the activity sample's node, is selected.
 */
internal fun sampleNodesState(): RvNodesState {
    val nodes = listOf(
        SampleNode("hk-01", "Trojan", 42),
        SampleNode("🇭🇰 HK 02 IPLC", "Shadowsocks", 57),
        SampleNode("Tokyo 01", "VLESS", 88),
        SampleNode("🇯🇵 Osaka", "Hysteria2", 124),
        SampleNode("sg-02", "VMess", 168),
        SampleNode("edge-relay", "WireGuard", null),
        SampleNode("us-lax-01", "Shadowsocks", 312),
        SampleNode("Taipei HiNet", "Trojan", 71),
    )
    val members = nodes.mapIndexed { i, node -> Member(id = "n$i", name = node.name, latencyMs = node.latencyMs, subscriptionId = SUBSCRIPTION) }
    val group = Group(id = "g-Proxy", name = "Proxy", kind = GroupKind.Selector, members = members, selectedId = members.first().id, pinned = true)
    val subscription = Subscription(
        id = SUBSCRIPTION,
        name = "Harbor Cloud",
        url = "https://example.invalid/harbor",
        nodes = nodes.size,
        updatedAtEpochMs = NOW - 2 * HOUR,
        usedBytes = 62_400_000_000,
        totalBytes = 200_000_000_000,
        expiresAtEpochMs = EXPIRES,
    )
    return RvNodesState(group, listOf(subscription), members.zip(nodes) { m, n -> m.id to n.protocol }.toMap(), NOW)
}

private class SampleNode(val name: String, val protocol: String, val latencyMs: Int?)

private const val SUBSCRIPTION = "s1"

/** 2026-11-22 08:00 UTC; the subscription runs out on 2027-03-15 12:00 UTC. */
private const val NOW = 1_795_334_400_000L
private const val EXPIRES = 1_805_112_000_000L
private const val HOUR = 3_600_000L
