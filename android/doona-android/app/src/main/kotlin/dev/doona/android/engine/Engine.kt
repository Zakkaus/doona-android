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
    val today: StateFlow<TodayTraffic>
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
     * The core's catalog, published whole, so a reader never sees part of one catalog beside part of another. Null until
     * it is read. [groups], [subscriptions] and [Settings.mode] repeat parts of it for the screens that read them alone.
     */
    val catalog: StateFlow<Catalog?>

    /**
     * A problem the running tunnel reports without stopping: [ErrorCode.LocalNetwork] once a connection through a node on
     * the local network failed in this run (ARCH D26). Null otherwise, and whenever the tunnel is not running.
     */
    val warning: StateFlow<ErrorCode?>

    /** Starts the tunnel. The caller has already obtained VPN consent: `VpnService.prepare` returned null. */
    fun connect()

    fun disconnect()

    /**
     * Pins [memberId] in [groupId] on [network], or on both networks when [network] is null. A null [memberId] clears
     * the affected pins. This and [testLatency] reach the core in call order, even if the caller stops waiting.
     */
    suspend fun select(groupId: String, memberId: String?, network: Network? = null): Result<Unit>

    /**
     * Starts probing the members of the group [groupId], or of every group when null, and returns once started.
     * [Group.testing] stays true until the last probe ends; results arrive through [groups].
     */
    suspend fun testLatency(groupId: String? = null): Result<Unit>

    /**
     * Fetches [url], decodes its nodes and adds them to the group template. Its fetches send [userAgent] as the
     * User-Agent, or the core's default when null; a value must be 1 to 256 bytes of printable ASCII and not blank, else
     * [ErrorCode.InvalidArgument] before anything is fetched. A null [name] takes the URL host; a name follows [renameSubscription].
     */
    suspend fun addSubscription(url: String, name: String?, userAgent: String?): Result<Subscription>

    suspend fun updateSubscription(id: String): Result<Subscription>

    /** Saves a trimmed name of 1 to 64 Unicode code points. Controls and line separators are invalid. Names may repeat. */
    suspend fun renameSubscription(id: String, name: String): Result<Subscription>

    /** Returns the original URI only for a share_links node. */
    suspend fun nodeShareLink(nodeId: String): Result<ShareLink>

    suspend fun removeSubscription(id: String): Result<Unit>

    /**
     * Sets the User-Agent the next fetches of subscription [id] send, or the default when [userAgent] is null, under the
     * same rules as [addSubscription]; nothing is fetched now. Returns the subscription as the catalog shows it.
     */
    suspend fun setSubscriptionUserAgent(id: String, userAgent: String?): Result<Subscription>

    /** Validates drafts without saving or reserving their IDs. Results are in input order. */
    suspend fun previewStaticNodes(rows: List<StaticNodeDraft>): Result<List<StaticNodeResult>>

    /** Commits accepted drafts together and returns a result for each input row, in order. */
    suspend fun addStaticNodes(rows: List<StaticNodeDraft>): Result<List<StaticNodeResult>>

    /** Reads the private editor record, not a catalog or telemetry entry. */
    suspend fun readStaticNode(id: String): Result<StaticNode>

    /** An edit based on a changed record fails with [ErrorCode.Busy]; a missing record fails with [ErrorCode.NotFound]. */
    suspend fun updateStaticNode(expected: StaticNode, node: StaticNodeDraft): Result<StaticNodeUpdateResult>

    /** Uses the same stale-record check as [updateStaticNode]; [confirmed] must be true to remove the node. */
    suspend fun removeStaticNode(expected: StaticNode, confirmed: Boolean): Result<Unit>

    suspend fun setMode(mode: Mode): Result<Unit>

    /**
     * Up to [limit] rows of the rule source, 1 to 500, from the row [offset]; none past the end. A page whose
     * [RulePage.rulesRevision] differs from the [catalog]'s rule source is stale: read it again. This and the other reads
     * below run in the caller's job: a caller that leaves first stops them.
     */
    suspend fun rules(offset: Int, limit: Int): Result<RulePage>

    /**
     * Makes the template [template] with [options] the rule source; new flows use it at once. Fails with
     * [ErrorCode.NotFound] for a template the core does not have.
     */
    suspend fun setRuleSource(template: String, options: RuleOptions): Result<Unit>

    /** Saves 1 to 4 IP literals, optionally with a port (default 53). New UDP queries and TCP DNS connections use them immediately. */
    suspend fun setDnsUpstreams(servers: List<String>): Result<Unit>

    /** What routes flows and DNS now, with the rules as text for the read-only 生效設定 page. */
    suspend fun effectiveConfig(): Result<EffectiveConfig>

    /** Today's and yesterday's bytes by the hour, by outbound and by rule, and the totals of the last 31 days. */
    suspend fun usage(): Result<Usage>

    /** The last [seconds] (1 to 3600) of rates, including data before disconnect. */
    suspend fun trafficSeries(seconds: Long): Result<TrafficSeries>

    /** All nodes in catalog order when [ids] is null; otherwise in request order, omitting unknown ids. */
    suspend fun nodes(ids: List<String>? = null): Result<List<NodeInfo>>

    /** Live TCP and UDP Score evidence, independent of the catalog revision; reading starts no trial. */
    suspend fun scoreState(): Result<ScoreState>

    /** Reads the active geo generation and the last update result, even while disconnected. */
    suspend fun configAssets(): Result<ConfigAssets>

    /** Starts a geo update through the running VPN. A downloaded generation takes effect at the next start. */
    suspend fun updateConfigAssets(): Result<ConfigAssets>

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

    /** The running tunnel's recent connections, newest first. Empty when the core has no running tunnel. */
    suspend fun recentFlows(): Result<List<RecentFlow>>

    /** Sets the level the core's ring keeps from now on. The core does not save it; works while stopped. */
    suspend fun setLogLevel(level: LogLevel): Result<Unit>

    /**
     * Deletes the crash files [names], sent after an export that included them succeeded, with the [mark] of the
     * [CoreDiagnostics] that listed them. Only these go, and only at or below [mark]: a crash written since the export
     * stays for the next one, even when the same call is sent again.
     */
    suspend fun clearCrashes(names: List<String>, mark: Long): Result<Unit>
}

/**
 * Codes shared with the core (`plans/v1/core-api.md`). Raw details never reach the UI: they may hold subscription URLs.
 * [LocalNetwork]: the failed connection or download went to an address on the local network, which Android 17 lets doona
 * reach only with the `ACCESS_LOCAL_NETWORK` permission (ARCH D26).
 */
enum class ErrorCode { InvalidArgument, NotFound, FetchFailed, TooLarge, ParseFailed, NoNodes, Busy, Internal, LocalNetwork, DnsFailed }

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

    /** Android revoked VPN authorization, for example when another VPN took over. */
    Revoked,

    /** The tunnel does not support Android's lockdown mode. */
    LockdownEnabled,

    /** Android did not provide a tunnel descriptor. */
    VpnNotEstablished,
}

/** Rates in bytes per second; totals in bytes since connect; histories hold the last 60 one-second rates, oldest first. */
data class Traffic(
    val upRate: Long = 0,
    val downRate: Long = 0,
    val upTotal: Long = 0,
    val downTotal: Long = 0,
    val upHistory: List<Long> = emptyList(),
    val downHistory: List<Long> = emptyList(),
    val activeFlows: Int = 0,
    val upPeak: Long = 0,
    val downPeak: Long = 0,
)

/**
 * Global sends every flow through the main group and Direct sends every flow straight out. Rule sends each new flow
 * where the first rule that matches it says, and a flow no rule matched through the main group.
 */
enum class Mode { Global, Direct, Rule }

enum class Network { Tcp, Udp }

enum class GroupKind(val manual: Boolean) { Selector(true), UrlTest(false), Score(false), Fallback(false) }

/**
 * A node or a nested group. [id] stays the same across subscription refreshes; [name] is only for display and may
 * repeat. [latencyMs] is null before the first probe and after a failed one; [failed] tells them apart, and [error] says
 * why the probe failed when known: [ErrorCode.DnsFailed] for DNS or [ErrorCode.LocalNetwork] for a local dial.
 */
data class Member(
    val id: String,
    val name: String,
    val latencyMs: Int? = null,
    val failed: Boolean = false,
    val isGroup: Boolean = false,
    /** The source subscription; null for static nodes and groups. */
    val subscriptionId: String? = null,
    val error: ErrorCode? = null,
)

/** Like the core catalog, [selectedId] and [pinned] report the TCP selection even when the networks differ. */
data class Group(
    /** `g-` plus the group's name; stable. */
    val id: String,
    val name: String,
    val kind: GroupKind,
    val members: List<Member>,
    val tcp: Selection,
    val udp: Selection = tcp,
    val testing: Boolean = false,
) {
    constructor(
        id: String,
        name: String,
        kind: GroupKind,
        members: List<Member>,
        selectedId: String?,
        pinned: Boolean = false,
        testing: Boolean = false,
    ) : this(id, name, kind, members, Selection(selectedId, pinned), testing = testing)

    data class Selection(val selectedId: String?, val pinned: Boolean)

    val selectedId: String? get() = tcp.selectedId
    val pinned: Boolean get() = tcp.pinned

    /** Whether the selected members differ, regardless of which network is pinned. */
    val networksDiffer: Boolean get() = tcp.selectedId != udp.selectedId

    fun withSharedSelection(selectedId: String?, pinned: Boolean): Group {
        val selection = Selection(selectedId, pinned)
        return copy(tcp = selection, udp = selection)
    }
}

/**
 * One revision of the core's catalog. [mainGroupId] is the group [Mode.Global] sends every flow through, and [Mode.Rule]
 * each flow no rule matched; [ruleSource] is where Rule's rules come from, present in every mode.
 */
data class Catalog(
    val mode: Mode,
    val groups: List<Group>,
    val subscriptions: List<Subscription>,
    val mainGroupId: String,
    val ruleSource: RuleSource,
)

/** Access [uri] only to share it. Formatting the value or its enclosing Result must not expose credentials. */
@JvmInline
value class ShareLink(val uri: String) {
    override fun toString(): String = "ShareLink([redacted])"
}

enum class SubscriptionFormat { ShareLinks, Base64, Sip008, Clash, SingBox }

/**
 * Quota fields come from the provider's `subscription-userinfo` header and stay null when it sends none. [lastFetch] is
 * null while the core has never fetched it, as for a subscription a hand-written `.dae` declares. [userAgent] is the
 * User-Agent its fetches send, null for the core's default; keep it out of logs and exports, as the URL.
 */
data class Subscription(
    val id: String,
    val name: String,
    val url: String,
    val nodes: Int,
    val lastFetch: SubscriptionFetch?,
    val error: String? = null,
    val usedBytes: Long? = null,
    val totalBytes: Long? = null,
    val expiresAtEpochMs: Long? = null,
    val userAgent: String? = null,
)

/** A subscription's last successful fetch: when it ended, and how the body was written. */
data class SubscriptionFetch(val atEpochMs: Long, val format: SubscriptionFormat)

/** Which apps use the tunnel. doona itself never does: its own sockets go out directly. */
data class PerApp(val policy: AppPolicy = AppPolicy.All, val packages: Set<String> = emptySet())

enum class AppPolicy { All, OnlyListed, ExceptListed }

data class Settings(
    val mode: Mode = Mode.Global,
    val perApp: PerApp = PerApp(),
    /**
     * Whether Android blocks traffic outside this VPN. False when the VPN service is not running;
     * while connecting or reconnecting it keeps the last reading taken while running.
     */
    val lockdownEnabled: Boolean = false,
)
