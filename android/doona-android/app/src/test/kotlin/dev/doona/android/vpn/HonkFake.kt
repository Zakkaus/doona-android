package dev.doona.android.vpn

/**
 * A [NativeApi] that answers each `method path` with the replies queued for it, the last one again once the queue is
 * down to it, so a page that reads on a timer keeps its answer. A request with no reply fails the test.
 */
internal class HonkFake : NativeApi {
    data class Call(val method: String, val path: String, val body: String?, val headers: Map<String, String>)

    val calls = mutableListOf<Call>()
    private val replies = mutableMapOf<String, ArrayDeque<NativeResponse>>()

    fun on(method: String, path: String, status: Int, body: String) {
        replies.getOrPut("$method $path") { ArrayDeque() }.addLast(NativeResponse(status, body))
    }

    fun get(path: String, body: String) = on("GET", path, 200, body)

    override fun call(method: String, path: String, body: String?, headers: Map<String, String>): NativeResponse {
        calls += Call(method, path, body, headers)
        val queue = checkNotNull(replies["$method $path"]) { "no reply for $method $path" }
        return if (queue.size > 1) queue.removeFirst() else queue.first()
    }
}

/**
 * Response bodies in honk's native API schema, one file per response under `src/test/resources/honk/`: `*-example.json`
 * are the examples of honk's contract (`native_api_openapi.yaml`, pr/483) as they stand; `*-sample.json` carry the approved
 * sample's values (rvSampleEngine's) in the same schema.
 */
internal object HonkExamples {
    const val RUNTIME_PATH = "/api/v1/runtime"
    const val HISTORY_PATH = "/api/v1/runtime/traffic/history?window_seconds=600&max_points=600"
    const val OUTBOUNDS_PATH = "/api/v1/runtime/outbounds"

    val RUNTIME get() = fixture("runtime/runtime-example.json")
    val HISTORY get() = fixture("runtime/traffic-history-example.json")
    val OUTBOUNDS get() = fixture("runtime/outbounds-example.json")
    val UNAVAILABLE get() = fixture("errors/temporarily-unavailable-example.json")

    /** The body in `honk/[name]` on the test classpath. */
    fun fixture(name: String): String = checkNotNull(HonkExamples::class.java.getResource("/honk/$name")) { "no fixture $name" }.readText()
}
