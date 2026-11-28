package dev.doona.android.feature.connections

import androidx.compose.runtime.Immutable
import dev.doona.android.designsystem.rv.RV_NO_VALUE
import dev.doona.android.designsystem.rv.RvPlace
import dev.doona.android.engine.ErrorCode
import dev.doona.android.feature.model.NodePlaces
import dev.doona.android.feature.model.RvGroupKind
import dev.doona.android.feature.model.RvRuleEnd
import dev.doona.android.feature.model.RvRuleMatch
import dev.doona.android.feature.model.protocolName
import dev.doona.android.feature.policies.ruleCondition
import dev.doona.android.feature.policies.rvGroupKind
import dev.doona.android.feature.policies.rvRuleMatch
import dev.doona.android.honk.Group
import dev.doona.android.honk.HonkConnection
import dev.doona.android.honk.Node

/**
 * A connection as 連線 shows it: where it went ([host], the domain, or the address when honk had none: [hasDomain]
 * false), the process honk saw open it ([app], empty when none) and its [packageName] (empty: honk records no Android
 * package), TCP or UDP, its bytes each way and when it started. [rule] is what the rule it matched matches, as the rule
 * pages say it, [ruleIndex] that rule's place in the list from 1; both null when honk recorded no rule. It went through
 * [group] to [node], to a standalone [node] with no group, or with neither to [end] (直連 or 拒絕); [end] is null when
 * honk recorded no outbound.
 */
@Immutable
internal class RvConnection(
    val id: String,
    val host: String,
    val port: Int,
    val hasDomain: Boolean,
    val app: String,
    val packageName: String,
    val udp: Boolean,
    val downBytes: Long,
    val upBytes: Long,
    val startedAtEpochMs: Long,
    val rule: RvRuleMatch?,
    val ruleIndex: Int?,
    val group: RvConnectionGroup?,
    val node: RvConnectionNode?,
    val end: RvRuleEnd?,
) {
    /** What the page's title says: the app, or the host when honk saw no process. */
    val title: String get() = app.ifEmpty { host }
    val endpoint: String get() = if (!hasDomain && ':' in host) "[$host]:$port" else "$host:$port"
}

/** The group a connection went through: its name as the profile gives it and how it picks. */
internal data class RvConnectionGroup(val name: String, val kind: RvGroupKind)

/** The node a connection ended on: its name, protocol and place. */
internal data class RvConnectionNode(val name: String, val protocol: String, val place: RvPlace)

/** 連線's state: the connections open when it last read them, newest first, and when that was; [loaded] once a read succeeded. */
internal data class RvConnectionsUi(val connections: List<RvConnection> = emptyList(), val nowEpochMs: Long = 0, val loaded: Boolean = false, val session: Long? = null, val error: ErrorCode? = null, val truncatedTotal: Long? = null)

/**
 * [connections] in 連線's words: each outbound named by its group in [groups], a standalone node in [nodes] by name (its
 * protocol 「—」 when two nodes share the name), 直連 for `direct`, 拒絕 for `block`, or a missing group for a name the
 * profile no longer has; the node at the end of its chain from [nodes] by id, placed by its name ([places]).
 */
internal fun connectionMapper(groups: List<Group>, nodes: List<Node>, places: NodePlaces): (HonkConnection) -> RvConnection {
    val byName = groups.associate { it.name to RvConnectionGroup(it.name, rvGroupKind(it.policy.kind)) }
    val byId = nodes.associate { it.id to RvConnectionNode(it.name, it.protocol?.let(::protocolName).orEmpty(), places.placeOf(it.name)) }
    val nodesByName = byId.values.groupBy { it.name }.mapValues { (_, same) -> same.singleOrNull() ?: same.first().copy(protocol = RV_NO_VALUE) }
    return { c ->
        val group = c.outbound?.let(byName::get)
        val node = when {
            c.outbound == DIRECT || c.outbound == BLOCK -> null
            c.chain.isNotEmpty() -> byId[c.chain.last()]
            group != null -> null
            else -> nodesByName[c.outbound]
        }
        RvConnection(
            id = c.id,
            host = c.host,
            port = c.port,
            hasDomain = c.hasDomain,
            app = c.process.orEmpty(),
            // honk-gap:conn-uid
            packageName = "",
            udp = c.udp,
            downBytes = c.downBytes,
            upBytes = c.upBytes,
            startedAtEpochMs = c.startedAtEpochMs,
            rule = c.ruleExpression?.let { if (it == "fallback") RvRuleMatch.Rest else rvRuleMatch(ruleCondition(it)) },
            ruleIndex = c.ruleId?.substringAfterLast(':')?.toIntOrNull()?.plus(1),
            group = group,
            node = node,
            end = when {
                group != null || node != null || c.outbound == null -> null
                c.outbound == DIRECT -> RvRuleEnd.Direct
                c.outbound == BLOCK -> RvRuleEnd.Reject
                else -> RvRuleEnd.Missing
            },
        )
    }
}

private const val DIRECT = "direct"
private const val BLOCK = "block"
