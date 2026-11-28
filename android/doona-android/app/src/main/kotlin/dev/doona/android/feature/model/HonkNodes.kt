package dev.doona.android.feature.model

import dev.doona.android.honk.HealthState
import dev.doona.android.honk.Node
import dev.doona.android.honk.Provider
import dev.doona.android.honk.ProviderKind
import dev.doona.android.honk.Transport
import kotlin.math.roundToInt

/**
 * honk's [node] as the pages show it. honk reports each node's last probe, not a series, so the history is that one
 * result: its latency, a timeout for a node honk found unavailable, none before a probe. honk's nodes carry no server,
 * port or TLS, so the node reads as not [RvNode.described] and has no share link. Its trend is the probe's latency less
 * the average of its last ten (owner, q-latency-trend 2026-11-26); none before ten. [providers] names the subscription
 * by id; a node added by its link ([isInline]) is 組態內.
 */
internal fun rvNode(node: Node, providers: Map<String, Provider>, places: NodePlaces): RvNode {
    val placed = places.of(node.name)
    val inline = node.isInline(providers)
    val probe = node.health.firstOrNull { it.transport == Transport.Tcp } ?: node.health.firstOrNull()
    val history = when (probe?.state) {
        HealthState.Healthy -> listOf(probe.latencyMs?.roundToInt())
        HealthState.Unavailable -> listOf(null)
        HealthState.Unknown, null -> emptyList()
    }
    val trend = probe?.takeIf { it.state == HealthState.Healthy }?.let { p -> p.latencyMs?.let { ms -> p.avg10Ms?.let { (ms - it).roundToInt() } } }
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
        subscription = if (inline) "" else providers[node.providerId]?.name.orEmpty(),
        subscriptionId = if (inline) RV_INLINE else node.providerId.orEmpty(),
        history = history,
        shareable = false,
        trendMs = trend,
    )
}

/** Whether [this] was added by its link: honk names its inline provider, or no provider. */
internal fun Node.isInline(providers: Map<String, Provider>): Boolean = providerId == null || providers[providerId]?.kind == ProviderKind.Inline

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
