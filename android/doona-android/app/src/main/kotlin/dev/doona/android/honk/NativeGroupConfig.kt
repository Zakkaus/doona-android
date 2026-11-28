package dev.doona.android.honk

import dev.doona.android.engine.EngineException
import dev.doona.android.engine.ErrorCode
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * A group's policy and its `default` and `final` through honk's native API (`/groups/{id}/config`). honk writes them
 * into the group's source and reloads. Members and the name are the source's filters, which this API does not edit.
 */
internal class NativeGroupConfig(private val api: NativeApi) {
    /** From `GET /groups/{id}`, which embeds the same document with the revision `/config` sends as its ETag. */
    fun read(id: String): HonkGroupConfig = api.read("GET", "/api/v1/groups/$id", HonkGroupConfig.serializer())

    /**
     * Writes what [after] changes from [before], the config read. Each changed field is first tested for its value in
     * [before], and `If-Match` carries the revision [before] was read at, so a change made elsewhere since the read fails
     * as [ErrorCode.Busy] (honk's 409 or 412) and is not overwritten. A reload that fails is [ErrorCode.InvalidArgument].
     * Nothing changed sends nothing.
     */
    suspend fun write(id: String, before: HonkGroupConfig, after: HonkGroupConfig) {
        val patch = buildJsonArray {
            fun change(path: String, old: JsonElement, new: JsonElement) {
                if (old == new) return
                add(op("test", path, old))
                add(op("replace", path, new))
            }
            change("/policy", nativeJson.encodeToJsonElement(before.policy), nativeJson.encodeToJsonElement(after.policy))
            change("/config/default_member_id", text(before.config.defaultMemberId), text(after.config.defaultMemberId))
            change("/config/final_outbound", text(before.config.finalOutbound), text(after.config.finalOutbound))
        }
        if (patch.isEmpty()) return
        val headers = mapOf("Content-Type" to "application/json-patch+json", "If-Match" to "\"${before.revision}\"")
        val reply = api.expect("PATCH", "/api/v1/groups/$id/config", patch.toString(), ErrorCode.Busy, headers)
        val href = nativeJson.parseToJsonElement(reply).jsonObject["href"]?.jsonPrimitive?.content ?: return
        if (api.finished(href).status == OperationStatus.Failed) throw EngineException(ErrorCode.InvalidArgument)
    }

    private fun op(op: String, path: String, value: JsonElement): JsonObject = buildJsonObject {
        put("op", op)
        put("path", path)
        put("value", value)
    }

    private fun text(value: String?): JsonElement = value?.let(::JsonPrimitive) ?: JsonNull
}

/**
 * A group's `policy` and `config` as `GET /groups/{id}` embeds them, the document `/config`'s PATCH targets, as far as
 * the app edits it; [revision] is honk's `config_revision`, null for a group not yet written.
 */
@Serializable
internal data class HonkGroupConfig(
    val policy: HonkGroupPolicy,
    val config: HonkGroupOptions,
    @SerialName("config_revision") val revision: String? = null,
)

/** [kind] is honk's policy (`selector`, `urltest`, …); [native] the name the source writes, the same word for dae. */
@Serializable
internal data class HonkGroupPolicy(val kind: String, val native: String)

/** [defaultMemberId] is a direct member's id; [finalOutbound] an outbound's name; null leaves dae's default. */
@Serializable
internal data class HonkGroupOptions(
    @SerialName("default_member_id") val defaultMemberId: String?,
    @SerialName("final_outbound") val finalOutbound: String?,
)
