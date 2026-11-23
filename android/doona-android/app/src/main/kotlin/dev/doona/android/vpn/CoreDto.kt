package dev.doona.android.vpn

import dev.doona.android.engine.EngineException
import dev.doona.android.engine.ErrorCode
import dev.doona.android.engine.Group
import dev.doona.android.engine.GroupKind
import dev.doona.android.engine.Member
import dev.doona.android.engine.Mode
import dev.doona.android.engine.Subscription
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/** The contract schema this app speaks (`plans/v1/core-api.md`). */
internal const val CORE_SCHEMA = 2

@Serializable
internal data class InitDto(val schema: Int)

/** The tun the service builds uses the same values. */
@Serializable
internal data class StartConfigDto(val mtu: Int, val ipv4: String, val dns: String, val bootstrapDns: List<String>) {
    companion object {
        const val MTU = 8500
        const val IPV4 = "172.19.0.1"
        const val IPV4_PREFIX = 30
        const val DNS = "172.19.0.2"

        fun of(bootstrapDns: List<String>, mtu: Int = MTU) = StartConfigDto(mtu, IPV4, DNS, bootstrapDns)
    }
}

@Serializable
internal data class SnapshotDto(
    val revision: Long,
    val running: Boolean,
    val generation: Long,
    val sinceEpochMs: Long? = null,
    /** An error code, set when the data path died after `start` returned. */
    val error: String? = null,
    /** An error code for a problem that leaves the data path up: `local_network`. */
    val warning: String? = null,
    val traffic: TrafficDto,
    /** The local day's bytes so far; an older core sends none. */
    val today: TodayDto = TodayDto(),
    val catalog: CatalogDto? = null,
) {
    val alive: Boolean get() = running && error == null
}

/** Bytes since `start`, the flows open now, and the session's fastest second each way (bytes). */
@Serializable
internal data class TrafficDto(
    val upTotal: Long,
    val downTotal: Long,
    val activeFlows: Int,
    val upPeak: Long = 0,
    val downPeak: Long = 0,
)

/** Today's bytes each way in local time, and yesterday's both ways together by the same time (null: none kept). */
@Serializable
internal data class TodayDto(val upTotal: Long = 0, val downTotal: Long = 0, val yesterdayByNow: Long? = null)

/**
 * `trafficSeries`' reply: average bytes a second per point of [stepSeconds], oldest first, the fastest second of the
 * window each way, and the wall time at the end of the last point.
 */
@Serializable
internal data class TrafficSeriesDto(
    val stepSeconds: Long,
    val endEpochMs: Long,
    val up: List<Long>,
    val down: List<Long>,
    val upPeak: Long,
    val downPeak: Long,
)

/** `usage`'s reply: today and yesterday by the hour, and the finished days of the last 31 that had bytes. */
@Serializable
internal data class UsageDto(val today: DayUsageDto, val yesterday: DayUsageDto? = null, val days: List<DayTotalDto>)

/** One local day ([date] as `2026-11-22`): 24 hours of bytes each way and of flows opened, and each outbound's bytes. */
@Serializable
internal data class DayUsageDto(
    val date: String,
    val up: List<Long>,
    val down: List<Long>,
    val flows: List<Long>,
    val outbounds: List<OutboundBytesDto>,
    /** Each rule's payload and flows in Rule mode, in id order: a row id, `unmatched`, or `other` past 64 in a day. */
    val byRule: List<RuleBytesDto> = emptyList(),
)

/** An outbound's payload: [id] is a group (`g-Proxy`), a node, `direct`, or `other` past 64 in a day. */
@Serializable
internal data class OutboundBytesDto(val id: String, val up: Long, val down: Long)

@Serializable
internal data class RuleBytesDto(val id: String, val up: Long, val down: Long, val flows: Long)

@Serializable
internal data class DayTotalDto(val date: String, val up: Long, val down: Long)

/** `nodes`' reply. */
@Serializable
internal data class NodesDto(val nodes: List<NodeInfoDto>)

/** What a node's share link says, and its last latency tests, oldest first. */
@Serializable
internal data class NodeInfoDto(
    val id: String,
    val protocol: String,
    val server: String,
    val port: Int,
    val transport: String,
    val tls: Boolean,
    val tests: List<LatencyTestDto>,
)

/** A test: when its round began, and the time it measured; null when the probe failed. */
@Serializable
internal data class LatencyTestDto(val atEpochMs: Long, val ms: Int? = null)

/** A mode as the core's JSON carries it; the engine has no Rule yet. */
@Serializable
internal enum class CoreMode {
    Global,
    Direct,
    Rule,
    ;

    /** Until K1, Rule shows as Global: the node card keeps the main group's node, which carries the flows no rule matched. */
    fun toMode(): Mode = when (this) {
        Global, Rule -> Mode.Global
        Direct -> Mode.Direct
    }

    companion object {
        fun of(mode: Mode): CoreMode = when (mode) {
            Mode.Global -> Global
            Mode.Direct -> Direct
        }
    }
}

/** [mainGroupId] is the group Global sends every flow through, and Rule the flows no rule matched. */
@Serializable
internal data class CatalogDto(
    val mode: CoreMode,
    val groups: List<GroupDto>,
    val subscriptions: List<SubscriptionDto>,
    val mainGroupId: String,
    val ruleSource: RuleSourceDto,
)

/**
 * Where Rule mode's rules come from and what compiled of them: [rules] rows, [lines] values, [skipped] lines not
 * compiled. [rulesRevision] is compared for equality only; [error] is `parse_failed` when the source did not compile.
 */
@Serializable
internal data class RuleSourceDto(
    val kind: String,
    /** The template's name and version; null for a source that is no template. */
    val template: String? = null,
    val version: Int? = null,
    /** The subscription the source came from; null for the template. */
    val subscriptionId: String? = null,
    val options: RuleOptionsDto,
    val rules: Int,
    val lines: Int,
    val skipped: Int,
    val rulesRevision: Long,
    val error: String? = null,
)

/** What a user can change in a template. No default: an argument must carry every option. */
@Serializable
internal data class RuleOptionsDto(val blockQuic: Boolean)

/** `rules`' reply: [total] rows in the source, [rows] the page asked for. */
@Serializable
internal data class RulesDto(val rulesRevision: Long, val total: Int, val rows: List<RuleRowDto>)

/**
 * One row of rules: [kind] is `DomainSuffix`, `GeoIp`, `Match` and the like, or the source's own name for a kind not
 * compiled; [sample] is the first value, null for `Match`. [targetKind] is `direct`, `reject` or `group`. [skipped]
 * says why [skippedLines] of the row's lines were not compiled.
 */
@Serializable
internal data class RuleRowDto(
    val id: String,
    val index: Int,
    val kind: String,
    val sample: String? = null,
    val count: Int,
    val target: String,
    val targetKind: String,
    val skipped: String? = null,
    val skippedLines: Int,
)

/** `effectiveConfig`'s reply: where flows and DNS go, and the rules as Clash-style text, capped at 1 MiB ([truncated]). */
@Serializable
internal data class EffectiveConfigDto(
    val mode: CoreMode,
    val mainGroupId: String,
    /** `direct` in Direct, else the main group's id. */
    val dnsVia: String,
    val dnsUpstreams: List<String>,
    val ruleSource: RuleSourceDto,
    val text: String,
    val truncated: Boolean,
)

@Serializable
internal data class GroupDto(
    val id: String,
    val name: String,
    val kind: GroupKind,
    val selectedId: String? = null,
    val pinned: Boolean,
    val testing: Boolean,
    val members: List<MemberDto>,
) {
    fun toGroup() = Group(id, name, kind, members.map(MemberDto::toMember), selectedId, pinned, testing)
}

@Serializable
internal data class MemberDto(
    val id: String,
    val name: String,
    val latencyMs: Int? = null,
    val failed: Boolean,
    /** Why the last probe failed, when the core knows: `local_network`. */
    val error: String? = null,
    val isGroup: Boolean,
    val subscriptionId: String? = null,
) {
    fun toMember() = Member(id, name, latencyMs, failed, isGroup, subscriptionId, error?.let(::errorCodeOf))
}

@Serializable
internal data class SubscriptionDto(
    val id: String,
    val name: String,
    val url: String,
    val nodes: Int,
    val updatedAtEpochMs: Long? = null,
    val error: String? = null,
    val usedBytes: Long? = null,
    val totalBytes: Long? = null,
    val expiresAtEpochMs: Long? = null,
) {
    /** The error becomes an [ErrorCode] name, the same vocabulary as [EngineException]. */
    fun toSubscription() = Subscription(
        id = id,
        name = name,
        url = url,
        nodes = nodes,
        updatedAtEpochMs = updatedAtEpochMs,
        error = error?.let { errorCodeOf(it).name },
        usedBytes = usedBytes,
        totalBytes = totalBytes,
        expiresAtEpochMs = expiresAtEpochMs,
    )
}

@Serializable
internal data class SelectArgs(val group: String, val memberId: String?)

@Serializable
internal data class GroupArgs(val group: String?)

@Serializable
internal data class AddSubscriptionArgs(val url: String, val name: String?)

@Serializable
internal data class IdArgs(val id: String)

@Serializable
internal data class ModeArgs(val mode: CoreMode)

/** `rules`' page: [limit] is 1 to 500. */
@Serializable
internal data class RulesArgs(val offset: Int, val limit: Int)

/** `setRuleSource`: schema 2 knows [kind] `template` only. */
@Serializable
internal data class RuleSourceArgs(val kind: String, val template: String, val options: RuleOptionsDto)

/** `trafficSeries`' window, 1 to 3600 seconds. */
@Serializable
internal data class SeriesArgs(val seconds: Long)

/** `nodes`: every node when [ids] is null. */
@Serializable
internal data class NodesArgs(val ids: List<String>?)

@Serializable
private data class CallErrorDto(val code: String)

@Serializable
private data class CallErrorReplyDto(val error: CallErrorDto)

private val ERROR_CODES = mapOf(
    "invalid_argument" to ErrorCode.InvalidArgument,
    "not_found" to ErrorCode.NotFound,
    "fetch_failed" to ErrorCode.FetchFailed,
    "parse_failed" to ErrorCode.ParseFailed,
    "no_nodes" to ErrorCode.NoNodes,
    "busy" to ErrorCode.Busy,
    "internal" to ErrorCode.Internal,
    "local_network" to ErrorCode.LocalNetwork,
)

/** An unknown code from a newer core is [ErrorCode.Internal]. */
internal fun errorCodeOf(code: String): ErrorCode = ERROR_CODES[code] ?: ErrorCode.Internal

internal object CoreJson {
    private val json = Json { ignoreUnknownKeys = true }

    fun schema(text: String): Int = json.decodeFromString(InitDto.serializer(), text).schema

    fun snapshot(text: String): SnapshotDto = json.decodeFromString(SnapshotDto.serializer(), text)

    fun <T> encode(serializer: SerializationStrategy<T>, value: T): String = json.encodeToString(serializer, value)

    /** The `ok` value of a `call` reply. Throws [EngineException] for an `error` reply. */
    fun <T> result(text: String, deserializer: DeserializationStrategy<T>): T {
        val reply = json.parseToJsonElement(text).jsonObject
        if ("error" in reply) {
            throw EngineException(errorCodeOf(json.decodeFromJsonElement(CallErrorReplyDto.serializer(), reply).error.code))
        }
        return json.decodeFromJsonElement(deserializer, reply.getValue("ok"))
    }
}
