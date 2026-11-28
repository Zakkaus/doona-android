package dev.doona.android.honk

/**
 * honk's native HTTP API served in-process: [path] is the request target with its query, such as
 * `/api/v1/providers?cursor=c1`, [body] is a JSON document or null for none, and [headers] go with the request as given,
 * such as `If-Match`; without a `Content-Type` the core sends `application/json`. Blocking; call it on `Dispatchers.IO`.
 */
interface NativeApi {
    fun call(method: String, path: String, body: String?, headers: Map<String, String> = emptyMap()): NativeResponse
}

/** The HTTP status and the response body, empty when honk sent none. */
data class NativeResponse(val status: Int, val body: String)
