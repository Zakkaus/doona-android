package dev.doona.android.vpn.honk

import dev.doona.android.vpn.NativeApi
import dev.doona.android.vpn.nativeJson
import dev.doona.android.vpn.read
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The running generation's traffic and DNS rules through honk's native API, each list complete and in evaluation order,
 * ending with its fallback. Rule ids hold within one [HonkRules.generationId] only. Blocking: run on `Dispatchers.IO`.
 */
internal class NativeRouting(private val api: NativeApi) {
    fun rules(): HonkRules = api.read("GET", "/api/v1/rules", HonkRules.serializer())

    fun dnsRules(): HonkDnsRules = api.read("GET", "/api/v1/dns/rules", HonkDnsRules.serializer())

    /** How the running generation would route [input], simulated without dialing or resolving (`resolve: none`). */
    fun trace(input: HonkTraceInput): HonkTrace =
        api.read("POST", "/api/v1/routing/trace", HonkTrace.serializer(), nativeJson.encodeToString(HonkTraceRequest.serializer(), HonkTraceRequest(input)))

    /**
     * Whether honk's last reload left its files unloaded: they did not pass admission, so honk still runs the generation
     * before them, and the rules read are not the ones on disk.
     */
    fun sourcesRejected(): Boolean = api.read("GET", "/api/v1/runtime", HonkRuntimeReload.serializer()).lastReload.rejected
}

/** The part of `GET /runtime` the rule pages read. */
@Serializable
internal class HonkRuntimeReload(@SerialName("last_reload") val lastReload: HonkLastReload)

/** A reload that failed with nothing committed ([HonkReloadDetails.committed] false) kept the generation before it. */
@Serializable
internal class HonkLastReload(val status: String, val error: HonkReloadError?) {
    val rejected: Boolean get() = status == "failed" && error?.details?.committed == false
}

@Serializable
internal class HonkReloadError(val details: HonkReloadDetails? = null)

@Serializable
internal class HonkReloadDetails(val committed: Boolean? = null, val diagnostics: List<HonkDiagnostic> = emptyList())

@Serializable
internal class HonkRules(
    @SerialName("generation_id") val generationId: String,
    val rules: List<HonkRule>,
    val fallback: HonkRuleFallback,
)

/** [expression] is honk's display text, `condition -> outbound`; [outbound] is a group name, `direct` or `block`. */
@Serializable
internal class HonkRule(
    @SerialName("rule_id") val id: String,
    val index: Int,
    val expression: String,
    val outbound: String,
    val must: Boolean,
    val source: HonkSourceAt?,
    val kind: String,
) {
    val isFallback: Boolean get() = kind == FALLBACK
}

@Serializable
internal class HonkRuleFallback(val outbound: String, val source: HonkSourceAt?)

/** Where a rule is written: the source by its id from `GET /config`, at a 1-based [line] and byte [column]. */
@Serializable
internal class HonkSourceAt(val file: String, @SerialName("source_id") val sourceId: String, val line: Int, val column: Int?)

@Serializable
internal class HonkDnsRules(
    @SerialName("generation_id") val generationId: String,
    val request: List<HonkDnsRule>,
    val response: List<HonkDnsRule>,
)

/** [action] is `upstream`, `reject`, `accept` or `requery`; [upstream] names the upstream the first and last go to. */
@Serializable
internal class HonkDnsRule(
    @SerialName("rule_id") val id: String,
    val index: Int,
    val expression: String,
    val action: String,
    val upstream: String?,
    val source: HonkSourceAt?,
    val kind: String,
) {
    val isFallback: Boolean get() = kind == FALLBACK
}

/** A destination to trace: [domain] or [dstIp] (at least one), its port and transport. */
@Serializable
internal class HonkTraceInput(
    val network: Transport,
    @SerialName("dst_port") val dstPort: Int,
    val domain: String? = null,
    @SerialName("dst_ip") val dstIp: String? = null,
)

@Serializable
private class HonkTraceRequest(val input: HonkTraceInput)

@Serializable
internal class HonkTrace(@SerialName("generation_id") val generationId: String, val evaluations: List<HonkEvaluation>)

/**
 * One destination address's verdict: [outbound] is null when [decision] is `indeterminate`, as a rule before the winner
 * needs an input the trace lacks. [rules] are in evaluation order, each `matched`, `not_matched`, `indeterminate` or
 * `skipped` (not reached).
 */
@Serializable
internal class HonkEvaluation(val outbound: String?, val rules: List<HonkRuleEvaluation>)

@Serializable
internal class HonkRuleEvaluation(@SerialName("rule_id") val ruleId: String, val result: String)

private const val FALLBACK = "fallback"
