package dev.doona.android.vpn

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * honk as 活動 reads it: the RV sample profile ([HonkFixtures.rvSample]) with the sample's traffic now, its last minute
 * and its bytes per outbound (the `-sample.json` files of `honk/runtime`). Each change keeps honk's response schema.
 */
internal object ActivityHonkFixtures {
    const val HERO_HISTORY_PATH = "/api/v1/runtime/traffic/history?window_seconds=60&max_points=60"
    const val PROBES_PATH = "/api/v1/probes"

    fun sample(long: Boolean = false): FakeHonk = HonkFixtures.rvSample(long).apply {
        on("GET", HonkExamples.RUNTIME_PATH, OK, HonkExamples.fixture("runtime/runtime-sample.json"))
        on("GET", HERO_HISTORY_PATH, OK, HonkExamples.fixture("runtime/traffic-history-minute-sample.json"))
        on("GET", HonkExamples.OUTBOUNDS_PATH, OK, HonkExamples.fixture("runtime/outbounds-sample.json"))
    }

    /**
     * [sample]'s profile and traffic served by this honk, such as the app's own ([HonkFixtures.running]) in a test that
     * draws the whole app.
     */
    fun FakeHonk.servingSample() = apply {
        on("GET", "/api/v1/groups", OK, HonkFixtures.body("groups/rv-sample"))
        for (id in GROUP_IDS) on("GET", "/api/v1/groups/$id", OK, HonkFixtures.body("groups/rv-sample-$id"))
        on("GET", "/api/v1/nodes", OK, HonkFixtures.body("nodes/rv-sample"))
        on("GET", "/api/v1/providers", OK, HonkFixtures.body("providers/rv-sample"))
        on("GET", "/api/v1/rules", OK, HonkFixtures.body("rules/rv-sample"))
        on("GET", HonkExamples.RUNTIME_PATH, OK, HonkExamples.fixture("runtime/runtime-sample.json"))
        on("GET", HERO_HISTORY_PATH, OK, HonkExamples.fixture("runtime/traffic-history-minute-sample.json"))
        on("GET", HonkExamples.OUTBOUNDS_PATH, OK, HonkExamples.fixture("runtime/outbounds-sample.json"))
    }

    /** honk not running: no traffic now and no samples, as a stopped tunnel leaves it; the profile stays readable. */
    fun FakeHonk.idle() = apply {
        on("GET", HonkExamples.RUNTIME_PATH, UNAVAILABLE, HonkExamples.UNAVAILABLE)
        on("GET", HERO_HISTORY_PATH, UNAVAILABLE, HonkExamples.UNAVAILABLE)
    }

    /** Every node unavailable at its last probe. */
    fun FakeHonk.allUnavailable() = apply {
        val nodes = HonkFixtures.body("nodes/rv-sample").edit { list ->
            list.with("nodes", JsonArray(list.array("nodes").map { node -> node.with("health", JsonArray(node.array("health").map { it.jsonObject.unavailable() })) }))
        }
        on("GET", "/api/v1/nodes", OK, nodes)
    }

    /** No subscription and no node: the profile's groups have no members. */
    fun FakeHonk.empty() = apply {
        on("GET", "/api/v1/providers", OK, HonkFixtures.body("providers/rv-sample").edit { it.with("providers", JsonArray(emptyList())) })
        on("GET", "/api/v1/nodes", OK, HonkFixtures.body("nodes/rv-sample").edit { it.with("nodes", JsonArray(emptyList())) })
        val groups = HonkFixtures.body("groups/rv-sample").edit { list -> JsonArray(list.jsonArray.map { it.jsonObject.with("member_count", JsonPrimitive(0)) }) }
        on("GET", "/api/v1/groups", OK, groups)
        for (id in GROUP_IDS) on("GET", "/api/v1/groups/$id", OK, HonkFixtures.body("groups/rv-sample-$id").edit { it.with("members", JsonArray(emptyList())) })
    }

    /**
     * The sample with its HK group on Score, as 統計's Score card reads it: TCP on Hong Kong 02 IPLC as the sample has it,
     * UDP on Hong Kong 01.
     */
    fun FakeHonk.scoring() = apply {
        val score = JsonObject(mapOf("kind" to JsonPrimitive("score"), "native" to JsonPrimitive("score")))
        val groups = HonkFixtures.body("groups/rv-sample").edit { list -> JsonArray(list.jsonArray.map { if (it.id() == "g-HK") it.with("policy", score) else it }) }
        on("GET", "/api/v1/groups", OK, groups)
        val hk = HonkFixtures.body("groups/rv-sample-g-HK").edit { group ->
            val runtime = group.jsonObject.getValue("runtime")
            val selection = runtime.jsonObject.getValue("selection")
            val udp = JsonObject(mapOf("member_id" to JsonPrimitive(HK_01), "resolved_leaf_node_id" to JsonPrimitive(HK_01), "source" to JsonPrimitive("health")))
            group.with("policy", score).with("runtime", runtime.with("selection", selection.with("udp", udp)))
        }
        on("GET", "/api/v1/groups/g-HK", OK, hk)
    }

    /** A fresh install: no provider, and no node probed yet. */
    fun FakeHonk.untested() = apply {
        on("GET", "/api/v1/providers", OK, HonkFixtures.body("providers/rv-sample").edit { it.with("providers", JsonArray(emptyList())) })
        val nodes = HonkFixtures.body("nodes/rv-sample").edit { list -> list.with("nodes", JsonArray(list.array("nodes").map { it.with("health", JsonArray(emptyList())) })) }
        on("GET", "/api/v1/nodes", OK, nodes)
    }

    /** Providers that sent no `subscription-userinfo`: no quota and no expiry. */
    fun FakeHonk.noQuota() = apply {
        val providers = HonkFixtures.body("providers/rv-sample").edit { list ->
            list.with("providers", JsonArray(list.array("providers").map { it.with("traffic", JsonNull).with("expires_at", JsonNull) }))
        }
        on("GET", "/api/v1/providers", OK, providers)
    }

    /** Every probe refused: honk cannot take it now. */
    fun FakeHonk.refusingProbes() = apply { on("POST", PROBES_PATH, UNAVAILABLE, HonkExamples.UNAVAILABLE) }

    private fun JsonObject.unavailable() = JsonObject(this + mapOf("state" to JsonPrimitive("unavailable"), "latency_ms" to JsonNull, "moving_avg_ms" to JsonNull))

    private fun String.edit(change: (JsonElement) -> JsonElement): String = change(nativeJson.parseToJsonElement(this)).toString()

    private fun JsonElement.array(key: String) = jsonObject.getValue(key).jsonArray

    private fun JsonElement.id() = (jsonObject.getValue("id") as JsonPrimitive).content

    private fun JsonElement.with(key: String, value: JsonElement) = JsonObject(jsonObject + (key to value))

    private val GROUP_IDS = listOf("g-Proxy", "g-Auto", "g-Streaming", "g-AI", "g-HK", "g-TW", "g-JP", "g-US")
    private const val OK = 200
    private const val HK_01 = "n-0001938e00000000"
    private const val UNAVAILABLE = 503
}
