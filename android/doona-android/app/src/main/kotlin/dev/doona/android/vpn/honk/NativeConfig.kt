package dev.doona.android.vpn.honk

import dev.doona.android.engine.EngineException
import dev.doona.android.engine.ErrorCode
import dev.doona.android.vpn.NativeApi
import dev.doona.android.vpn.expect
import dev.doona.android.vpn.nativeJson
import dev.doona.android.vpn.read
import kotlinx.coroutines.delay
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * honk's configuration files through its native API: the accepted sources with their text, and a source replaced, which
 * honk validates and reloads. Blocking: run on `Dispatchers.IO`.
 */
internal class NativeConfig(private val api: NativeApi) {
    fun config(): HonkConfig = api.read("GET", "/api/v1/config", HonkConfig.serializer())

    /**
     * Replaces [source] with [content] and waits for honk's reload. A source changed since it was read fails with
     * [ErrorCode.Busy], so the caller reads again; text honk does not accept fails with [ErrorCode.InvalidArgument].
     */
    suspend fun replace(source: HonkSource, content: String) {
        val body = buildJsonObject { put("content", content) }.toString()
        val accepted = api.expect("PUT", "/api/v1/config/sources/${source.id}", body, headers = mapOf("If-Match" to "\"${source.sha256}\""))
        val operation = nativeJson.decodeFromString(HonkAccepted.serializer(), accepted).href
        while (true) {
            delay(POLL_MS)
            when (api.read("GET", operation, HonkReload.serializer()).status) {
                "succeeded" -> return
                "failed", "cancelled" -> throw EngineException(ErrorCode.InvalidArgument)
            }
        }
    }

    private companion object {
        /** honk's operations ask to be polled no more than once a second (`Retry-After: 1`). */
        const val POLL_MS = 1_000L
    }
}

@Serializable
internal class HonkConfig(val sources: List<HonkSource>)

/** [kind] is `main`, `include`, `subscription` or `generated`; only a [writable] source can be replaced. */
@Serializable
internal class HonkSource(
    val id: String,
    val path: String,
    val kind: String,
    @SerialName("content_sha256") val sha256: String,
    val content: String,
    val writable: Boolean,
)

@Serializable
internal class HonkAccepted(val href: String)

@Serializable
internal class HonkReload(val status: String)
