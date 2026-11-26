package dev.doona.android.report

import dev.doona.android.vpn.FakeHonk
import dev.doona.android.vpn.HonkFixtures

/** honk's reads for the report's tests, from the fixtures under `test/resources/honk/` (contract examples and samples in honk's shape). */
internal object ReportHonk {
    const val FLOWS = "/api/v1/flows?detail=full&limit=128"

    /**
     * A running honk whose catalog holds what a provider's subscription and a user's catalog can: a token URL, servers by
     * address and by name, in node, group and provider names and ids alike (the `report-secrets` fixtures).
     */
    fun secrets(): FakeHonk = FakeHonk().apply {
        on("GET", "/api/v1/version", 200, HonkFixtures.body("version/build"))
        on("GET", "/api/v1/runtime", 200, HonkFixtures.body("runtime/runtime-example"))
        on("GET", "/api/v1/providers", 200, HonkFixtures.body("providers/report-secrets"))
        on("GET", "/api/v1/nodes", 200, HonkFixtures.body("nodes/report-secrets"))
        on("GET", "/api/v1/groups", 200, HonkFixtures.body("groups/report-secrets"))
        on("GET", "/api/v1/groups/198.51.100.7", 200, HonkFixtures.body("groups/report-secrets-198.51.100.7"))
        on("GET", "/api/v1/groups/Auto", 200, HonkFixtures.body("groups/report-secrets-Auto"))
        on("GET", "/api/v1/rules", 200, HonkFixtures.body("rules/running"))
        on("GET", FLOWS, 200, HonkFixtures.body("flows/full"))
    }

    /** honk not running: every read fails, as the native API does with no core. */
    fun down(): FakeHonk = FakeHonk()
}
