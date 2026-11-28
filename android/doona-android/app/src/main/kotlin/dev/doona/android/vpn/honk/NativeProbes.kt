package dev.doona.android.vpn.honk

import dev.doona.android.engine.EngineException
import dev.doona.android.engine.ErrorCode
import dev.doona.android.vpn.NativeApi
import dev.doona.android.vpn.expect
import dev.doona.android.vpn.nativeJson
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
     * Tests the direct members of each of [groupIds] by HTTP over TCP, from cold, all groups at once, and waits until
     * every probe ends, polling them together; honk keeps the results as the members' health. A probe honk could not run fails with its error.
     */
    suspend fun groups(groupIds: List<String>) {
        val operations = groupIds.map { id ->
            nativeJson.decodeFromString(OperationAccepted.serializer(), api.expect("POST", "/api/v1/probes", body(id))).href
        }
        val ended = coroutineScope { operations.map { async { api.finished(it) } }.awaitAll() }
        ended.firstOrNull { it.status == OperationStatus.Failed }?.let { failed ->
            throw EngineException(failed.error?.errorCode() ?: ErrorCode.Internal)
        }
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
