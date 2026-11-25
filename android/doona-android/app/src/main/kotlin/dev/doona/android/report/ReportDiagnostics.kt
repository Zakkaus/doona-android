package dev.doona.android.report

import dev.doona.android.engine.CoreDiagnostics
import dev.doona.android.engine.EngineException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/** Project the diagnostics schema; unknown keys and arbitrary string values never reach the report. */
internal fun ReportScrubber.diagnostics(core: Result<CoreDiagnostics>, crashes: List<String>): JsonObject {
    val listing = core.getOrElse { error ->
        return buildJsonObject { put("error", (error as? EngineException)?.code?.name ?: "Internal") }
    }
    val reply = try {
        Json.parseToJsonElement(listing.json) as? JsonObject
    } catch (_: SerializationException) {
        null
    } ?: return buildJsonObject { put("error", "unreadable") }
    return buildJsonObject {
        reply.obj("core")?.let { put("core", scrubBuild(it)) }
        copyChoice(reply, "level", CORE_LEVELS)
        reply.array("events")?.let { put("events", JsonArray(it.mapNotNull { e -> (e as? JsonObject)?.let(::coreEvent) })) }
        copyNumber(reply, "overwritten")
        when (val snapshot = reply["snapshot"]) {
            is JsonObject -> put("snapshot", coreSnapshot(snapshot))
            JsonNull -> put("snapshot", JsonNull)
            else -> Unit
        }
        reply.obj("stats")?.let { put("stats", coreStats(it)) }
        putJsonArray("crashes") {
            listing.crashes.forEach { file ->
                if (file.name in crashes) {
                    addJsonObject {
                        put("name", file.name)
                        put("bytes", file.bytes)
                    }
                }
            }
        }
        copyNumber(reply, "crashMark")
    }
}

private fun ReportScrubber.scrubBuild(source: JsonObject) = buildJsonObject {
    put("version", version(source.string("version")))
    copyNumber(source, "schema")
    copyChoice(source, "profile", BUILD_TYPES)
    source.string("target")?.takeIf(CORE_TARGET::matches)?.let { put("target", it) }
    source.array("features")?.let { put("features", JsonArray(it.filter { feature -> feature == FAULT_FEATURE })) }
}

private fun coreEvent(source: JsonObject): JsonObject? {
    val name = source.string("name")?.takeIf { it in CORE_EVENTS } ?: return null
    return buildJsonObject {
        copyNumber(source, "atEpochMs")
        copyChoice(source, "level", CORE_LEVELS)
        put("name", name)
        copyChoice(source, "code", EVENT_CODES)
        copyChoice(source, "detail", CORE_DETAILS)
        source.obj("counts")?.let { put("counts", buildJsonObject { copyNumbers(it, COUNTS) }) }
        copyNumber(source, "ms")
    }
}

private fun coreSnapshot(source: JsonObject) = buildJsonObject {
    copyNumber(source, "atEpochMs")
    copyNumber(source, "revision")
    (source["running"] as? JsonPrimitive)?.takeIf { !it.isString && it.booleanOrNull != null }?.let { put("running", it) }
    copyNumber(source, "generation")
    copyChoice(source, "error", CORE_ERROR_CODES)
    copyChoice(source, "warning", CORE_ERROR_CODES)
}

private fun coreStats(source: JsonObject) = buildJsonObject {
    source.obj("traffic")?.let { put("traffic", buildJsonObject { copyNumbers(it, TRAFFIC_NUMBERS) }) }
    source.obj("today")?.let { put("today", buildJsonObject { copyNumbers(it, TODAY_NUMBERS) }) }
}

private fun JsonObject.obj(key: String) = this[key] as? JsonObject

private fun JsonObject.array(key: String) = this[key] as? JsonArray

private fun JsonObject.string(key: String) = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonObjectBuilder.copyChoice(source: JsonObject, key: String, choices: Set<String>) {
    val value = source[key] as? JsonPrimitive ?: return
    if (value == JsonNull || (value.isString && value.content in choices)) put(key, value)
}

private fun JsonObjectBuilder.copyNumbers(source: JsonObject, keys: List<String>) {
    for (key in keys) copyNumber(source, key)
}

private fun JsonObjectBuilder.copyNumber(source: JsonObject, key: String) {
    val value = source[key] as? JsonPrimitive ?: return
    if (value == JsonNull || (!value.isString && value.longOrNull != null)) put(key, value)
}

private val CORE_ERROR_CODES = setOf("invalid_argument", "not_found", "fetch_failed", "too_large", "parse_failed", "no_nodes", "busy", "internal", "local_network")
private val IO_CODES = setOf("not_found", "permission_denied", "storage_full", "read_only", "invalid_data", "timed_out", "io")
private val EVENT_CODES = CORE_ERROR_CODES + IO_CODES
private val CORE_LEVELS = setOf("error", "warn", "info", "debug")
private val CORE_TARGET = Regex("(?:aarch64|arm|x86_64|x86|riscv64)-android")
private val FAULT_FEATURE = JsonPrimitive("fault-hook")
private val CORE_EVENTS = REPORT_COMMANDS + setOf(
    "init", "unknown", "debugFault", "stateWrite", "loadSubscription", "probeRound", "jni", "panic", "died",
    "warning", "stopped", "statsRead", "statsSetAside", "statsWrite",
)
private val CORE_DETAILS = REPORT_COMMANDS + CORE_LEVELS + IO_CODES + setOf(
    "Global", "Direct", "Rule", "cancelled", "stateSetAside", "repeated", "cleared", "detached", "carriersOpen", "stats.json", "latency.json",
    "dnsUpstreamsReset",
)
private val COUNTS = listOf("generation", "nodes", "subscriptions", "probes", "failed", "tasks", "files")
private val TRAFFIC_NUMBERS = listOf("upTotal", "downTotal", "activeFlows", "upPeak", "downPeak")
private val TODAY_NUMBERS = listOf("upTotal", "downTotal", "yesterdayByNow")
