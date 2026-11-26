package dev.doona.android.vpn

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** [HonkFixtures] with what the policy pages read besides the catalog and the rules: honk's config and DNS rules. */
internal object PolicyHonkFixtures {
    /** [HonkFixtures.running] with the contract's editable config. */
    fun running(): FakeHonk = HonkFixtures.running().apply {
        on("GET", "/api/v1/config", 200, HonkFixtures.body("config/editable"))
    }

    /** [HonkFixtures.rvSample] with its config: template `cn-direct`, 封鎖廣告 on, and that config's export. */
    fun rvSample(long: Boolean = false): FakeHonk = HonkFixtures.rvSample(long).apply {
        on("GET", "/api/v1/config", 200, HonkFixtures.body("config/rv-sample"))
        on("GET", "/api/v1/dns/rules", 200, HonkFixtures.body("dns-rules/rv-sample"))
        on("GET", "/api/v1/x-honk/config/export", 200, exported("config/rv-sample"))
    }

    /**
     * The sample's honk-shaped groups (testDebug `honkGroupsEngine`): 出口 over 香港 and 日本, two subscription nodes
     * and Home Relay, added by hand; 香港, a url-test; 日本, a fallback; 空, a selector with no member. Its rules start
     * with one that refuses its flows, and their groups are not among these.
     */
    fun honkGroups(): FakeHonk = rvSample().apply {
        val groups = HonkFixtures.body("groups/honk-groups")
        on("GET", "/api/v1/groups", 200, groups)
        for (id in Json.parseToJsonElement(groups).jsonArray.map { it.jsonObject.getValue("id").jsonPrimitive.content }) {
            on("GET", "/api/v1/groups/$id", 200, HonkFixtures.body("groups/honk-groups-$id"))
        }
        on("GET", "/api/v1/nodes", 200, HonkFixtures.body("nodes/honk-groups"))
        on("GET", "/api/v1/rules", 200, HonkFixtures.body("rules/honk-groups"))
    }

    /** `GET /x-honk/config/export` of the config [name]: its files' text, one after another. */
    private fun exported(name: String) = Json.parseToJsonElement(HonkFixtures.body(name)).jsonObject.getValue("sources").jsonArray
        .joinToString("\n") { it.jsonObject.getValue("content").jsonPrimitive.content }
}
