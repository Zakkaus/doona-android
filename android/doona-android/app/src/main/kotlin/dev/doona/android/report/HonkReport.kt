package dev.doona.android.report

import dev.doona.android.honk.Group
import dev.doona.android.honk.HealthState
import dev.doona.android.honk.MemberKind
import dev.doona.android.honk.NativeApi
import dev.doona.android.honk.NativeCatalog
import dev.doona.android.honk.Node
import dev.doona.android.honk.Provider
import dev.doona.android.honk.Transport
import dev.doona.android.honk.expect
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * What the report holds of honk, read through its native API as the export starts: its build (`GET /version`), its
 * state (`GET /runtime`) and its providers, nodes and groups ([NativeCatalog]). Each part honk does not answer is null.
 * Blocking: read it on `Dispatchers.IO`.
 */
internal class HonkView(val version: JsonObject?, val runtime: JsonObject?, val catalog: HonkCatalog?) {
    companion object {
        fun read(api: NativeApi): HonkView {
            val catalog = NativeCatalog(api)
            return HonkView(
                version = api.json("/api/v1/version"),
                runtime = api.json("/api/v1/runtime"),
                catalog = runCatching { HonkCatalog(catalog.providers(), catalog.nodes(), catalog.groups()) }.getOrNull(),
            )
        }

        private fun NativeApi.json(path: String): JsonObject? = runCatching { Json.parseToJsonElement(expect("GET", path)) as? JsonObject }.getOrNull()
    }
}

internal class HonkCatalog(val providers: List<Provider>, val nodes: List<Node>, val groups: List<Group>)

/**
 * honk's build: the engine's name and version, the commit and the target it was built for. Names, ids and paths a
 * configuration chose never reach the report: providers, nodes and groups go by their place in honk's lists.
 */
internal fun honkCore(version: JsonObject?): JsonObject? {
    version ?: return null
    return buildJsonObject {
        val engine = version.obj("engine")
        copyMatch(engine, "name", ENGINE_NAME)
        copyMatch(engine, "version", ENGINE_VERSION)
        val build = version.obj("build")
        copyMatch(build, "revision", REVISION)
        copyMatch(build, "target", TARGET)
    }
}

/** honk's state: its lifecycle, the active configuration's, the datapath's, the connections open and the last reload. */
internal fun honkRuntime(runtime: JsonObject?): JsonObject? {
    runtime ?: return null
    return buildJsonObject {
        runtime.obj("lifecycle").let {
            copyMatch(it, "state", WORD)
            copyNumber(it, "uptime_seconds")
        }
        copyMatch(runtime.obj("generation"), "state", WORD, "generation")
        runtime.obj("datapath").let {
            copyMatch(it, "kind", WORD, "datapath")
            copyMatch(it, "state", WORD, "datapathState")
        }
        runtime.obj("traffic")?.obj("connections")?.let { c ->
            putJsonObject("connections") { for (key in listOf("tcp", "udp", "total")) copyNumber(c, key) }
        }
        runtime.obj("last_reload")?.let { reload ->
            putJsonObject("lastReload") {
                copyMatch(reload, "status", WORD)
                copyMatch(reload.obj("error"), "code", WORD, "error")
            }
        }
        (runtime["degradations"] as? JsonArray)?.let { list ->
            putJsonArray("degradations") {
                list.mapNotNull { it as? JsonObject }.forEach { d ->
                    addJsonObject {
                        copyMatch(d, "component", WORD)
                        copyMatch(d, "code", WORD)
                    }
                }
            }
        }
    }
}

/** honk's providers, groups and nodes by counts and kinds, each by its place in honk's list. */
internal fun honkCatalog(catalog: HonkCatalog): JsonObject = buildJsonObject {
    val providers = catalog.providers.withIndex().associate { it.value.id to it.index }
    val groups = catalog.groups.withIndex().associate { it.value.id to it.index }
    val nodes = catalog.nodes.withIndex().associate { it.value.id to it.index }
    putJsonArray("providers") {
        catalog.providers.forEachIndexed { i, p -> addJsonObject { provider(i, p) } }
    }
    putJsonArray("groups") {
        catalog.groups.forEachIndexed { i, g -> addJsonObject { group(i, g, groups, nodes) } }
    }
    putJsonObject("nodes") {
        put("total", catalog.nodes.size)
        put("tested", catalog.nodes.count { n -> n.tcp()?.latencyMs != null })
        put("failed", catalog.nodes.count { n -> n.tcp()?.state == HealthState.Unavailable })
    }
    putJsonArray("nodeList") {
        catalog.nodes.take(MAX_LISTED_NODES).forEachIndexed { i, n ->
            addJsonObject {
                put("code", "node:$i")
                put("provider", n.providerId?.let(providers::get))
                put("protocol", n.protocol?.takeIf(WORD::matches))
                put("latencyMs", n.tcp()?.latencyMs?.toLong())
                put("state", n.tcp()?.state?.name)
            }
        }
    }
}

private fun JsonObjectBuilder.provider(at: Int, provider: Provider) {
    put("index", at)
    put("kind", provider.kind.name)
    put("nodes", provider.nodeCount)
    put("updated", provider.updatedAt != null)
    put("status", provider.status.name)
    // Only whether the last refresh failed, not why.
    put("failed", provider.lastError != null)
    put("quota", provider.traffic?.totalBytes != null)
}

private fun JsonObjectBuilder.group(at: Int, group: Group, groups: Map<String, Int>, nodes: Map<String, Int>) {
    put("index", at)
    put("kind", group.policy.kind.name)
    put("members", group.members.size)
    put("nodes", group.members.count { it.kind == MemberKind.Node })
    put("tcpSelected", selected(group.runtime.selection.tcp?.memberId, group, groups, nodes))
    put("tcpPinned", group.runtime.selection.tcp?.source == OVERRIDE)
    put("udpSelected", selected(group.runtime.selection.udp?.memberId, group, groups, nodes))
    put("udpPinned", group.runtime.selection.udp?.source == OVERRIDE)
}

/** A selected member by its place in honk's lists: `group:<i>` or `node:<i>`. */
private fun selected(id: String?, group: Group, groups: Map<String, Int>, nodes: Map<String, Int>): String? {
    val member = group.members.firstOrNull { it.id == id } ?: return null
    return when (member.kind) {
        MemberKind.Group -> groups[member.id]?.let { "group:$it" }
        MemberKind.Node -> nodes[member.id]?.let { "node:$it" }
    }
}

private fun Node.tcp() = health.firstOrNull { it.transport == Transport.Tcp }

private fun JsonObject.obj(key: String) = this[key] as? JsonObject

private fun JsonObjectBuilder.copyMatch(source: JsonObject?, key: String, pattern: Regex, name: String = key) {
    val value = source?.get(key) as? JsonPrimitive ?: return
    if (value == JsonNull) {
        put(name, JsonNull)
    } else if (value.isString && pattern.matches(value.content)) {
        put(name, value.content)
    }
}

/** A count, which honk sends as a number or, past 2^53, as a decimal string. */
private fun JsonObjectBuilder.copyNumber(source: JsonObject?, key: String) {
    val value = source?.get(key) as? JsonPrimitive ?: return
    (value.longOrNull ?: value.content.toLongOrNull())?.let { put(key, it) }
}

/** A selection's source when the user pinned the member. */
private const val OVERRIDE = "override"

private val ENGINE_NAME = Regex("[a-z][a-z0-9-]{0,31}")
private val ENGINE_VERSION = Regex("""\d{1,9}(?:\.\d{1,9}){0,3}(?:-[0-9A-Za-z.]{1,32})?""")
private val REVISION = Regex("[0-9a-f]{7,40}")
private val TARGET = Regex("(?:aarch64|arm|armv7|x86_64|x86|i686|riscv64)-[a-z0-9_-]{1,48}")

/** An engine-defined word: a state, kind or error code, never free text. */
private val WORD = Regex("[a-z][a-z0-9_.-]{0,47}")
