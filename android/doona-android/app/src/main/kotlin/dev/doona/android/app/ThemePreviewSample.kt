package dev.doona.android.app

import dev.doona.android.engine.Status
import dev.doona.android.feature.activity.RvActivityCards
import dev.doona.android.feature.activity.RvActivityState
import dev.doona.android.feature.activity.RvMainNode
import dev.doona.android.feature.model.CoreNodePlaces
import dev.doona.android.feature.model.RvDay
import dev.doona.android.feature.model.RvNode
import dev.doona.android.feature.model.rvNode
import dev.doona.android.feature.subscriptions.rvSubscription
import dev.doona.android.honk.NodeList
import dev.doona.android.honk.ProviderList
import dev.doona.android.honk.nativeJson
import kotlin.math.sin

/**
 * What 主題's 預覽 draws: 活動 connected, on a small honk sample (one subscription of three nodes, as honk's
 * `GET /api/v1/providers` and `GET /api/v1/nodes` answer) mapped to the page's models as honk's own replies are, with
 * a minute of traffic. Only the preview uses it; the sandbox's samples stay debug-only.
 */
internal object ThemePreviewSample {
    private val providers = nativeJson.decodeFromString(ProviderList.serializer(), PROVIDERS).providers
    private val byId = providers.associateBy { it.id }

    val nodes: List<RvNode> = nativeJson.decodeFromString(NodeList.serializer(), NODES).nodes.map { rvNode(it, byId, CoreNodePlaces) }
    val subscriptions = providers.map { rvSubscription(it, userAgent = null) }

    private val main = nodes.first()

    val state = RvActivityState(
        status = Status.Running(sinceEpochMs = NOW - 2 * HOUR_MS),
        node = RvMainNode(main.id, main.name, main.history.lastOrNull(), failed = false),
        downRate = 2_400_000,
        upRate = 310_000,
        downHistory = List(SAMPLES) { (1_600_000 + 900_000 * sin(it / 5.0)).toFloat() },
        upHistory = List(SAMPLES) { (240_000 + 90_000 * sin(it / 4.0 + 1)).toFloat() },
    )

    val cards = RvActivityCards(
        today = RvDay(down = List(HOURS) { 180_000_000L + 40_000_000L * (it % 5) }, up = List(HOURS) { 22_000_000L + 6_000_000L * (it % 3) }, connections = null),
        nowHour = HOURS.toFloat(),
        yesterdayByNow = 1_900_000_000L,
        nowEpochMs = NOW,
        ruleCount = 14,
        mainNodeId = main.id,
        node = { id -> nodes.firstOrNull { it.id == id } },
        groupKind = { null },
    )
}

private const val NOW = 1_795_300_000_000L
private const val HOUR_MS = 3_600_000L
private const val SAMPLES = 60
private const val HOURS = 14

private const val PROVIDERS = """
{"providers":[{"id":"s1","name":"Sample","kind":"subscription","url_redacted":null,"node_count":3,
"updated_at":"2026-11-22T09:00:00Z","expires_at":null,"traffic":null,"status":"ok","last_error":null}],"next_cursor":null}
"""

private const val NODES = """
{"observed_at":"2026-11-22T10:00:00Z","nodes":[
{"id":"n-1","name":"Hong Kong 01","protocol":"trojan","stream_transport":"tcp","subscription_tag":"s1","provider_id":"s1",
"group_ids":["g-Proxy"],"health":[{"transport":"tcp","state":"healthy","latency_ms":38,"observed_at":"2026-11-22T10:00:00Z","error":null}]},
{"id":"n-2","name":"Tokyo 02","protocol":"vless","stream_transport":"ws","subscription_tag":"s1","provider_id":"s1",
"group_ids":["g-Proxy"],"health":[{"transport":"tcp","state":"healthy","latency_ms":64,"observed_at":"2026-11-22T10:00:00Z","error":null}]},
{"id":"n-3","name":"Singapore 03","protocol":"shadowsocks","stream_transport":null,"subscription_tag":"s1","provider_id":"s1",
"group_ids":["g-Proxy"],"health":[]}],"next_cursor":null}
"""
