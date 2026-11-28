package dev.doona.android.vpn.honk

import dev.doona.android.engine.EngineException
import dev.doona.android.engine.ErrorCode
import dev.doona.android.vpn.NativeApi
import dev.doona.android.vpn.expect
import dev.doona.android.vpn.nativeJson
import dev.doona.android.vpn.read
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
    fun read(id: String): HonkGroupConfig = api.read("GET", "/api/v1/groups/$id/config", HonkGroupConfig.serializer())

    /**
     * Writes what [after] changes from [before], the config read. Each changed field is first tested for its value in
     * [before], so a change made elsewhere since the read fails as [ErrorCode.Busy] (honk's 409) and is not overwritten;
     * honk's own revision check is `*`, as the app reads no response headers. A reload that fails is
     * [ErrorCode.InvalidArgument]. Nothing changed sends nothing.
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
        val reply = api.expect("PATCH", "/api/v1/groups/$id/config", patch.toString(), ErrorCode.Busy, PATCH_HEADERS)
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

/** The body of `GET /groups/{id}/config`, as far as the app edits it. */
@Serializable
internal data class HonkGroupConfig(val policy: HonkGroupPolicy, val config: HonkGroupOptions)

/** [kind] is honk's policy (`selector`, `urltest`, …); [native] the name the source writes, the same word for dae. */
@Serializable
internal data class HonkGroupPolicy(val kind: String, val native: String)

/** [defaultMemberId] is a direct member's id; [finalOutbound] an outbound's name; null leaves dae's default. */
@Serializable
internal data class HonkGroupOptions(
    @SerialName("default_member_id") val defaultMemberId: String?,
    @SerialName("final_outbound") val finalOutbound: String?,
)

private val PATCH_HEADERS = mapOf("Content-Type" to "application/json-patch+json", "If-Match" to "*")
