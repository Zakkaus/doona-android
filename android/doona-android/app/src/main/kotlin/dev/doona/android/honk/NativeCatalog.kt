package dev.doona.android.honk

import dev.doona.android.engine.EngineException
import dev.doona.android.engine.ErrorCode
import kotlinx.coroutines.delay
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.net.URLEncoder

/**
 * honk's providers, nodes, groups and selections through its native API. honk refreshes each provider on its own
 * interval, keeps selections and probes health; this class only reads and writes. Every call blocks in [NativeApi]: run
 * them on `Dispatchers.IO`.
 */
internal class NativeCatalog(private val api: NativeApi) {
    fun providers(): List<Provider> = pages { cursor ->
        api.read("GET", page("/api/v1/providers", cursor), ProviderList.serializer()).let { it.providers to it.nextCursor }
    }

    /** A [userAgent] of null leaves honk's default. A name already in use fails with [ErrorCode.InvalidArgument]. */
    suspend fun addProvider(name: String, url: String, userAgent: String?): Provider {
        val body = buildJsonObject {
            put("name", name)
            put("kind", "subscription")
            put("url", url)
            userAgent?.let { put("user_agent", it) }
        }
        return created("POST", "/api/v1/providers", body.toString(), Provider.serializer())
    }

    suspend fun removeProvider(id: String) = removed("/api/v1/providers/$id")

    /** Fetches [id] now and waits for honk to publish its nodes; another refresh of it in flight fails with [ErrorCode.Busy]. */
    suspend fun refreshProvider(id: String): Provider =
        finished(api.read("POST", "/api/v1/providers/$id/refresh", OperationAccepted.serializer()), Provider.serializer())

    fun nodes(): List<Node> = pages { cursor ->
        api.read("GET", page("/api/v1/nodes", cursor), NodeList.serializer()).let { it.nodes to it.nextCursor }
    }

    /** Adds a static node from a share link; a link honk cannot use or a name in use fails with [ErrorCode.InvalidArgument]. */
    suspend fun addNode(name: String, link: String): Node {
        val body = buildJsonObject {
            put("name", name)
            put("link", link)
        }
        return created("POST", "/api/v1/nodes", body.toString(), Node.serializer())
    }

    suspend fun removeNode(id: String) = removed("/api/v1/nodes/$id")

    fun summaries(): List<GroupSummary> = api.read("GET", "/api/v1/groups", ListSerializer(GroupSummary.serializer()))

    fun groups(): List<Group> = summaries().map { group(it.id) }

    /** One group in full; one honk does not have fails with [ErrorCode.NotFound]. */
    fun group(id: String): Group = api.read("GET", "/api/v1/groups/$id", Group.serializer())

    /** Selects [memberId] in [groupId] on [network]: a Selector's choice, or a pin on an automatic group. */
    fun select(groupId: String, memberId: String, network: SelectionNetwork) {
        val body = buildJsonObject {
            put("member_id", memberId)
            put("network", wire(network))
        }
        api.expect("PUT", "/api/v1/groups/$groupId/selection", body.toString())
    }

    /** Clears the pins on an automatic group; a Selector has none and fails with [ErrorCode.InvalidArgument]. */
    fun clearSelection(groupId: String, network: SelectionNetwork) {
        api.expect("DELETE", "/api/v1/groups/$groupId/selection?network=${wire(network)}", conflict = ErrorCode.InvalidArgument)
    }

    /** A create answers 201 with the resource, or 202 with an operation when honk runs it in the background. */
    private suspend fun <T> created(method: String, path: String, body: String, serializer: KSerializer<T>): T =
        resource(api.expect(method, path, body, conflict = ErrorCode.InvalidArgument), serializer)

    private suspend fun removed(path: String) {
        if (resource(api.expect("DELETE", path), DeleteCount.serializer()).deleted == 0) throw EngineException(ErrorCode.NotFound)
    }

    /** [body] is the resource itself, or the [OperationAccepted] that yields it: only the latter has an `operation_id`. */
    private suspend fun <T> resource(body: String, serializer: KSerializer<T>): T {
        val json = nativeJson.parseToJsonElement(body).jsonObject
        if ("operation_id" !in json) return nativeJson.decodeFromJsonElement(serializer, json)
        return finished(nativeJson.decodeFromJsonElement(OperationAccepted.serializer(), json), serializer)
    }

    /** Polls [accepted] until it ends: its result, or its error as an [EngineException]. */
    private suspend fun <T> finished(accepted: OperationAccepted, serializer: KSerializer<T>): T {
        while (true) {
            delay(POLL_MS)
            val operation = api.read("GET", accepted.href, Operation.serializer(serializer))
            when (operation.status) {
                OperationStatus.Succeeded -> return checkNotNull(operation.result) { "a finished operation without a result" }
                OperationStatus.Failed -> throw EngineException(checkNotNull(operation.error) { "a failed operation without an error" }.errorCode())
                OperationStatus.Queued, OperationStatus.Running -> Unit
            }
        }
    }

    private companion object {
        /** honk's operations ask to be polled no more than once a second (`Retry-After: 1`). */
        const val POLL_MS = 1_000L
    }
}

/**
 * A failed fetch names its stage; one that fetched text honk could not read is [ErrorCode.ParseFailed]. An operation
 * whose change honk could not record in its configuration store (`store_unavailable`) is [ErrorCode.WriteFailed].
 */
internal fun SafeError.errorCode(): ErrorCode = when {
    code == "fetch_failed" && (details?.get("stage") as? JsonPrimitive)?.content == "parse_failed" -> ErrorCode.ParseFailed
    code == "fetch_failed" || code == "route_unavailable" -> ErrorCode.FetchFailed
    code == "store_unavailable" -> ErrorCode.WriteFailed
    else -> ErrorCode.Internal
}

private fun <T> pages(read: (cursor: String?) -> Pair<List<T>, String?>): List<T> {
    val items = mutableListOf<T>()
    var cursor: String? = null
    do {
        val (page, next) = read(cursor)
        items += page
        cursor = next
    } while (cursor != null)
    return items
}

private fun page(path: String, cursor: String?) = cursor?.let { "$path?cursor=${URLEncoder.encode(it, "UTF-8")}" } ?: path

private fun wire(network: SelectionNetwork) = when (network) {
    SelectionNetwork.Tcp -> "tcp"
    SelectionNetwork.Udp -> "udp"
    SelectionNetwork.Both -> "both"
}
