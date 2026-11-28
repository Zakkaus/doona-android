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
     * cold, and waits until every admitted probe ends; honk keeps the results as the members' health. Groups go four at a
     * time (honk runs at most four probe jobs); a group over honk's 64-member limit is sent in lists of 64 member ids, one
     * after another. A target or a list that fails does not stop the others, but a busy honk stops everything after its
     * batch, or after the node it refused; the first failure is thrown at the end.
     */
    suspend fun groups(groups: List<Group>, nodes: List<Node> = emptyList()) {
        var failure: EngineException? = null
        for (batch in groups.chunked(4)) {
            val errors = coroutineScope {
                batch.map { group ->
                    async {
                        var failed: EngineException? = null
                        for (members in if (group.members.size <= 64) listOf(null) else group.members.chunked(64)) {
                            try {
                                val accepted = api.read("POST", "/api/v1/probes", OperationAccepted.serializer(), body(group.id, members))
                                val operation = api.finished(accepted.href)
                                if (operation.status == OperationStatus.Failed) throw EngineException(operation.error?.errorCode() ?: ErrorCode.Internal)
                            } catch (error: EngineException) {
                                if (error.code == ErrorCode.Busy) return@async error
                                failed = failed ?: error
                            }
                        }
                        failed
                    }
                }.awaitAll()
            }
            if (failure == null) failure = errors.firstOrNull { it != null }
            val busy = errors.firstOrNull { it?.code == ErrorCode.Busy }
            if (busy != null) throw failure ?: busy
        }
        for (target in nodes) {
            if (target.providerId == null || target.groupIds.isNotEmpty()) continue
            try {
                node(target.id)
            } catch (error: EngineException) {
                if (failure == null) failure = error
                if (error.code == ErrorCode.Busy) break
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
