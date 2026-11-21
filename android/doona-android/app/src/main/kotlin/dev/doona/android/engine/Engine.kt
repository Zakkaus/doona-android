package dev.doona.android.engine

import kotlinx.coroutines.flow.StateFlow

/**
 * The UI's only view of the VPN engine. The service-backed implementation lives in `vpn/`; `ui/fake/FakeEngine`
 * serves previews and screenshot tests. Every flow holds a complete snapshot: a change replaces the whole value.
 */
interface Engine {
    val status: StateFlow<Status>
    val traffic: StateFlow<Traffic>
    val groups: StateFlow<List<Group>>
    val subscriptions: StateFlow<List<Subscription>>
    val settings: StateFlow<Settings>

    /** Starts the tunnel. The caller has already obtained VPN consent: `VpnService.prepare` returned null. */
    fun connect()

    fun disconnect()

    /** Pins [member] in [group]; null hands an automatic group back to its own choice. */
    suspend fun select(group: String, member: String?)

    /** Probes the members of [group], or of every group when null. Results arrive through [groups]. */
    suspend fun testLatency(group: String? = null)

    /** Fetches [url], decodes its nodes and adds them to the group template. */
    suspend fun addSubscription(url: String, name: String? = null): Result<Subscription>

    suspend fun updateSubscription(id: String): Result<Subscription>

    suspend fun removeSubscription(id: String)

    suspend fun setMode(mode: Mode)

    /** A running tunnel is rebuilt to apply it, which drops open connections. */
    suspend fun setPerApp(perApp: PerApp)
}

sealed interface Status {
    data object Stopped : Status

    data object Starting : Status

    data class Running(val sinceEpochMs: Long) : Status

    data object Stopping : Status

    /** [reason] is shown to the user as is. */
    data class Failed(val reason: String) : Status
}

/** Rates in bytes per second; totals in bytes since connect; histories hold the last 60 one-second rates, oldest first. */
data class Traffic(
    val upRate: Long = 0,
    val downRate: Long = 0,
    val upTotal: Long = 0,
    val downTotal: Long = 0,
    val upHistory: List<Long> = emptyList(),
    val downHistory: List<Long> = emptyList(),
)

/** Global sends everything through the template's first group. Rule arrives with the simple routing templates. */
enum class Mode { Global, Direct }

enum class GroupKind(val manual: Boolean) { Selector(true), UrlTest(false), Fallback(false) }

/** A node or a nested group. [latencyMs] is null before the first probe and after a failed one; [failed] tells them apart. */
data class Member(
    val name: String,
    val latencyMs: Int? = null,
    val failed: Boolean = false,
    val isGroup: Boolean = false,
    /** The subscription a node came from; null for groups. */
    val subscriptionId: String? = null,
)

/** [selected] carries traffic now; [pinned] says the user chose it rather than the group's own test. */
data class Group(
    val name: String,
    val kind: GroupKind,
    val members: List<Member>,
    val selected: String?,
    val pinned: Boolean = false,
)

/** Quota fields come from the provider's `subscription-userinfo` header and stay null when it sends none. */
data class Subscription(
    val id: String,
    val name: String,
    val url: String,
    val nodes: Int,
    val updatedAtEpochMs: Long?,
    val error: String? = null,
    val usedBytes: Long? = null,
    val totalBytes: Long? = null,
    val expiresAtEpochMs: Long? = null,
)

/** Which apps use the tunnel. doona itself never does: its own sockets go out directly. */
data class PerApp(val policy: AppPolicy = AppPolicy.All, val packages: Set<String> = emptySet())

enum class AppPolicy { All, OnlyListed, ExceptListed }

data class Settings(val mode: Mode = Mode.Global, val perApp: PerApp = PerApp())
