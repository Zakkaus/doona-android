package dev.doona.android.feature.subscriptions

import dev.doona.android.engine.Engine
import dev.doona.android.engine.EngineException
import dev.doona.android.engine.ErrorCode
import dev.doona.android.engine.Member
import dev.doona.android.engine.NodeInfo
import dev.doona.android.engine.StaticNode
import dev.doona.android.engine.StaticNodeDraft
import dev.doona.android.engine.StaticNodeReason
import dev.doona.android.engine.StaticNodeResult
import dev.doona.android.engine.fake.FakeEngine
import dev.doona.android.engine.fake.FakeRuleMode
import kotlinx.coroutines.CompletableDeferred
import java.net.URI
import java.net.URLDecoder

/**
 * [fake] with the core's static node commands, as `crates/doona-core/src/api/static_nodes.rs` answers them: a link of a
 * known protocol is named after its #fragment, else its host; one already added is a duplicate; anything else is invalid.
 * An add commits every accepted row together, into the main group, 自動, 串流, AI and the group of its region ([regionOf], from the
 * name, as the core reads it). [failing] makes the next calls of that kind fail as a whole; [refused] lists the links
 * the add refuses although the preview took them (a row changed in between).
 */
internal class StaticNodesEngine(
    val fake: FakeEngine = FakeEngine(),
    private val regionOf: (String) -> String? = { null },
) : Engine by fake {
    var failing: ErrorCode? = null
    var deleteFailing: ErrorCode? = null
    val refused = mutableSetOf<String>()
    private val records = linkedMapOf<String, StaticNode>()
    private val info = mutableMapOf<String, NodeInfo>()
    private var next = 1

    /** The links each preview was asked about, in order. */
    val previews = mutableListOf<List<String>>()

    override suspend fun previewStaticNodes(rows: List<StaticNodeDraft>): Result<List<StaticNodeResult>> {
        val links = rows.map { (it as StaticNodeDraft.Link).link }
        previews += links
        failing?.let { return Result.failure(EngineException(it)) }
        return Result.success(links.map { link -> check(link) { name, protocol -> StaticNodeResult.Ready("preview-$name", name, protocol) } })
    }

    /** While set, an add waits for it before it answers, as a slow core does. */
    var addGate: CompletableDeferred<Unit>? = null

    override suspend fun addStaticNodes(rows: List<StaticNodeDraft>): Result<List<StaticNodeResult>> {
        addGate?.await()
        failing?.let { return Result.failure(EngineException(it)) }
        val links = rows.map { (it as StaticNodeDraft.Link).link }
        val results = links.map { link ->
            if (link in refused) return@map StaticNodeResult.Duplicate("taken")
            check(link) { name, protocol -> StaticNodeResult.Added("static-${next++}", name, protocol) }
        }
        val added = results.zip(links).mapNotNull { (result, link) -> (result as? StaticNodeResult.Added)?.let { it to link } }
        added.forEach { (node, link) ->
            records[node.id] = StaticNode(node.id, node.name, link)
            info[node.id] = NodeInfo(node.id, null, node.protocol, URI(link).host.orEmpty(), URI(link).port, "tcp", true, emptyList())
        }
        join(added.map { it.first })
        return Result.success(results)
    }

    override suspend fun readStaticNode(id: String): Result<StaticNode> =
        records[id]?.let { Result.success(it) } ?: Result.failure(EngineException(ErrorCode.NotFound))

    override suspend fun removeStaticNode(expected: StaticNode, confirmed: Boolean): Result<Unit> {
        deleteFailing?.let { return Result.failure(EngineException(it)) }
        if (!confirmed || records[expected.id] != expected) return Result.failure(EngineException(ErrorCode.Busy))
        records -= expected.id
        fake.groups.value = fake.groups.value.map { group -> group.copy(members = group.members.filterNot { it.id == expected.id }) }
        return Result.success(Unit)
    }

    override suspend fun nodes(ids: List<String>?): Result<List<NodeInfo>> =
        fake.nodes(ids).map { known -> known + ids.orEmpty().mapNotNull(info::get) }

    /** The added nodes into the main group, 自動 and their region's group, as the core's template rebuilds the groups. */
    private fun join(added: List<StaticNodeResult.Added>) {
        if (added.isEmpty()) return
        fake.groups.value = fake.groups.value.map { group ->
            val members = added.filter { group.id == FakeRuleMode.MAIN_GROUP_ID || group.name in EVERY_NODE || group.name == regionOf(it.name) }
                .map { Member(it.id, it.name, region = regionOf(it.name)) }
            if (members.isEmpty()) group else group.copy(members = group.members + members)
        }
    }

    private fun check(link: String, ok: (name: String, protocol: String) -> StaticNodeResult): StaticNodeResult {
        val uri = runCatching { URI(link) }.getOrNull() ?: return StaticNodeResult.Invalid(StaticNodeReason.Link)
        if (uri.scheme !in PROTOCOLS) return StaticNodeResult.Invalid(StaticNodeReason.Protocol)
        if (records.values.any { it.link == link }) return StaticNodeResult.Duplicate(records.values.first { it.link == link }.id)
        val name = uri.rawFragment?.let { URLDecoder.decode(it, Charsets.UTF_8) } ?: uri.host.orEmpty()
        return ok(name, uri.scheme)
    }

    private companion object {
        val PROTOCOLS = setOf("ss", "vmess", "vless", "trojan", "hysteria2", "socks5")

        /** The template's groups that take every node besides the main one (`core-api.md`, group template). */
        val EVERY_NODE = setOf("Auto", "Streaming", "AI")
    }
}

/** A clipboard holding [clip], or nothing. */
internal class HeldClipboard(var clip: String? = null) : ClipboardLinks {
    override fun mayHoldLink(): Boolean = clip != null

    override fun link(): String? = clip

    override fun text(): String? = clip
}
