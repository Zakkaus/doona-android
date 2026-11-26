package dev.doona.android.vpn.honk

import dev.doona.android.engine.EngineException
import dev.doona.android.engine.ErrorCode
import dev.doona.android.vpn.NativeApi
import dev.doona.android.vpn.expect
import dev.doona.android.vpn.nativeJson
import dev.doona.android.vpn.read
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * honk's configuration files through its native API: the accepted sources with their text, and a source replaced, which
 * honk validates and reloads. Blocking: run on `Dispatchers.IO`.
 */
internal class NativeConfig(private val api: NativeApi) {
    fun config(): HonkConfig = api.read("GET", "/api/v1/config", HonkConfig.serializer())

    /** The accepted files inlined into one document, listener secrets left out (`GET /x-honk/config/export`). */
    fun export(): String = api.expect("GET", "/api/v1/x-honk/config/export")

    /**
     * Replaces [source] with [content] and waits for honk's reload. A source changed since it was read fails with
     * [ErrorCode.Busy], so the caller reads again; text honk does not accept fails with [ErrorCode.InvalidArgument].
     */
    suspend fun replace(source: HonkSource, content: String) {
        replace(source.id, source.sha256, content)
    }

    /** [replace] by the source's [id] and the [sha256] of the bytes the edit started from. */
    suspend fun replace(id: String, sha256: String, content: String) {
        val body = buildJsonObject { put("content", content) }.toString()
        val accepted = api.expect("PUT", "/api/v1/config/sources/$id", body, headers = mapOf("If-Match" to "\"$sha256\""))
        val operation = nativeJson.decodeFromString(OperationAccepted.serializer(), accepted).href
        if (api.finished(operation).status == OperationStatus.Failed) throw EngineException(ErrorCode.InvalidArgument)
    }

    /**
     * honk's full check of a candidate, nothing written: [candidates] are (path, text) pairs, the first the entry file,
     * each laid over the accepted file at its path (`POST /config/validate`, mode `full`).
     */
    fun validate(candidates: List<Pair<String, String>>): HonkValidation {
        val body = buildJsonObject {
            put("mode", "full")
            putJsonArray("sources") {
                candidates.forEachIndexed { index, (path, content) ->
                    addJsonObject {
                        put("id", "candidate-$index")
                        put("path", path)
                        put("content", content)
                    }
                }
            }
        }.toString()
        return api.read("POST", "/api/v1/config/validate", HonkValidation.serializer(), body)
    }
}

/** The accepted configuration; [store] is honk's configuration db, whose revision the history lists. */
@Serializable
internal class HonkConfig(val sources: List<HonkSource>, @SerialName("x-honk") val honk: HonkConfigExtensions? = null) {
    /** The entry file. */
    val main: HonkSource get() = sources.first { it.kind == MAIN }

    /** The files honk reads as written, entry first: the ones the configuration pages list and edit. */
    val files: List<HonkSource> get() = sources.filter { it.kind == MAIN || it.kind == INCLUDE }

    val revision: Int get() = honk?.store?.revision ?: 0
}

private const val MAIN = "main"
private const val INCLUDE = "include"

@Serializable
internal class HonkConfigExtensions(val store: HonkStore? = null)

@Serializable
internal class HonkStore(val kind: String, val revision: Int? = null)

/** [kind] is `main`, `include`, `subscription` or `generated`; only a [writable] source can be replaced. */
@Serializable
internal class HonkSource(
    val id: String,
    val path: String,
    val kind: String,
    @SerialName("content_sha256") val sha256: String,
    val content: String,
    val writable: Boolean,
    @SerialName("line_count") val lineCount: Int,
    @SerialName("loaded_at") val loadedAt: String,
)

/** A full check's verdict: [valid] false with at least one error among the [diagnostics]. */
@Serializable
internal class HonkValidation(val valid: Boolean, val diagnostics: List<HonkDiagnostic>)

/** Where a diagnostic lies: one-based lines and byte columns, the end column one past the last byte. */
@Serializable
internal class HonkSpan(
    @SerialName("start_line") val startLine: Int,
    @SerialName("start_column") val startColumn: Int,
    @SerialName("end_line") val endLine: Int,
    @SerialName("end_column") val endColumn: Int,
) {
    /** The UTF-8 byte offsets `[start, end)` of this span in [text]. */
    fun bytes(text: String): Pair<Int, Int> {
        val lines = text.encodeToByteArray().let { bytes -> listOf(0) + bytes.indices.filter { bytes[it] == NEWLINE }.map { it + 1 } }
        return lines[startLine - 1] + startColumn - 1 to lines[endLine - 1] + endColumn - 1
    }
}

private const val NEWLINE = '\n'.code.toByte()
