package dev.doona.android.vpn

import dev.doona.android.engine.Catalog
import dev.doona.android.engine.CoreDiagnostics
import dev.doona.android.engine.CrashFile
import dev.doona.android.engine.DayTotal
import dev.doona.android.engine.DayUsage
import dev.doona.android.engine.EffectiveConfig
import dev.doona.android.engine.EngineException
import dev.doona.android.engine.ErrorCode
import dev.doona.android.engine.LatencyTest
import dev.doona.android.engine.LogLevel
import dev.doona.android.engine.Member
import dev.doona.android.engine.Mode
import dev.doona.android.engine.NodeInfo
import dev.doona.android.engine.OutboundBytes
import dev.doona.android.engine.RuleBytes
import dev.doona.android.engine.RuleKind
import dev.doona.android.engine.RuleOptions
import dev.doona.android.engine.RulePage
import dev.doona.android.engine.RuleRow
import dev.doona.android.engine.RuleSkip
import dev.doona.android.engine.RuleSource
import dev.doona.android.engine.RuleTarget
import dev.doona.android.engine.StaticNodeReason
import dev.doona.android.engine.StaticNodeResult
import dev.doona.android.engine.StaticNodeUpdateResult
import dev.doona.android.engine.Subscription
import dev.doona.android.engine.SubscriptionFormat
import dev.doona.android.engine.TodayTraffic
import dev.doona.android.engine.TrafficSeries
import dev.doona.android.engine.Usage
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonClassDiscriminator
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject

/** The contract schema this app speaks (`plans/v1/core-api.md`). */
internal const val CORE_SCHEMA = 3

@Serializable
internal data class InitDto(val schema: Int)

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
internal data class TodayDto(val upTotal: Long = 0, val downTotal: Long = 0, val yesterdayByNow: Long? = null) {
    fun toTodayTraffic() = TodayTraffic(upTotal, downTotal, yesterdayByNow)
}

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
) {
    fun toTrafficSeries() = TrafficSeries(stepSeconds, endEpochMs, up, down, upPeak, downPeak)
}

/** `usage`'s reply: today and yesterday by the hour, and the finished days of the last 31 that had bytes. */
@Serializable
internal data class UsageDto(val today: DayUsageDto, val yesterday: DayUsageDto? = null, val days: List<DayTotalDto>) {
    fun toUsage() = Usage(today.toDayUsage(), yesterday?.toDayUsage(), days.map { DayTotal(it.date, it.up, it.down) })
}

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
) {
    fun toDayUsage() = DayUsage(
        date = date,
        up = up,
        down = down,
        flows = flows,
        outbounds = outbounds.map { OutboundBytes(it.id, it.up, it.down) },
        byRule = byRule.map { RuleBytes(it.id, it.up, it.down, it.flows) },
    )
}

/** An outbound's payload: [id] is a group (`g-Proxy`), a node, `direct`, or `other` past 64 in a day. */
@Serializable
internal data class OutboundBytesDto(val id: String, val up: Long, val down: Long)

@Serializable
internal data class RuleBytesDto(val id: String, val up: Long, val down: Long, val flows: Long)

@Serializable
internal data class DayTotalDto(val date: String, val up: Long, val down: Long)

/** `nodes`' reply. */
@Serializable
internal data class NodesDto(val nodes: List<NodeInfoDto>) {
    fun toNodes() = nodes.map(NodeInfoDto::toNodeInfo)
}

/** What a node's share link says, and its last latency tests, oldest first. */
@Serializable
internal data class NodeInfoDto(
    val id: String,
    val subscriptionId: String?,
    val protocol: String,
    val server: String,
    val port: Int,
    val transport: String,
    val tls: Boolean,
    val tests: List<LatencyTestDto>,
) {
    fun toNodeInfo() = NodeInfo(id, subscriptionId, protocol, server, port, transport, tls, tests.map { LatencyTest(it.atEpochMs, it.ms) })
}

/** A test: when its round began, and the time it measured; null when the probe failed. */
@Serializable
internal data class LatencyTestDto(val atEpochMs: Long, val ms: Int? = null)

@OptIn(ExperimentalSerializationApi::class)
@JsonClassDiscriminator("status")
@Serializable
internal sealed interface StaticNodeRowDto {
    fun toRow(): StaticNodeResult

    @Serializable
    sealed interface Update : StaticNodeRowDto {
        override fun toRow(): StaticNodeUpdateResult
    }

    @Serializable
    @SerialName("ready")
    class Ready(val id: String, val name: String, val protocol: String) : StaticNodeRowDto {
        override fun toRow() = StaticNodeResult.Ready(id, name, protocol)
    }

    @Serializable
    @SerialName("added")
    class Added(val id: String, val name: String, val protocol: String) : StaticNodeRowDto {
        override fun toRow() = StaticNodeResult.Added(id, name, protocol)
    }

    @Serializable
    @SerialName("updated")
    class Updated(val id: String, val name: String, val protocol: String) : Update {
        override fun toRow() = StaticNodeResult.Updated(id, name, protocol)
    }

    @Serializable
    @SerialName("duplicate")
    class Duplicate(val id: String) : Update {
        override fun toRow() = StaticNodeResult.Duplicate(id)
    }

    @Serializable
    @SerialName("invalid")
    class Invalid(val reason: StaticNodeReason) : Update {
        override fun toRow() = StaticNodeResult.Invalid(reason)
    }

    @Serializable
    @SerialName("unsupported")
    class Unsupported(val reason: StaticNodeReason) : Update {
        override fun toRow() = StaticNodeResult.Unsupported(reason)
    }
}

/** [mainGroupId] is the group Global sends every flow through, and Rule the flows no rule matched. */
@Serializable
internal data class CatalogDto(
    val mode: Mode,
    val groups: List<GroupDto>,
    val subscriptions: List<SubscriptionDto>,
    val mainGroupId: String,
    val ruleSource: RuleSourceDto,
) {
    fun toCatalog() = Catalog(
        mode = mode,
        groups = groups.map(GroupDto::toGroup),
        subscriptions = subscriptions.map(SubscriptionDto::toSubscription),
        mainGroupId = mainGroupId,
        ruleSource = ruleSource.toRuleSource(),
    )
}

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
) {
    fun toRuleSource() =
        RuleSource(template, version, subscriptionId, options.toRuleOptions(), rules, lines, skipped, rulesRevision, error?.let(::errorCodeOf))
}

/** What a user can change in a template. No default: an argument must carry every option. */
@Serializable
internal data class RuleOptionsDto(val blockQuic: Boolean) {
    fun toRuleOptions() = RuleOptions(blockQuic)

    companion object {
        fun of(options: RuleOptions) = RuleOptionsDto(options.blockQuic)
    }
}

/** `rules`' reply: [total] rows in the source, [rows] the page asked for. */
@Serializable
internal data class RulesDto(val rulesRevision: Long, val total: Int, val rows: List<RuleRowDto>) {
    fun toRulePage() = RulePage(rulesRevision, total, rows.map(RuleRowDto::toRuleRow))
}

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
) {
    fun toRuleRow() = RuleRow(
        id = id,
        index = index,
        kind = RuleKind.entries.firstOrNull { it.name == kind },
        kindName = kind,
        sample = sample,
        count = count,
        target = ruleTargetOf(target, targetKind),
        skipped = skipped?.let { RULE_SKIPS[it] ?: RuleSkip.UnsupportedKind },
        skippedLines = skippedLines,
    )
}

/** `effectiveConfig`'s reply: where flows and DNS go, and the rules as Clash-style text, capped at 1 MiB ([truncated]). */
@Serializable
internal data class EffectiveConfigDto(
    val mode: Mode,
    val mainGroupId: String,
    /** `direct` in Direct, else the main group's id. */
    val dnsVia: String,
    val dnsUpstreams: List<String>,
    val ruleSource: RuleSourceDto,
    val text: String,
    val truncated: Boolean,
) {
    fun toEffectiveConfig() = EffectiveConfig(
        mode = mode,
        mainGroupId = mainGroupId,
        dnsVia = if (dnsVia == DIRECT) RuleTarget.Direct else RuleTarget.Group(dnsVia),
        dnsUpstreams = dnsUpstreams,
        ruleSource = ruleSource.toRuleSource(),
        text = text,
        truncated = truncated,
    )
}

private const val DIRECT = "direct"
private const val REJECT = "reject"

/** A rule's [target] by its [targetKind]: `direct`, `reject`, or a group, whose id is the target. */
private fun ruleTargetOf(target: String, targetKind: String): RuleTarget = when (targetKind) {
    DIRECT -> RuleTarget.Direct
    REJECT -> RuleTarget.Reject
    else -> RuleTarget.Group(target)
}

/** An unknown reason from a newer core reads as [RuleSkip.UnsupportedKind]. */
private val RULE_SKIPS = mapOf(
    "unsupported_kind" to RuleSkip.UnsupportedKind,
    "geodata_missing" to RuleSkip.GeodataMissing,
    "app_rule" to RuleSkip.AppRule,
)

@Serializable
internal data class MemberDto(
    val id: String,
    val name: String,
    val latencyMs: Int? = null,
    val failed: Boolean,
    /** Why the last probe failed, when the core knows: `dns_failed` or `local_network`. */
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
    val userAgent: String? = null,
    val format: String? = null,
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
        userAgent = userAgent,
        format = SUBSCRIPTION_FORMATS[format],
    )
}

private val SUBSCRIPTION_FORMATS = mapOf(
    "share_links" to SubscriptionFormat.ShareLinks,
    "base64" to SubscriptionFormat.Base64,
    "sip008" to SubscriptionFormat.Sip008,
    "clash" to SubscriptionFormat.Clash,
    "sing_box" to SubscriptionFormat.SingBox,
)

@Serializable
internal data class GroupArgs(val group: String?)

/** `addSubscription`: [userAgent] may be absent, as from a caller before it; absent or null is the default. */
@Serializable
internal data class AddSubscriptionArgs(val url: String, val name: String?, val userAgent: String? = null)

@Serializable
internal data class RenameSubscriptionArgs(val id: String, val name: String)

/** `setSubscriptionUserAgent`: the key is required, and null returns the subscription to the default. */
@Serializable
internal data class SubscriptionUserAgentArgs(val id: String, val userAgent: String?)

@Serializable
internal data class IdArgs(val id: String)

@Serializable
internal data class ModeArgs(val mode: Mode)

@Serializable
internal data class BootstrapDnsArgs(val servers: List<String>) {
    companion object {
        const val COMMAND = "setBootstrapDns"
    }
}

@Serializable
internal data class DnsUpstreamsArgs(val servers: List<String>)

/** `rules`' page: [limit] is 1 to 500. */
@Serializable
internal data class RulesArgs(val offset: Int, val limit: Int)

/** `setRuleSource`: schema 2 knows [kind] `template` only. */
@Serializable
internal data class RuleSourceArgs(val kind: String, val template: String, val options: RuleOptionsDto) {
    companion object {
        fun template(template: String, options: RuleOptions) = RuleSourceArgs("template", template, RuleOptionsDto.of(options))
    }
}

/** `trafficSeries`' window, 1 to 3600 seconds. */
@Serializable
internal data class SeriesArgs(val seconds: Long)

@Serializable
internal data class CallErrorDto(val code: String)

@Serializable
internal data class CallErrorReplyDto(val error: CallErrorDto)

private val ERROR_CODES = mapOf(
    "invalid_argument" to ErrorCode.InvalidArgument,
    "not_found" to ErrorCode.NotFound,
    "fetch_failed" to ErrorCode.FetchFailed,
    "too_large" to ErrorCode.TooLarge,
    "parse_failed" to ErrorCode.ParseFailed,
    "no_nodes" to ErrorCode.NoNodes,
    "busy" to ErrorCode.Busy,
    "internal" to ErrorCode.Internal,
    "local_network" to ErrorCode.LocalNetwork,
    "dns_failed" to ErrorCode.DnsFailed,
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

/** A diagnostics level as the core's JSON carries it: the ring keeps the events of its level and the more severe. */
@Serializable
internal enum class CoreLevel {
    @SerialName("error")
    Error,

    @SerialName("warn")
    Warn,

    @SerialName("info")
    Info,

    @SerialName("debug")
    Debug,
    ;

    fun toLogLevel(): LogLevel = when (this) {
        Error -> LogLevel.Error
        Warn -> LogLevel.Warn
        Info -> LogLevel.Info
        Debug -> LogLevel.Debug
    }

    companion object {
        fun of(level: LogLevel): CoreLevel = when (level) {
            LogLevel.Error -> Error
            LogLevel.Warn -> Warn
            LogLevel.Info -> Info
            LogLevel.Debug -> Debug
        }
    }
}

/** `setLogLevel`'s arguments. */
@Serializable
internal data class LevelArgs(val level: CoreLevel)

/** `diagnostics`' arguments: the events of [level] and the more severe; every event when null, which is not sent. */
@Serializable
internal data class DiagnosticsArgs(val level: CoreLevel? = null)

/** `clearCrashes`' arguments: the crash files the export took and its listing's `crashMark`; the core deletes only these. */
@Serializable
internal data class ClearCrashesArgs(val names: List<String>, val mark: Long)

/** `diagnostics`' reply: the build, the ring's [level] and its [events] (oldest first), and the [crashes] (oldest first). */
@Serializable
internal data class DiagnosticsDto(
    val core: CoreBuildDto,
    val level: CoreLevel,
    val events: List<DiagEventDto>,
    /** Events the ring let go since `init`. */
    val overwritten: Long,
    /** What the last `snapshot` answered; null before the first. */
    val snapshot: LastSnapshotDto? = null,
    val stats: StatsSummaryDto,
    val crashes: List<CrashFileDto>,
    /** No crash file listed has a higher number, and none written later will have it or a lower one. */
    val crashMark: Long,
) {
    fun toCoreDiagnostics(json: String) =
        CoreDiagnostics(level.toLogLevel(), crashes.map { CrashFile(it.name, it.bytes) }, crashMark, json)
}

/** The library and how it was built: [profile] `release` or `debug`, [target] as `aarch64-android`. */
@Serializable
internal data class CoreBuildDto(val version: String, val schema: Int, val profile: String, val target: String, val features: List<String>)

/** One event of the ring: [code] an error code, [counts] numbers only, [ms] how long a command took. */
@Serializable
internal data class DiagEventDto(
    val atEpochMs: Long,
    val level: CoreLevel,
    val name: String,
    val code: String? = null,
    val detail: String? = null,
    val counts: Map<String, Long> = emptyMap(),
    val ms: Long? = null,
)

@Serializable
internal data class LastSnapshotDto(
    val atEpochMs: Long,
    val revision: Long,
    val running: Boolean,
    val generation: Long,
    val error: String? = null,
    val warning: String? = null,
)

/** The running generation's traffic (zero while stopped) and the local day's bytes, as `snapshot` reports them. */
@Serializable
internal data class StatsSummaryDto(val traffic: TrafficDto, val today: TodayDto = TodayDto())

/** A file in the core's `crash/` directory. */
@Serializable
internal data class CrashFileDto(val name: String, val bytes: Long)

/** `diagnostics`' reply parsed, and its `ok` object as JSON text for the export. */
internal class DiagnosticsReply(val dto: DiagnosticsDto, val json: String)

/** Reads the `ok` value once as a tree, then decodes it with the caller's [Json] configuration. */
internal object DiagnosticsReplyReader : DeserializationStrategy<DiagnosticsReply> {
    override val descriptor: SerialDescriptor = JsonElement.serializer().descriptor

    override fun deserialize(decoder: Decoder): DiagnosticsReply {
        val input = decoder as? JsonDecoder ?: throw SerializationException("diagnostics needs a JSON decoder")
        val ok = input.decodeJsonElement()
        return DiagnosticsReply(input.json.decodeFromJsonElement(DiagnosticsDto.serializer(), ok), ok.toString())
    }
}
