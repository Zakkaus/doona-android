package dev.doona.android.vpn.honk

import dev.doona.android.engine.EngineException
import dev.doona.android.engine.ErrorCode
import dev.doona.android.vpn.NativeApi
import dev.doona.android.vpn.expect
import dev.doona.android.vpn.nativeJson
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** honk's probes through its native API. Blocking: run on `Dispatchers.IO`. */
internal class NativeProbes(private val api: NativeApi) {
    /**
     * Tests the direct members of each of [groupIds] by HTTP over TCP, from cold, all groups at once, and waits until
     * every probe ends; honk keeps the results as the members' health. A probe honk could not run fails with its error.
     */
    suspend fun groups(groupIds: List<String>) {
        val operations = groupIds.map { id ->
            nativeJson.decodeFromString(OperationAccepted.serializer(), api.expect("POST", "/api/v1/probes", body(id))).href
        }
        operations.map { api.finished(it) }.firstOrNull { it.status == OperationStatus.Failed }?.let { failed ->
            throw EngineException(failed.error?.errorCode() ?: ErrorCode.Internal)
        }
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
