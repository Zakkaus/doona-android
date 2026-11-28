package dev.doona.android.honk

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

// honk's catalog resources as its native API sends them (native_api_openapi.yaml, components/schemas): providers,
// nodes, groups and the operations that create, refresh or delete them. Names follow honk's schemas; fields the app
// reads nothing from are left out. Timestamps stay RFC 3339 text and 64-bit counters decimal text, as honk sends them.

@Serializable
internal class ProviderList(val providers: List<Provider>, @SerialName("next_cursor") val nextCursor: String?)

@Serializable
internal class Provider(
    val id: String,
    val name: String,
    val kind: ProviderKind,
    @SerialName("url_redacted") val urlRedacted: String?,
    @SerialName("node_count") val nodeCount: Int,
    @SerialName("updated_at") val updatedAt: String?,
    @SerialName("expires_at") val expiresAt: String?,
    val traffic: ProviderTraffic?,
    val status: ProviderStatus,
    @SerialName("last_error") val lastError: SafeError?,
)

@Serializable
internal enum class ProviderKind {
    @SerialName("subscription")
    Subscription,

    @SerialName("file")
    File,

    @SerialName("inline")
    Inline,
}

@Serializable
internal enum class ProviderStatus {
    @SerialName("ok")
    Ok,

    @SerialName("stale")
    Stale,

    @SerialName("error")
    Error,
}

/** Each figure is null when the subscription's `subscription-userinfo` header left it out. */
@Serializable
internal class ProviderTraffic(
    @SerialName("upload_bytes") val uploadBytes: String?,
    @SerialName("download_bytes") val downloadBytes: String?,
    @SerialName("total_bytes") val totalBytes: String?,
)

/** [details] is free-form; a failed fetch names its `stage` there. */
@Serializable
internal class SafeError(val code: String, val message: String, val details: JsonObject? = null)

@Serializable
internal class NodeList(val nodes: List<Node>, @SerialName("next_cursor") val nextCursor: String?)

/** A static node (one added by its link) has a null [providerId]. */
@Serializable
internal class Node(
    val id: String,
    val name: String,
    val protocol: String?,
    @SerialName("stream_transport") val streamTransport: String? = null,
    @SerialName("subscription_tag") val subscriptionTag: String?,
    @SerialName("provider_id") val providerId: String? = null,
    @SerialName("group_ids") val groupIds: List<String>,
    val health: List<HealthObservation>,
)

@Serializable
internal class HealthObservation(
    val transport: Transport,
    val state: HealthState,
    @SerialName("latency_ms") val latencyMs: Double?,
    /** The average of the last ten probes; null until there are ten. */
    @SerialName("avg10_ms") val avg10Ms: Double? = null,
    @SerialName("observed_at") val observedAt: String,
    val error: String?,
)

@Serializable
internal enum class Transport {
    @SerialName("tcp")
    Tcp,

    @SerialName("udp")
    Udp,
}

@Serializable
internal enum class HealthState {
    @SerialName("healthy")
    Healthy,

    @SerialName("unavailable")
    Unavailable,

    @SerialName("unknown")
    Unknown,
}

/** One entry of `GET /groups`. */
@Serializable
internal class GroupSummary(val id: String, val name: String)

/** Group ids last as long as the honk process: never store them. */
@Serializable
internal class Group(
    val id: String,
    val name: String,
    val icon: String?,
    val policy: GroupPolicy,
    val members: List<GroupMember>,
    val runtime: GroupRuntime,
    val capabilities: GroupCapabilities,
)

/** [native] is the policy as the configuration writes it, such as `min_moving_avg`. */
@Serializable
internal class GroupPolicy(val kind: PolicyKind, val native: String)

@Serializable
internal enum class PolicyKind {
    @SerialName("selector")
    Selector,

    @SerialName("urltest")
    UrlTest,

    @SerialName("loadbalance")
    LoadBalance,

    @SerialName("fallback")
    Fallback,

    @SerialName("random")
    Random,

    @SerialName("score")
    Score,

    @SerialName("fixed")
    Fixed,
}

@Serializable
internal class GroupMember(val id: String, val name: String, val kind: MemberKind)

@Serializable
internal enum class MemberKind {
    @SerialName("node")
    Node,

    @SerialName("group")
    Group,
}

@Serializable
internal class GroupRuntime(val selection: GroupSelections, val health: List<GroupHealthObservation>)

/** The member each network uses now; null before the group has chosen one. */
@Serializable
internal class GroupSelections(val tcp: GroupSelection?, val udp: GroupSelection?)

/** [source] is `runtime` for the group's own choice and `override` for a pin. */
@Serializable
internal class GroupSelection(
    @SerialName("member_id") val memberId: String,
    @SerialName("resolved_leaf_node_id") val resolvedLeafNodeId: String? = null,
    val source: String,
)

@Serializable
internal class GroupHealthObservation(
    @SerialName("member_id") val memberId: String,
    val transport: Transport,
    val state: HealthState,
    @SerialName("latency_ms") val latencyMs: Double?,
)

@Serializable
internal class GroupCapabilities(
    @SerialName("can_select") val canSelect: Boolean,
    @SerialName("can_override") val canOverride: Boolean,
)

/** The network a selection applies to: [Both] chooses for TCP and UDP at once. */
@Serializable
internal enum class SelectionNetwork {
    @SerialName("tcp")
    Tcp,

    @SerialName("udp")
    Udp,

    @SerialName("both")
    Both,
}

/** honk's answer to a change it runs in the background: poll [href] for the [Operation]. */
@Serializable
internal class OperationAccepted(@SerialName("operation_id") val operationId: String, val href: String)

/** An operation as `GET /operations/{id}` reports it; [result] is the created or refreshed resource. */
@Serializable
internal class Operation<T>(val status: OperationStatus, val result: T? = null, val error: SafeError? = null)

@Serializable
internal enum class OperationStatus {
    @SerialName("queued")
    Queued,

    @SerialName("running")
    Running,

    @SerialName("succeeded")
    Succeeded,

    @SerialName("failed")
    Failed,
}

@Serializable
internal class DeleteCount(val deleted: Int)
