package dev.doona.android.feature.model

import dev.doona.android.vpn.honk.HealthState
import dev.doona.android.vpn.honk.Node
import dev.doona.android.vpn.honk.Provider
import dev.doona.android.vpn.honk.Transport
import kotlin.math.roundToInt

/**
 * honk's [node] as the pages show it. honk reports each node's last probe, not a series, so the history is that one
 * result: its latency, a timeout for a node honk found unavailable, none before a probe. honk's nodes carry no server,
 * port or TLS, so the node reads as not [RvNode.described] and has no share link. [providers] names the subscription by
 * id; a node of none (`provider_id` null) is 組態內.
 */
internal fun rvNode(node: Node, providers: Map<String, Provider>, places: NodePlaces): RvNode {
    val placed = places.of(node.name)
    val probe = node.health.firstOrNull { it.transport == Transport.Tcp } ?: node.health.firstOrNull()
    val history = when (probe?.state) {
        HealthState.Healthy -> listOf(probe.latencyMs?.roundToInt())
        HealthState.Unavailable -> listOf(null)
        HealthState.Unknown, null -> emptyList()
    }
    return RvNode(
        id = node.id,
        name = node.name,
        short = placed.short,
        place = placed.place,
        city = placed.city,
        protocol = node.protocol?.let(::protocolName).orEmpty(),
        host = "",
        port = 0,
        transport = node.streamTransport?.let(::transportName).orEmpty(),
        tls = false,
        subscription = node.providerId?.let { providers[it]?.name }.orEmpty(),
        subscriptionId = node.providerId ?: RV_INLINE,
        history = history,
        shareable = false,
    )
}

/** A transport as the pages name it (gRPC, WebSocket), from honk's lower-case id; an id not listed is shown as sent. */
internal fun transportName(id: String): String = TRANSPORTS[id] ?: id

private val TRANSPORTS = mapOf(
    "tcp" to "TCP",
    "ws" to "WebSocket",
    "grpc" to "gRPC",
    "h2" to "HTTP/2",
    "http" to "HTTP",
    "quic" to "QUIC",
    "httpupgrade" to "HTTPUpgrade",
    "xhttp" to "XHTTP",
)
