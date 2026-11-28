package dev.doona.android.feature.subscriptions

import dev.doona.android.honk.HonkFixtures
import dev.doona.android.honk.NativeApi
import dev.doona.android.honk.NativeResponse
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * honk's RV sample profile ([HonkFixtures.rvSample]) with its inline provider and POST and DELETE /nodes, as honk's
 * native API answers them. An add files the node under the name asked, in the inline provider, and puts it in the
 * groups that take every node (Proxy, Auto, Streaming, AI) and in its region's group ([regionOf], from the name, as a
 * filter would); a name in use is 409 `state_conflict`, a link in [refused] 422 `unsupported_value`. GET /nodes and
 * each group list what was added. Its configuration ([sources]) holds the groups' blocks: Proxy and Auto with no filter,
 * so they take every node, the others with name filters, US in a file honk cannot write; a replace of a source keeps
 * the new text ([writes], in order) and its reload succeeds. [addStatus], [deleteStatus] and [writeStatus] answer every
 * add, delete or replace with that error status.
 */
internal class InlineHonk(private val regionOf: (String) -> String? = { null }) : NativeApi {
    private val sample = HonkFixtures.rvSample()
    private val added = mutableListOf<JsonObject>()
    private var next = 1
    var addStatus: Int? = null
    var deleteStatus: Int? = null
    var writeStatus: Int? = null
    val sources = linkedMapOf("source-main" to MAIN, "source-regions" to REGIONS_FILE, "source-us" to US_FILE)
    val writes = mutableListOf<Pair<String, String>>()
    val refused = mutableSetOf<String>()

    /** The links each add was asked to file, in order. */
    val links = mutableListOf<String>()

    @Synchronized
    override fun call(method: String, path: String, body: String?, headers: Map<String, String>): NativeResponse = when {
        method == "POST" && path == NODES -> create(Json.parseToJsonElement(checkNotNull(body)).jsonObject)
        method == "DELETE" && path.startsWith("$NODES/") -> remove(path.removePrefix("$NODES/"))
        method == "PUT" && path.startsWith("$CONFIG/sources/") -> write(path.removePrefix("$CONFIG/sources/"), Json.parseToJsonElement(checkNotNull(body)).jsonObject)
        method == "GET" -> read(path) ?: sample.call(method, path, body, headers)
        else -> sample.call(method, path, body, headers)
    }

    /** The answer to GET [path] where what this honk added or wrote changes it, or null where the sample's stands. */
    private fun read(path: String): NativeResponse? = when {
        path == NODES -> NativeResponse(200, appended(sample.call("GET", path, null).body, "nodes", added))
        path == PROVIDERS -> NativeResponse(200, appended(sample.call("GET", path, null).body, "providers", listOf(INLINE_PROVIDER)))
        path == CONFIG -> NativeResponse(200, config())
        path == RELOAD -> NativeResponse(200, HonkFixtures.body("operations/reload-complete"))
        path.startsWith("$GROUPS/") -> NativeResponse(200, members(sample.call("GET", path, null).body, path.removePrefix("$GROUPS/")))
        else -> null
    }

    /** Files [link] under [name] at once, as an add does. */
    fun add(name: String, link: String) = create(
        buildJsonObject {
            put("name", name)
            put("link", link)
        },
    )

    private fun create(request: JsonObject): NativeResponse {
        val name = request.getValue("name").jsonPrimitive.content
        val link = request.getValue("link").jsonPrimitive.content
        links += link
        addStatus?.let { return error(it, "internal") }
        if (link in refused) return error(422, "unsupported_value")
        if (added.any { it.getValue("name").jsonPrimitive.content == name }) return error(409, "state_conflict")
        val groups = EVERY_NODE + listOfNotNull(regionOf(name)?.let { "g-$it" }?.takeIf { it in REGION_GROUPS })
        val node = buildJsonObject {
            put("id", "static-${next++}")
            put("name", name)
            put("protocol", link.substringBefore("://"))
            put("subscription_tag", JsonNull)
            put("provider_id", "inline")
            putJsonArray("group_ids") { groups.forEach { add(JsonPrimitive(it)) } }
            putJsonArray("health") {}
        }
        added += node
        return NativeResponse(201, node.toString())
    }

    private fun config(): String = buildJsonObject {
        putJsonArray("sources") {
            sources.forEach { (id, content) ->
                add(
                    buildJsonObject {
                        put("id", id)
                        put("path", "${id.removePrefix("source-")}.dae")
                        put("kind", if (id == "source-main") "main" else "include")
                        put("content_sha256", content.hashCode().toString())
                        put("content", content)
                        put("writable", id != "source-us")
                    },
                )
            }
        }
    }.toString()

    private fun write(id: String, request: JsonObject): NativeResponse {
        writeStatus?.let { return error(it, "temporarily_unavailable") }
        val content = request.getValue("content").jsonPrimitive.content
        sources[id] = content
        writes += id to content
        return NativeResponse(202, HonkFixtures.body("config-replace/queued"))
    }

    private fun remove(id: String): NativeResponse {
        deleteStatus?.let { return error(it, "internal") }
        val removed = added.removeAll { it.getValue("id").jsonPrimitive.content == id }
        return NativeResponse(200, """{"deleted":${if (removed) 1 else 0}}""")
    }

    /** [body]'s group with the added nodes it takes appended to its members. */
    private fun members(body: String, id: String): String {
        val group = Json.parseToJsonElement(body).jsonObject
        val taken = added.filter { node -> node.getValue("group_ids").jsonArray.any { it.jsonPrimitive.content == id } }.map { node ->
            buildJsonObject {
                put("id", node.getValue("id"))
                put("name", node.getValue("name"))
                put("kind", "node")
            }
        }
        return JsonObject(group + ("members" to JsonArray(group.getValue("members").jsonArray + taken))).toString()
    }

    private companion object {
        const val NODES = "/api/v1/nodes"
        const val PROVIDERS = "/api/v1/providers"
        const val GROUPS = "/api/v1/groups"
        const val CONFIG = "/api/v1/config"
        const val RELOAD = "/api/v1/operations/op-config-01"

        val MAIN = listOf(
            "group {",
            "    Proxy {",
            "        policy: select",
            "    }",
            "    Auto {",
            "        policy: min_moving_avg",
            "    }",
            "    Streaming {",
            "        filter: name(keyword: 'Hong Kong', 'Taipei')",
            "        policy: min_moving_avg",
            "    }",
            "    AI {",
            "        filter: name(keyword: 'Tokyo')",
            "        policy: min_moving_avg",
            "    }",
            "}",
        ).joinToString("\n")

        val REGIONS_FILE = listOf("HK", "TW", "JP").joinToString("\n", "group {\n", "\n}") { "    $it {\n        filter: name(regex: '^$it ')\n        policy: min_moving_avg\n    }" }

        val US_FILE = "group {\n    US {\n        filter: name(regex: '^US ')\n    }\n}"
        val EVERY_NODE = listOf("g-Proxy", "g-Auto", "g-Streaming", "g-AI")
        val REGION_GROUPS = setOf("g-HK", "g-TW", "g-JP", "g-US")

        val INLINE_PROVIDER = buildJsonObject {
            put("id", "inline")
            put("name", "inline")
            put("kind", "inline")
            put("url_redacted", JsonNull)
            put("node_count", 0)
            put("updated_at", JsonNull)
            put("expires_at", JsonNull)
            put("traffic", JsonNull)
            put("status", "ok")
            put("last_error", JsonNull)
        }

        /** [body]'s list under [key] with [items] appended. */
        fun appended(body: String, key: String, items: List<JsonObject>): String {
            val page = Json.parseToJsonElement(body).jsonObject
            return JsonObject(
                page + (
                    key to buildJsonArray {
                        page.getValue(key).jsonArray.forEach(::add)
                        items.forEach(::add)
                    }
                    ),
            ).toString()
        }

        fun error(status: Int, code: String) = NativeResponse(status, """{"error":{"code":"$code","message":"$code","details":null},"request_id":"request-1"}""")
    }
}

/** A clipboard holding [clip], or nothing. */
internal class HeldClipboard(var clip: String? = null) : ClipboardLinks {
    override fun mayHoldLink(): Boolean = clip != null

    override fun link(): String? = clip

    override fun text(): String? = clip
}
