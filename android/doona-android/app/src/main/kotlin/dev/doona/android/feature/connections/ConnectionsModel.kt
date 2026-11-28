package dev.doona.android.feature.connections

import androidx.compose.runtime.Immutable
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
 * [group] to [node], or with no group to [end] (直連 or 拒絕); [end] is null when honk recorded no outbound.
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
internal data class RvConnectionsUi(val connections: List<RvConnection> = emptyList(), val nowEpochMs: Long = 0, val loaded: Boolean = false, val session: Long? = null, val error: ErrorCode? = null)

/**
 * [connections] in 連線's words: each outbound named by its group in [groups] (or 直連 for `direct`, 拒絕 for `block`, and
 * a missing group for a name the profile no longer has), and the node at the end of its chain from [nodes], placed by
 * its name ([places]).
 */
internal fun connectionMapper(groups: List<Group>, nodes: List<Node>, places: NodePlaces): (HonkConnection) -> RvConnection {
    val byName = groups.associate { it.name to RvConnectionGroup(it.name, rvGroupKind(it.policy.kind)) }
    val byId = nodes.associate { it.id to RvConnectionNode(it.name, it.protocol?.let(::protocolName).orEmpty(), places.placeOf(it.name)) }
    return { c ->
        val group = c.outbound?.let(byName::get)
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
            rule = c.ruleExpression?.let { rvRuleMatch(ruleCondition(it)) },
            ruleIndex = c.ruleId?.substringAfterLast(':')?.toIntOrNull()?.plus(1),
            group = group,
            node = group?.let { c.chain.lastOrNull()?.let(byId::get) },
            end = when {
                group != null || c.outbound == null -> null
                c.outbound == DIRECT -> RvRuleEnd.Direct
                c.outbound == BLOCK -> RvRuleEnd.Reject
                else -> RvRuleEnd.Missing
            },
        )
    }
}

private const val DIRECT = "direct"
private const val BLOCK = "block"
