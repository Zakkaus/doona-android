package dev.doona.android.vpn

/**
 * honk's native HTTP API served in-process: [path] is the request target with its query, such as
 * `/api/v1/providers?cursor=c1`, and [body] is a JSON document or null for none. Blocking; call it on `Dispatchers.IO`.
 */
fun interface NativeApi {
    fun call(method: String, path: String, body: String?): NativeResponse
}

/** The HTTP status and the response body, empty when honk sent none. */
data class NativeResponse(val status: Int, val body: String)
