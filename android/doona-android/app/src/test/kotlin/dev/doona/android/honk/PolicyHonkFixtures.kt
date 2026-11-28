package dev.doona.android.honk

import dev.doona.android.engine.fake.RvSampleConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** [HonkFixtures] with what the policy pages read besides the catalog and the rules: honk's config and DNS rules. */
internal object PolicyHonkFixtures {
    /** [HonkFixtures.running] with the contract's editable config. */
    fun running(): FakeHonk = HonkFixtures.running().apply {
        on("GET", "/api/v1/config", 200, HonkFixtures.body("config/editable"))
    }

    /** [HonkFixtures.rvSample] with the editable config (`cn-direct`, ad blocking on) and GeoSite loaded. */
    fun rvSample(long: Boolean = false): FakeHonk = HonkFixtures.rvSample(long).apply {
        on("GET", "/api/v1/config", 200, HonkFixtures.body("config/rv-sample"))
        on("GET", "/api/v1/geodata", 200, HonkFixtures.body("geodata/rv-sample"))
        on("GET", "/api/v1/dns/rules", 200, HonkFixtures.body("dns-rules/rv-sample"))
    }

    /** [rvSample] with the routing of its file edited by hand (a rule of the user's own), so it is no template. */
    fun handWritten(): FakeHonk = rvSample().apply {
        on("GET", "/api/v1/config", 200, HonkFixtures.body("config/rv-sample-hand"))
    }

    /**
     * `GET /groups` as honk lists the groups of `config/rv-sample-hand`, by their names in that file, then [extra];
     * [handWritten] keeps the sample's list, whose details the rule pages read.
     */
    fun handGroups(vararg extra: String): String {
        val group = Json.parseToJsonElement(HonkFixtures.body("groups/rv-sample")).jsonArray.first().jsonObject
        val names = listOf("proxy", "auto", "media", "ai", "hk", "tw", "jp", "sg", "us", "kr") + extra
        return JsonArray(names.map { JsonObject(group + ("id" to JsonPrimitive("g-$it")) + ("name" to JsonPrimitive(it))) }).toString()
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
        on("GET", "/api/v1/providers", 200, HonkFixtures.body("providers/rv-sample").replace("\"node_count\":0", "\"node_count\":1"))
        on("GET", "/api/v1/rules", 200, HonkFixtures.body("rules/honk-groups"))
    }

    /**
     * [rvSample] on the configuration pages' six-file tree (config/rv-config, the sample config of p14) with its nine
     * revisions; honk's check passes, a write of rules/cn.dae is accepted and its reload succeeds.
     */
    fun configTree(): FakeHonk = rvSample().apply {
        on("GET", "/api/v1/config", 200, HonkFixtures.body("config/rv-config"))
        on("GET", "/api/v1/x-honk/config/export", 200, RvSampleConfig.exported)
        on("GET", "/api/v1/x-honk/config/revisions", 200, HonkFixtures.body("revisions/rv-config"))
        on("POST", "/api/v1/config/validate", 200, HonkFixtures.body("config-validate/valid"))
        on("PUT", "/api/v1/config/sources/source-f4", 202, HonkFixtures.body("config-replace/queued"))
        on("POST", "/api/v1/x-honk/config/revisions/8/activate", 202, HonkFixtures.body("config-replace/queued"))
        on("GET", "/api/v1/operations/op-config-01", 200, HonkFixtures.body("operations/reload-complete"))
    }
}
