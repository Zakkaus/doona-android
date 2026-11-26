package dev.doona.android.vpn

import dev.doona.android.engine.EngineException
import dev.doona.android.engine.ErrorCode
import dev.doona.android.engine.Group
import dev.doona.android.engine.GroupKind
import dev.doona.android.engine.Member
import dev.doona.android.engine.Network
import dev.doona.android.engine.Subscription
import kotlinx.coroutines.delay
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.URLEncoder
import java.time.OffsetDateTime

/**
 * Subscriptions (honk's providers), nodes, groups and selections through honk's native API. honk refreshes each
 * subscription on its own interval, keeps selections and probes health; this class only reads and writes. Group ids
 * last as long as the honk process: never store them. Every call blocks in [NativeApi]: run them on `Dispatchers.IO`.
 */
internal class NativeCatalog(private val api: NativeApi) {
    fun subscriptions(): List<Subscription> =
        pages("/api/v1/providers", ProviderPageDto.serializer()).filter { it.kind == "subscription" }.map(ProviderDto::toSubscription)

    /** A [userAgent] of null leaves honk's default. A name already in use fails with [ErrorCode.InvalidArgument]. */
    fun addSubscription(url: String, name: String, userAgent: String?): Subscription {
        val body = buildJsonObject {
            put("name", name)
            put("kind", "subscription")
            put("url", url)
            userAgent?.let { put("user_agent", it) }
        }
        return decode(request("POST", "/api/v1/providers", body, ErrorCode.InvalidArgument), ProviderDto.serializer()).toSubscription()
    }

    fun removeSubscription(id: String) {
        val deleted = decode(request("DELETE", "/api/v1/providers/$id"), DeleteCountDto.serializer()).deleted
        if (deleted == 0) throw EngineException(ErrorCode.NotFound)
    }

    /** Fetches [id] now and waits for honk to publish its nodes; another refresh of it in flight fails with [ErrorCode.Busy]. */
    suspend fun refreshSubscription(id: String): Subscription {
        val operation = decode(request("POST", "/api/v1/providers/$id/refresh"), AcceptedDto.serializer()).href
        while (true) {
            delay(POLL_MS)
            val state = decode(request("GET", operation), RefreshOperationDto.serializer())
            when (state.status) {
                "succeeded" -> return checkNotNull(state.result) { "a refresh without a result" }.toSubscription()
                "failed" -> throw EngineException(checkNotNull(state.error) { "a failed refresh without an error" }.toErrorCode())
            }
        }
    }

    /** Every node; static nodes have a null [Member.subscriptionId]. */
    fun nodes(): List<Member> = pages("/api/v1/nodes", NodePageDto.serializer()).map(NodeDto::toMember)

    /** Adds a static node from a share link; a link honk cannot use or a name in use fails with [ErrorCode.InvalidArgument]. */
    fun addNode(name: String, link: String): Member {
        val body = buildJsonObject {
            put("name", name)
            put("link", link)
        }
        return decode(request("POST", "/api/v1/nodes", body, ErrorCode.InvalidArgument), NodeDto.serializer()).toMember()
    }

    fun groups(): List<Group> {
        val subscriptions = nodes().associate { it.id to it.subscriptionId }
        return decode(request("GET", "/api/v1/groups"), ListSerializer(GroupSummaryDto.serializer())).map { summary ->
            decode(request("GET", "/api/v1/groups/${summary.id}"), GroupDetailDto.serializer()).toGroup(subscriptions)
        }
    }

    /**
     * Selects [memberId] in [groupId] on [network], or on both networks when null: a Selector's choice, or a pin on an
     * automatic group. A null [memberId] clears the pins, and fails with [ErrorCode.InvalidArgument] on a Selector.
     */
    fun select(groupId: String, memberId: String?, network: Network?) {
        val wire = network?.name?.lowercase() ?: "both"
        if (memberId == null) {
            request("DELETE", "/api/v1/groups/$groupId/selection?network=$wire", conflict = ErrorCode.InvalidArgument)
        } else {
            request(
                "PUT",
                "/api/v1/groups/$groupId/selection",
                buildJsonObject {
                    put("member_id", memberId)
                    put("network", wire)
                },
            )
        }
    }

    private fun <T> pages(path: String, serializer: DeserializationStrategy<PageDto<T>>): List<T> {
        val items = mutableListOf<T>()
        var cursor: String? = null
        do {
            val target = cursor?.let { "$path?cursor=${URLEncoder.encode(it, "UTF-8")}" } ?: path
            val page = decode(request("GET", target), serializer)
            items += page.items
            cursor = page.nextCursor
        } while (cursor != null)
        return items
    }

    /** The body of a 2xx reply; any other status is an [EngineException], with [conflict] for a 409. */
    private fun request(method: String, path: String, body: JsonObject? = null, conflict: ErrorCode = ErrorCode.Busy): String {
        val response = api.call(method, path, body?.toString())
        if (response.status in 200..299) return response.body
        throw EngineException(httpErrorCode(response.status, conflict))
    }

    private fun <T> decode(text: String, serializer: DeserializationStrategy<T>): T = json.decodeFromString(serializer, text)

    private companion object {
        /** honk's operations ask to be polled no more than once a second (`Retry-After: 1`). */
        const val POLL_MS = 1_000L

        val json = Json { ignoreUnknownKeys = true }
    }
}

/**
 * The status of honk's error envelope decides the code; its `error.code` adds nothing the app acts on, and its message
 * has no place in [EngineException]. A 409 is a conflict with honk's state, which [conflict] names for the call; 410
 * (an expired page cursor) and 503 (honk cannot take the request now) can be retried.
 */
private fun httpErrorCode(status: Int, conflict: ErrorCode) = when (status) {
    400, 422 -> ErrorCode.InvalidArgument
    404 -> ErrorCode.NotFound
    409 -> conflict
    410, 503 -> ErrorCode.Busy
    413 -> ErrorCode.TooLarge
    else -> ErrorCode.Internal
}

private fun epochMs(timestamp: String) = OffsetDateTime.parse(timestamp).toInstant().toEpochMilli()

internal interface PageDto<T> {
    val items: List<T>
    val nextCursor: String?
}

@Serializable
internal class ProviderPageDto(
    @SerialName("providers") override val items: List<ProviderDto>,
    @SerialName("next_cursor") override val nextCursor: String?,
) : PageDto<ProviderDto>

@Serializable
internal class NodePageDto(
    @SerialName("nodes") override val items: List<NodeDto>,
    @SerialName("next_cursor") override val nextCursor: String?,
) : PageDto<NodeDto>

/**
 * honk reports `updated_at` without the body format [Subscription.lastFetch] needs, and no User-Agent, so both stay null.
 * Traffic and expiry come from the provider's `subscription-userinfo` header and are null without it.
 */
@Serializable
internal class ProviderDto(
    val id: String,
    val name: String,
    val kind: String,
    @SerialName("url_redacted") val url: String?,
    @SerialName("node_count") val nodeCount: Int,
    @SerialName("expires_at") val expiresAt: String?,
    val traffic: ProviderTrafficDto?,
    @SerialName("last_error") val lastError: SafeErrorDto?,
) {
    fun toSubscription() = Subscription(
        id = id,
        name = name,
        url = checkNotNull(url) { "a subscription without a URL" },
        nodes = nodeCount,
        lastFetch = null,
        error = lastError?.toErrorCode(),
        usedBytes = traffic?.used(),
        totalBytes = traffic?.total?.toLong(),
        expiresAtEpochMs = expiresAt?.let(::epochMs),
    )
}

/** Decimal strings, each null when the header leaves it out. */
@Serializable
internal class ProviderTrafficDto(
    @SerialName("upload_bytes") val upload: String?,
    @SerialName("download_bytes") val download: String?,
    @SerialName("total_bytes") val total: String?,
) {
    fun used(): Long? = if (upload == null && download == null) null else (upload?.toLong() ?: 0) + (download?.toLong() ?: 0)
}

@Serializable
internal class SafeErrorDto(val code: String, val details: SafeErrorDetailsDto? = null) {
    /** A fetch whose body did not parse is [ErrorCode.ParseFailed]; one honk could not route or download, [ErrorCode.FetchFailed]. */
    fun toErrorCode() = when {
        code == "fetch_failed" && details?.stage == "parse_failed" -> ErrorCode.ParseFailed
        code == "fetch_failed" || code == "route_unavailable" -> ErrorCode.FetchFailed
        else -> ErrorCode.Internal
    }
}

@Serializable
internal class SafeErrorDetailsDto(val stage: String? = null)

@Serializable
internal class AcceptedDto(val href: String)

@Serializable
internal class RefreshOperationDto(val status: String, val result: ProviderDto? = null, val error: SafeErrorDto? = null)

@Serializable
internal class DeleteCountDto(val deleted: Int)

/** `provider_id` is `inline` for a static node and null for honk's builtins (`direct`, `block`). */
@Serializable
internal class NodeDto(val id: String, val name: String, @SerialName("provider_id") val providerId: String? = null) {
    fun toMember() = Member(id = id, name = name, subscriptionId = providerId?.takeUnless { it == "inline" })
}

@Serializable
internal class GroupSummaryDto(val id: String)

@Serializable
internal class GroupDetailDto(
    val id: String,
    val name: String,
    val policy: GroupPolicyDto,
    val members: List<GroupMemberDto>,
    val runtime: GroupRuntimeDto,
) {
    /** [subscriptions] maps node ids to their subscription, which group members do not carry. */
    fun toGroup(subscriptions: Map<String, String?>) = Group(
        id = id,
        name = name,
        kind = checkNotNull(GROUP_KINDS[policy.kind]) { "group policy ${policy.kind}" },
        members = members.map { Member(id = it.id, name = it.name, isGroup = it.kind == "group", subscriptionId = subscriptions[it.id]) },
        tcp = runtime.selection.tcp.toSelection(),
        udp = runtime.selection.udp.toSelection(),
    )
}

private val GROUP_KINDS = mapOf(
    "selector" to GroupKind.Selector,
    "urltest" to GroupKind.UrlTest,
    "loadbalance" to GroupKind.LoadBalance,
    "fallback" to GroupKind.Fallback,
    "score" to GroupKind.Score,
)

@Serializable
internal class GroupPolicyDto(val kind: String)

@Serializable
internal class GroupMemberDto(val id: String, val name: String, val kind: String)

@Serializable
internal class GroupRuntimeDto(val selection: GroupSelectionsDto)

@Serializable
internal class GroupSelectionsDto(val tcp: GroupSelectionDto?, val udp: GroupSelectionDto?)

/** `source` is `override` while a pinned member stands in for an automatic group's policy. */
@Serializable
internal class GroupSelectionDto(@SerialName("member_id") val memberId: String, val source: String)

private fun GroupSelectionDto?.toSelection() = Group.Selection(this?.memberId, this?.source == "override")
