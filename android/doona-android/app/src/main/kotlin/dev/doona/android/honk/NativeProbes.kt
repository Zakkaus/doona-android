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
     * Tests the direct members of each of [groups], then each user node of [nodes] in no group, by HTTP over TCP, from
     * cold, and waits until every probe ends; honk keeps the results as the members' health. Groups run at once; a group
     * over honk's 64-member limit is sent in lists of 64 member ids, one after another. A target that fails does not stop
     * the others: the first failure is thrown once all have ended.
     */
    suspend fun groups(groups: List<Group>, nodes: List<Node> = emptyList()) {
        val failures = coroutineScope {
            groups.map { group ->
                async {
                    try {
                        val batches = if (group.members.size <= 64) listOf(null) else group.members.chunked(64)
                        for (members in batches) {
                            val reply = api.expect("POST", "/api/v1/probes", body(group.id, members))
                            val operation = api.finished(nativeJson.decodeFromString(OperationAccepted.serializer(), reply).href)
                            if (operation.status == OperationStatus.Failed) throw EngineException(operation.error?.errorCode() ?: ErrorCode.Internal)
                        }
                        null
                    } catch (error: EngineException) {
                        error
                    }
                }
            }.awaitAll()
        }
        var failure = failures.firstOrNull { it != null }
        for (target in nodes) {
            if (target.providerId == null || target.groupIds.isNotEmpty()) continue
            try {
                node(target.id)
            } catch (error: EngineException) {
                if (failure == null) failure = error
            }
        }
        failure?.let { throw it }
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

    private fun body(groupId: String, members: List<GroupMember>?) = buildJsonObject {
        putJsonObject("target") {
            put("type", "group")
            put("group_id", groupId)
        }
        put("kind", "http")
        putJsonArray("transport") { add("tcp") }
        put("ip_version", "any")
        if (members == null) put("members", "direct") else putJsonArray("members") { members.forEach { add(it.id) } }
        put("warmth", "cold")
    }.toString()
}
