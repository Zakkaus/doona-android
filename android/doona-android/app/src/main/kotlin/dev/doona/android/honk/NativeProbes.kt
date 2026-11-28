package dev.doona.android.honk

import dev.doona.android.engine.EngineException
import dev.doona.android.engine.ErrorCode
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** honk's probes through its native API. Blocking: run on `Dispatchers.IO`. */
internal class NativeProbes(private val api: NativeApi) {
    /**
     * Tests the direct members of each of [groupIds] by HTTP over TCP, from cold, four groups at a time (honk runs at most
     * four probe jobs), and waits until every admitted probe ends; honk keeps the results as the members' health. A group
     * that fails does not stop the others; the first error is thrown at the end. A busy honk stops the later batches.
     */
    suspend fun groups(groupIds: List<String>) {
        var firstError: ErrorCode? = null
        for (batch in groupIds.chunked(4)) {
            val errors = coroutineScope {
                batch.map { id ->
                    async {
                        try {
                            val accepted = api.read("POST", "/api/v1/probes", OperationAccepted.serializer(), body(id))
                            val operation = api.finished(accepted.href)
                            if (operation.status == OperationStatus.Failed) operation.error?.errorCode() ?: ErrorCode.Internal else null
                        } catch (error: EngineException) {
                            error.code
                        }
                    }
                }.awaitAll()
            }
            if (firstError == null) firstError = errors.firstOrNull { it != null }
            if (ErrorCode.Busy in errors) break
        }
        firstError?.let { throw EngineException(it) }
    }

    /** Tests node [nodeId] the same way and waits until its probe ends; a probe honk could not run fails with its error. */
    suspend fun node(nodeId: String) {
        val body = buildJsonObject {
            putJsonObject("target") {
                put("type", "node")
                put("node_id", nodeId)
            }
            put("kind", "http")
            putJsonArray("transport") { add("tcp") }
            put("ip_version", "any")
            put("warmth", "cold")
        }.toString()
        val operation = api.finished(nativeJson.decodeFromString(OperationAccepted.serializer(), api.expect("POST", "/api/v1/probes", body)).href)
        operation.error?.let { throw EngineException(it.errorCode()) }
    }

    private fun body(groupId: String) = buildJsonObject {
        putJsonObject("target") {
            put("type", "group")
            put("group_id", groupId)
        }
        put("kind", "http")
        putJsonArray("transport") { add("tcp") }
        put("ip_version", "any")
        put("members", "direct")
        put("warmth", "cold")
    }.toString()
}
