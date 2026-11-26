package dev.doona.android.vpn

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * honk response bodies under `src/test/resources/honk/<resource>/<name>.json`, each in the exact shape of honk's native
 * API (`native_api_openapi.yaml` of daeuniverse/honk#483). The contract examples are copied as they are; sample values
 * for the app's shot tests keep that shape.
 */
internal object HonkFixtures {
    fun body(name: String): String = checkNotNull(HonkFixtures::class.java.getResource("/honk/$name.json")) { "no fixture honk/$name.json" }.readText()

    /** The contract's examples for every read the policy pages make, as a running honk answers them. */
    fun running(): FakeHonk = FakeHonk().apply {
        on("GET", "/api/v1/groups", 200, body("groups/list"))
        on("GET", "/api/v1/groups/group-proxy", 200, body("groups/group-proxy"))
        on("GET", "/api/v1/nodes", 200, body("nodes/list"))
        on("GET", "/api/v1/providers", 200, body("providers/list"))
        on("GET", "/api/v1/rules", 200, body("rules/running"))
        on("GET", "/api/v1/dns/rules", 200, body("dns-rules/running"))
        on("GET", "/api/v1/runtime", 200, body("runtime/runtime-example"))
    }

    /**
     * The RV sample profile (the app's `rvSampleEngine` content: its 48 nodes, 8 groups, 2 subscriptions and 14 rules) as
     * a running honk serves it; [long] names three of its groups at length, zh and en mixed, as users name them by hand.
     */
    fun rvSample(long: Boolean = false): FakeHonk = FakeHonk().apply {
        val variant = if (long) "rv-sample-long" else "rv-sample"
        val groups = body("groups/$variant")
        on("GET", "/api/v1/groups", 200, groups)
        for (id in Json.parseToJsonElement(groups).jsonArray.map { it.jsonObject.getValue("id").jsonPrimitive.content }) {
            val name = "groups/$variant-$id".takeIf { exists(it) } ?: "groups/rv-sample-$id"
            on("GET", "/api/v1/groups/$id", 200, body(name))
        }
        on("GET", "/api/v1/nodes", 200, body("nodes/rv-sample"))
        on("GET", "/api/v1/providers", 200, body("providers/rv-sample"))
        on("GET", "/api/v1/rules", 200, body("rules/$variant"))
        on("GET", "/api/v1/runtime", 200, body("runtime/runtime-example"))
    }

    private fun exists(name: String) = HonkFixtures::class.java.getResource("/honk/$name.json") != null
}

/** A [NativeApi] that answers each `method path` with its last reply set by [on], as often as asked; [calls] in order. */
internal class FakeHonk : NativeApi {
    data class Call(val method: String, val path: String, val body: String?, val headers: Map<String, String>)

    val calls = mutableListOf<Call>()
    private val replies = mutableMapOf<String, NativeResponse>()

    fun on(method: String, path: String, status: Int, body: String) {
        replies["$method $path"] = NativeResponse(status, body)
    }

    @Synchronized
    override fun call(method: String, path: String, body: String?, headers: Map<String, String>): NativeResponse {
        calls += Call(method, path, body, headers)
        return checkNotNull(replies["$method $path"]) { "no reply for $method $path" }
    }
}
