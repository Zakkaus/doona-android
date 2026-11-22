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
internal const val CORE_SCHEMA = 1

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

        fun of(bootstrapDns: List<String>) = StartConfigDto(MTU, IPV4, DNS, bootstrapDns)
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
    val traffic: TrafficDto,
    val catalog: CatalogDto? = null,
) {
    val alive: Boolean get() = running && error == null
}

@Serializable
internal data class TrafficDto(val upTotal: Long, val downTotal: Long, val activeFlows: Int)

@Serializable
internal data class CatalogDto(val mode: Mode, val groups: List<GroupDto>, val subscriptions: List<SubscriptionDto>)

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
    val isGroup: Boolean,
    val subscriptionId: String? = null,
) {
    fun toMember() = Member(id, name, latencyMs, failed, isGroup, subscriptionId)
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
internal data class ModeArgs(val mode: Mode)

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
