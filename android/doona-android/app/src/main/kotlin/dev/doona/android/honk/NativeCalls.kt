package dev.doona.android.honk

import dev.doona.android.engine.EngineException
import dev.doona.android.engine.ErrorCode
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.Json

/** honk's JSON as the app reads it: fields it has no use for are skipped. */
internal val nativeJson = Json { ignoreUnknownKeys = true }

/**
 * [method] [path] decoded with [serializer]; a status outside 2xx is an [EngineException], with [conflict] for a 409 (a
 * conflict with honk's state, which only the call can name). Blocking: run it on `Dispatchers.IO`.
 */
internal fun <T> NativeApi.read(
    method: String,
    path: String,
    serializer: DeserializationStrategy<T>,
    body: String? = null,
    conflict: ErrorCode = ErrorCode.Busy,
): T = nativeJson.decodeFromString(serializer, expect(method, path, body, conflict))

/** The body of a 2xx reply to [method] [path]; see [read]. */
internal fun NativeApi.expect(
    method: String,
    path: String,
    body: String? = null,
    conflict: ErrorCode = ErrorCode.Busy,
    headers: Map<String, String> = emptyMap(),
): String {
    val response = call(method, path, body, headers)
    if (response.status in 200..299) return response.body
    throw EngineException(nativeErrorCode(response.status, conflict))
}

/**
 * The status of honk's error envelope decides the code. 410 (an expired cursor), 412 (a file changed since it was read)
 * and 503 (honk cannot take the request now) can be retried, so they are [ErrorCode.Busy].
 */
private fun nativeErrorCode(status: Int, conflict: ErrorCode) = when (status) {
    400, 422 -> ErrorCode.InvalidArgument
    404 -> ErrorCode.NotFound
    409 -> conflict
    410, 412, 503 -> ErrorCode.Busy
    413 -> ErrorCode.TooLarge
    else -> ErrorCode.Internal
}
