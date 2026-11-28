package dev.doona.android.honk

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * honk's native API for 設定's tests and goldens: each `METHOD path` answers its queued replies in turn and then keeps
 * answering the last one; one with none fails as honk does when it is not running. The bodies are the fixtures under
 * `test/resources/honk/` (`geodata/`, `runtime-settings/`): the contract's examples of `native_api_openapi.yaml`, and
 * the sample's values in the same shape. Any other call goes to [others] when given.
 */
internal class SettingsHonk(private val others: NativeApi? = null) : NativeApi {
    data class Call(val method: String, val path: String, val body: String?)

    val calls = mutableListOf<Call>()
    private val replies = mutableMapOf<String, ArrayDeque<NativeResponse>>()

    /** Queues [body] with [status] for [method] [path]. */
    fun on(method: String, path: String, status: Int, body: String): SettingsHonk = apply {
        replies.getOrPut("$method $path") { ArrayDeque() }.addLast(NativeResponse(status, body))
    }

    /** From now on [method] [path] answers [body] with [status], whatever was queued. */
    fun now(method: String, path: String, status: Int, body: String): SettingsHonk = apply {
        replies["$method $path"] = ArrayDeque(listOf(NativeResponse(status, body)))
    }

    override fun call(method: String, path: String, body: String?, headers: Map<String, String>): NativeResponse {
        calls += Call(method, path, body)
        val queue = replies["$method $path"] ?: return others?.call(method, path, body, headers) ?: error("honk is not running")
        return if (queue.size > 1) queue.removeFirst() else queue.first()
    }

    companion object {
        /** honk running with the contract's loaded data and settings, taking every update and settings patch; [others] answers the rest. */
        fun running(others: NativeApi? = null): SettingsHonk = SettingsHonk(others)
            .on("GET", "/api/v1/geodata", 200, GEODATA)
            .on("GET", "/api/v1/runtime/settings", 200, RUNTIME_SETTINGS)
            .on("POST", "/api/v1/geodata/update", 202, GEODATA_QUEUED)
            .on("PATCH", "/api/v1/runtime/settings", 200, RUNTIME_SETTINGS)

        /** honk running with the sample's data (the APK's copy, never updated) and the sample's 每週 schedule. */
        fun sample(): SettingsHonk = SettingsHonk(PolicyHonkFixtures.rvSample())
            .on("GET", "/api/v1/geodata", 200, HonkFixtures.body("geodata/rv-sample"))
            .on("GET", "/api/v1/runtime/settings", 200, HonkFixtures.body("runtime-settings/rv-sample"))
            .on("POST", "/api/v1/geodata/update", 202, GEODATA_QUEUED)
            .on("PATCH", "/api/v1/runtime/settings", 200, HonkFixtures.body("runtime-settings/rv-sample"))

        /** `geodata/loaded` with [checked], [updated], [modified] (the geosite file's) and [error] as its last attempt. */
        fun geoData(
            checked: String? = "2026-08-15T03:12:40Z",
            updated: String? = "2026-08-14T03:00:00Z",
            error: JsonElement = JsonNull,
            modified: String = "2026-08-14T03:00:00Z",
        ): String {
            val loaded = Json.parseToJsonElement(GEODATA).jsonObject
            val assets = loaded.getValue("assets").jsonArray
            val geosite = JsonObject(assets[0].jsonObject + ("modified_at" to JsonPrimitive(modified)))
            return JsonObject(
                loaded + mapOf(
                    "assets" to JsonArray(listOf(geosite) + assets.drop(1)),
                    "last_checked_at" to JsonPrimitive(checked),
                    "last_updated_at" to JsonPrimitive(updated),
                    "last_error" to error,
                ),
            ).toString()
        }

        /** `runtime-settings/current` with [enabled] and [hours] as its automatic update. */
        fun runtimeSettings(enabled: Boolean = true, hours: Int = 24, cron: String? = null): String {
            val current = Json.parseToJsonElement(RUNTIME_SETTINGS).jsonObject
            val geodata = current.getValue("geodata").jsonObject
            val auto = buildJsonObject {
                put("enabled", enabled)
                put("interval_hours", hours)
                put("cron", JsonPrimitive(cron))
            }
            return JsonObject(current + ("geodata" to JsonObject(geodata + ("auto_update" to auto)))).toString()
        }

        val GEODATA = HonkFixtures.body("geodata/loaded")
        val RUNTIME_SETTINGS = HonkFixtures.body("runtime-settings/current")
        val GEODATA_QUEUED = HonkFixtures.body("geodata/update-queued")
        val GEODATA_IN_FLIGHT = HonkFixtures.body("geodata/update-in-flight")

        /** The `last_error` of `geodata/packaged`. */
        val GEODATA_ERROR: JsonElement = Json.parseToJsonElement(HonkFixtures.body("geodata/packaged")).jsonObject.getValue("last_error")
    }
}
