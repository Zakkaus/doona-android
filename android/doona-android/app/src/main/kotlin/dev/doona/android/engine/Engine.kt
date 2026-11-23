package dev.doona.android.engine

import kotlinx.coroutines.flow.StateFlow

/**
 * The UI's only view of the VPN engine. The service-backed implementation lives in `vpn/`; `engine/fake/FakeEngine`
 * serves previews and screenshot tests. Every flow holds a complete snapshot: a change replaces the whole value.
 * Actions fail with [EngineException]; the UI turns its code into localized text.
 */
interface Engine {
    /** False until the saved subscriptions and groups are loaded, so an empty list is not mistaken for a first run. */
    val ready: StateFlow<Boolean>
    val status: StateFlow<Status>
    val traffic: StateFlow<Traffic>
    val groups: StateFlow<List<Group>>
    val subscriptions: StateFlow<List<Subscription>>
    val settings: StateFlow<Settings>

    /**
     * False until the stored per-app policy has been read, or found unreadable: until then `settings.perApp` is the
     * default, not what the user saved, and an edit that starts from it would overwrite the saved policy. Apart from
     * [ready], which waits for the core's catalog.
     */
    val perAppLoaded: StateFlow<Boolean>

    /**
     * A problem the running tunnel reports without stopping: [ErrorCode.LocalNetwork] once a connection through a node on
     * the local network failed in this run (ARCH D26). Null otherwise, and whenever the tunnel is not running.
     */
    val warning: StateFlow<ErrorCode?>

    /** Starts the tunnel. The caller has already obtained VPN consent: `VpnService.prepare` returned null. */
    fun connect()

    fun disconnect()

    /**
     * Pins the member [memberId] in the group [groupId]; null hands an automatic group back to its own choice. This and
     * [testLatency] reach the core in the order they are called, from any caller, and a caller that stops waiting does not
     * cancel its call.
     */
    suspend fun select(groupId: String, memberId: String?): Result<Unit>

    /**
     * Starts probing the members of the group [groupId], or of every group when null, and returns once started.
     * [Group.testing] stays true until the last probe ends; results arrive through [groups].
     */
    suspend fun testLatency(groupId: String? = null): Result<Unit>

    /** Fetches [url], decodes its nodes and adds them to the group template. */
    suspend fun addSubscription(url: String, name: String? = null): Result<Subscription>

    suspend fun updateSubscription(id: String): Result<Subscription>

    suspend fun removeSubscription(id: String): Result<Unit>

    suspend fun setMode(mode: Mode): Result<Unit>

    /**
     * Stores [perApp] and applies it: a running tunnel is rebuilt, which drops open connections, and the call returns
     * once the rebuilt tunnel is up or failed. The policy is committed whatever the caller does meanwhile. Fails with
     * an [EngineException] when it could not be stored, and with a [RebuildFailedException] when it was stored but the
     * tunnel could not be rebuilt with it.
     */
    suspend fun setPerApp(perApp: PerApp): Result<Unit>

    /**
     * Reads the core's event ring, with the events of [level] and the more severe, or all of them when null, together
     * with the build, the last snapshot's codes, the stats and the crash files. Works while the tunnel is stopped.
     */
    suspend fun diagnostics(level: LogLevel? = null): Result<CoreDiagnostics>

    /** Sets the level the core's ring keeps from now on. The core does not save it; works while stopped. */
    suspend fun setLogLevel(level: LogLevel): Result<Unit>

    /**
     * Deletes the crash files [names], sent after an export that included them succeeded. Only these go: a crash written
     * since the export stays for the next one.
     */
    suspend fun clearCrashes(names: List<String>): Result<Unit>
}

/**
 * Codes shared with the core (`plans/v1/core-api.md`). Raw details never reach the UI: they may hold subscription URLs.
 * [LocalNetwork]: the failed connection or download went to an address on the local network, which Android 17 lets doona
 * reach only with the `ACCESS_LOCAL_NETWORK` permission (ARCH D26).
 */
enum class ErrorCode { InvalidArgument, NotFound, FetchFailed, ParseFailed, NoNodes, Busy, Internal, LocalNetwork }

class EngineException(val code: ErrorCode) : Exception(code.name)

/** A policy that was stored, but the tunnel rebuilt with it failed to start for [reason]; the tunnel is [Status.Failed]. */
class RebuildFailedException(val reason: FailureReason) : Exception(reason.name)

sealed interface Status {
    data object Stopped : Status

    data object Starting : Status

    data class Running(val sinceEpochMs: Long) : Status

    data object Stopping : Status

    /** [code] is the core's own answer when it refused the start, such as [ErrorCode.NoNodes]; null for any other failure. */
    data class Failed(val reason: FailureReason, val code: ErrorCode? = null) : Status
}

enum class FailureReason {
    /** `OnlyListed` resolved to no installed app; connecting would capture doona itself. */
    NoAllowedApps,

    /** The tunnel or the core did not start. */
    StartFailed,

    /** The core's data path stopped on its own after starting. */
    CoreStopped,

    /** The bundled core speaks another contract schema. */
    IncompatibleCore,
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

/**
 * A node or a nested group. [id] stays the same across subscription refreshes; [name] is only for display and may
 * repeat. [latencyMs] is null before the first probe and after a failed one; [failed] tells them apart, and [error] says
 * why the probe failed when the core knows: [ErrorCode.LocalNetwork] for a node on the local network.
 */
data class Member(
    val id: String,
    val name: String,
    val latencyMs: Int? = null,
    val failed: Boolean = false,
    val isGroup: Boolean = false,
    /** The subscription a node came from; null for groups. */
    val subscriptionId: String? = null,
    val error: ErrorCode? = null,
)

/** [selectedId] is the member carrying traffic now; [pinned] says the user chose it rather than the group's own test. */
data class Group(
    /** `g-` plus the group's name; stable. */
    val id: String,
    val name: String,
    val kind: GroupKind,
    val members: List<Member>,
    val selectedId: String?,
    val pinned: Boolean = false,
    val testing: Boolean = false,
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
