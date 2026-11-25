package dev.doona.android.engine.fake

import dev.doona.android.engine.Catalog
import dev.doona.android.engine.ConfigAssets
import dev.doona.android.engine.CoreDiagnostics
import dev.doona.android.engine.CrashFile
import dev.doona.android.engine.EffectiveConfig
import dev.doona.android.engine.Engine
import dev.doona.android.engine.EngineException
import dev.doona.android.engine.ErrorCode
import dev.doona.android.engine.Group
import dev.doona.android.engine.GroupKind
import dev.doona.android.engine.LatencyTest
import dev.doona.android.engine.LogLevel
import dev.doona.android.engine.Member
import dev.doona.android.engine.Mode
import dev.doona.android.engine.Network
import dev.doona.android.engine.NodeInfo
import dev.doona.android.engine.PerApp
import dev.doona.android.engine.RecentFlow
import dev.doona.android.engine.RuleOptions
import dev.doona.android.engine.RulePage
import dev.doona.android.engine.ScoreState
import dev.doona.android.engine.Settings
import dev.doona.android.engine.ShareLink
import dev.doona.android.engine.StaticNode
import dev.doona.android.engine.StaticNodeDraft
import dev.doona.android.engine.StaticNodeResult
import dev.doona.android.engine.StaticNodeUpdateResult
import dev.doona.android.engine.Status
import dev.doona.android.engine.Subscription
import dev.doona.android.engine.SubscriptionFetch
import dev.doona.android.engine.SubscriptionFormat
import dev.doona.android.engine.TodayTraffic
import dev.doona.android.engine.Traffic
import dev.doona.android.engine.TrafficSeries
import dev.doona.android.engine.Usage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlin.math.sin

/**
 * Deterministic in-memory [Engine] for previews and screenshot tests; it never touches the network or a tunnel.
 * The default dataset follows the core's rule template. The goldens pass [SAMPLE_GROUPS], which they were drawn with.
 */
class FakeEngine internal constructor(
    groups: List<Group>,
    subscriptions: List<Subscription>,
    status: Status,
    traffic: Traffic,
    mode: Mode,
    private val data: FakeData,
) : Engine {
    constructor(
        groups: List<Group> = TEMPLATE_GROUPS,
        subscriptions: List<Subscription> = SAMPLE_SUBSCRIPTIONS,
        status: Status = Status.Stopped,
        traffic: Traffic = Traffic(),
        mode: Mode = Mode.Global,
    ) : this(groups, subscriptions, status, traffic, mode, FakeData(nodes = nodeInfo(groups), scoreState = sampleScoreState(groups)))

    override val ready = MutableStateFlow(true)
    override val status = MutableStateFlow(status)
    override val traffic = MutableStateFlow(traffic)
    override val today: StateFlow<TodayTraffic> = MutableStateFlow(data.today)
    override val groups = MutableStateFlow(groups)
    override val subscriptions = MutableStateFlow(subscriptions)
    override val settings: StateFlow<Settings> get() = settingsFlow
    private val settingsFlow = MutableStateFlow(Settings(mode = mode))
    private val ruleSource = MutableStateFlow(data.source)
    private var assets = ConfigAssets("builtin", null, "idle", null, false)
    private var dnsUpstreams = FakeRuleMode.DNS_UPSTREAMS

    /** The number in the next subscription id: as in the core, an id is never given twice, also after a removal. */
    private var nextSubscription = (subscriptions.maxOfOrNull(::subscriptionNumber) ?: 0) + 1

    /**
     * Follows the flows above as each changes, also when a test sets one directly: Unconfined collects in the writer's
     * thread before the write returns.
     */
    override val catalog: StateFlow<Catalog?> =
        combine(this.groups, this.subscriptions, settingsFlow, ruleSource) { all, subscribed, settings, source ->
            Catalog(settings.mode, all, subscribed, FakeRuleMode.MAIN_GROUP_ID, source)
        }.stateIn(CoroutineScope(Dispatchers.Unconfined), SharingStarted.Eagerly, null)

    /** True: the fake's policy is there from the start; a test sets false to stand for a stored policy still being read. */
    override val perAppLoaded = MutableStateFlow(true)

    /** None: the fake's tunnel never dials. A test sets one to stand for the core's report. */
    override val warning = MutableStateFlow<ErrorCode?>(null)

    override fun connect() {
        if (status.value is Status.Running) return
        if (assets.updateWaitingForNextStart) {
            assets = ConfigAssets("download", GEO_RELEASE, "activated", null, false)
        }
        status.value = Status.Running(sinceEpochMs = 0)
    }

    override fun disconnect() {
        status.value = Status.Stopped
    }

    override suspend fun select(groupId: String, memberId: String?, network: Network?): Result<Unit> {
        groups.update { all ->
            all.map { g ->
                if (g.id != groupId) {
                    g
                } else {
                    val selection = Group.Selection(memberId ?: g.members.firstOrNull()?.id, pinned = memberId != null)
                    val tcp = if (network == Network.Udp) g.tcp else selection
                    val udp = if (network == Network.Tcp) g.udp else selection
                    g.copy(tcp = tcp, udp = udp)
                }
            }
        }
        return Result.success(Unit)
    }

    override suspend fun testLatency(groupId: String?): Result<Unit> = Result.success(Unit)

    override suspend fun addSubscription(url: String, name: String?, userAgent: String?): Result<Subscription> {
        val agent = userAgentOf(userAgent).getOrElse { return Result.failure(it) }
        val label = (if (name == null) runCatching { java.net.URI(url).host?.take(NAME_CAP) }.getOrNull() else displayName(name))
            ?: return Result.failure(EngineException(ErrorCode.InvalidArgument))
        val added = Subscription(id = takeSubscriptionId(), name = label, url = url, nodes = 0, lastFetch = FETCHED, userAgent = agent)
        subscriptions.update { it + added }
        return Result.success(added)
    }

    /** Above every id given before and every id in [subscriptions], which a test may set directly. */
    @Synchronized
    private fun takeSubscriptionId(): String {
        val n = maxOf(nextSubscription, (subscriptions.value.maxOfOrNull(::subscriptionNumber) ?: 0) + 1)
        nextSubscription = n + 1
        return "s$n"
    }

    override suspend fun updateSubscription(id: String): Result<Subscription> =
        Result.success(subscriptions.value.first { it.id == id })

    override suspend fun renameSubscription(id: String, name: String): Result<Subscription> {
        val trimmed = displayName(name) ?: return Result.failure(EngineException(ErrorCode.InvalidArgument))
        val changed = subscriptions.value.firstOrNull { it.id == id }?.copy(name = trimmed)
            ?: return Result.failure(EngineException(ErrorCode.NotFound))
        subscriptions.update { all -> all.map { if (it.id == id) changed else it } }
        return Result.success(changed)
    }

    // Preview nodes have no original feed or credentials.
    override suspend fun nodeShareLink(nodeId: String): Result<ShareLink> = Result.failure(EngineException(ErrorCode.NotFound))

    override suspend fun removeSubscription(id: String): Result<Unit> {
        subscriptions.update { all -> all.filterNot { it.id == id } }
        return Result.success(Unit)
    }

    override suspend fun setSubscriptionUserAgent(id: String, userAgent: String?): Result<Subscription> {
        val agent = userAgentOf(userAgent).getOrElse { return Result.failure(it) }
        val changed = subscriptions.value.firstOrNull { it.id == id }?.copy(userAgent = agent)
            ?: return Result.failure(EngineException(ErrorCode.NotFound))
        subscriptions.update { all -> all.map { if (it.id == id) changed else it } }
        return Result.success(changed)
    }

    override suspend fun previewStaticNodes(rows: List<StaticNodeDraft>): Result<List<StaticNodeResult>> = staticEditorRequiresCore()

    override suspend fun addStaticNodes(rows: List<StaticNodeDraft>): Result<List<StaticNodeResult>> = staticEditorRequiresCore()

    override suspend fun readStaticNode(id: String): Result<StaticNode> = staticEditorRequiresCore()

    override suspend fun updateStaticNode(expected: StaticNode, node: StaticNodeDraft): Result<StaticNodeUpdateResult> = staticEditorRequiresCore()

    override suspend fun removeStaticNode(expected: StaticNode, confirmed: Boolean): Result<Unit> = staticEditorRequiresCore()

    private fun <T> staticEditorRequiresCore(): Result<T> = Result.failure(EngineException(ErrorCode.Internal))

    override suspend fun setMode(mode: Mode): Result<Unit> {
        settingsFlow.update { it.copy(mode = mode) }
        return Result.success(Unit)
    }

    /** Pages the sample's rows and refuses the limits the core refuses. */
    override suspend fun rules(offset: Int, limit: Int): Result<RulePage> {
        if (offset < 0 || limit !in 1..MAX_RULES_PAGE) return Result.failure(EngineException(ErrorCode.InvalidArgument))
        val rows = data.rows
        return Result.success(RulePage(ruleSource.value.rulesRevision, rows.size, rows.drop(offset).take(limit)))
    }

    /** Only the template's options change: they change no row. */
    override suspend fun setRuleSource(template: String, options: RuleOptions): Result<Unit> {
        if (template != FakeRuleMode.TEMPLATE) return Result.failure(EngineException(ErrorCode.NotFound))
        ruleSource.update { it.copy(options = options) }
        return Result.success(Unit)
    }

    override suspend fun setDnsUpstreams(servers: List<String>): Result<Unit> {
        if (servers.size !in 1..4) return Result.failure(EngineException(ErrorCode.InvalidArgument))
        val endpoints = servers.map { fakeResolver(it) ?: return Result.failure(EngineException(ErrorCode.InvalidArgument)) }
        if (endpoints.distinct().size != endpoints.size) return Result.failure(EngineException(ErrorCode.InvalidArgument))
        dnsUpstreams = endpoints
        return Result.success(Unit)
    }

    override suspend fun effectiveConfig(): Result<EffectiveConfig> =
        Result.success(FakeRuleMode.effectiveConfig(settingsFlow.value.mode, groups.value, ruleSource.value, data.configLines, dnsUpstreams))

    override suspend fun usage(): Result<Usage> = Result.success(data.usage)

    override suspend fun trafficSeries(seconds: Long): Result<TrafficSeries> {
        if (seconds !in 1L..3_600L) return Result.failure(EngineException(ErrorCode.InvalidArgument))
        val live = traffic.value
        val series = data.series ?: SampleSeries(0, live.upHistory, live.downHistory)
        return Result.success(series.window(seconds.toInt()))
    }

    private val nodesById = data.nodes.associateBy { it.id }

    /** As the core's: a subscription's nodes leave with it; nodes of no subscription (written into the configuration) stay. */
    override suspend fun nodes(ids: List<String>?): Result<List<NodeInfo>> {
        val subscribed = subscriptions.value.mapTo(HashSet()) { it.id }
        val asked = if (ids == null) data.nodes else ids.mapNotNull(nodesById::get)
        return Result.success(asked.filter { it.subscriptionId == null || it.subscriptionId in subscribed })
    }

    override suspend fun scoreState(): Result<ScoreState> = Result.success(data.scoreState)

    override suspend fun configAssets(): Result<ConfigAssets> = Result.success(assets)

    override suspend fun updateConfigAssets(): Result<ConfigAssets> {
        if (assets.updateWaitingForNextStart) return Result.failure(EngineException(ErrorCode.Busy))
        if (status.value !is Status.Running) {
            assets = assets.copy(lastResult = "failed", reason = "not_connected")
            return Result.failure(EngineException(ErrorCode.FetchFailed))
        }
        val checking = assets.copy(lastResult = "checking", reason = null)
        assets = if (checking.release == GEO_RELEASE) {
            checking.copy(lastResult = "up_to_date")
        } else {
            checking.copy(lastResult = "ready_on_next_start", updateWaitingForNextStart = true)
        }
        return Result.success(checking)
    }

    override suspend fun setPerApp(perApp: PerApp): Result<Unit> {
        settingsFlow.update { it.copy(perApp = perApp) }
        return Result.success(Unit)
    }

    /** The ring's level, Info as after the core's `init`. */
    var logLevel = LogLevel.Info

    /** None: the fake never crashes. A test sets some to stand for the core's crash files. */
    var crashes = emptyList<CrashFile>()

    /** The highest number a crash file's name held, as the core's mark. */
    private var crashMark = 0L

    override suspend fun diagnostics(level: LogLevel?): Result<CoreDiagnostics> {
        crashMark = maxOf(crashMark, crashes.maxOfOrNull { it.name.removeSuffix(".txt").toLongOrNull() ?: 0L } ?: 0L)
        val files = crashes.joinToString(",") { """{"name":"${it.name}","bytes":${it.bytes}}""" }
        val json = """{"level":"${logLevel.name.lowercase()}","crashes":[$files],"crashMark":$crashMark}"""
        return Result.success(CoreDiagnostics(logLevel, crashes, crashMark, json))
    }

    var recentFlowRows = RECENT_FLOWS

    override suspend fun recentFlows(): Result<List<RecentFlow>> =
        Result.success(if (status.value is Status.Running) recentFlowRows else emptyList())

    override suspend fun setLogLevel(level: LogLevel): Result<Unit> {
        logLevel = level
        return Result.success(Unit)
    }

    override suspend fun clearCrashes(names: List<String>, mark: Long): Result<Unit> {
        crashes = crashes.filterNot { it.name in names && (it.name.removeSuffix(".txt").toLongOrNull() ?: Long.MAX_VALUE) <= mark }
        return Result.success(Unit)
    }

    companion object {
        private const val MAX_RULES_PAGE = 500
        private const val GEO_RELEASE = "2026-11-23"
        private val RECENT_FLOWS = listOf(
            RecentFlow("tcp", "172.19.0.1:41002", "192.0.2.2:443", "example.test", "g-Proxy", 371, true),
            RecentFlow("udp", "172.19.0.1:41001", "192.0.2.1:443", null, "direct", 12, false),
        )

        private fun node(id: String, name: String, latencyMs: Int?) =
            Member(id = id, name = name, latencyMs = latencyMs, subscriptionId = "s1")

        /** Ids of the core's shape, `n-` and 16 hex digits, so effectiveConfig's codes are too; in the order of their names. */
        private val NODES = listOf(
            node("n-369a8a63c1d2e3f4", "hk-01", 42),
            node("n-4b1e07d9a2c3b4d5", "hk-02", 57),
            node("n-85d011c7e5f60718", "jp-01", 88),
            node("n-c2f5a8e1b3c4d5e6", "us-01", 171),
        )

        private fun nodeInfo(groups: List<Group>): List<NodeInfo> = groups.asSequence()
            .flatMap { it.members }
            .filterNot { it.isGroup }
            .distinctBy { it.id }
            .mapIndexed { index, member ->
                val tests = if (member.failed || member.latencyMs != null) listOf(LatencyTest(0, member.latencyMs)) else emptyList()
                NodeInfo(member.id, member.subscriptionId, "trojan", "node-${index + 1}.example.net", 443, "tcp", true, tests)
            }.toList()

        /** The group template before rule mode: Proxy, Auto and one region. The goldens were drawn with it. */
        val SAMPLE_GROUPS = listOf(
            Group(
                id = "g-Proxy",
                name = "Proxy",
                kind = GroupKind.Selector,
                members = listOf(Member(id = "g-Auto", name = "Auto", isGroup = true), Member(id = "g-HK", name = "HK", isGroup = true)) + NODES,
                selectedId = "g-Auto",
            ),
            Group(id = "g-Auto", name = "Auto", kind = GroupKind.UrlTest, members = NODES, selectedId = NODES[0].id),
            Group(id = "g-HK", name = "HK", kind = GroupKind.UrlTest, members = NODES.take(2), selectedId = NODES[0].id),
        )

        /** The template as the core builds it: [SAMPLE_GROUPS] with the 串流 and AI selectors, which follow Proxy until the user picks. */
        val TEMPLATE_GROUPS: List<Group> = run {
            val proxy = SAMPLE_GROUPS.first()
            val follower = Member(id = proxy.id, name = proxy.name, isGroup = true)
            fun selector(name: String) = Group("g-$name", name, GroupKind.Selector, listOf(follower) + proxy.members, selectedId = proxy.id)
            SAMPLE_GROUPS.take(2) + selector("Streaming") + selector("AI") + SAMPLE_GROUPS.drop(2)
        }

        /** A minute of download near 3.9 MB/s and upload near 330 KB/s: two slow waves around each level. */
        val SAMPLE_TRAFFIC: Traffic = run {
            fun series(level: Double, swing: Double, seed: Int) = List(60) { i ->
                val t = (i + seed).toDouble()
                (level + swing * (0.6 * sin(t / 9.0) + 0.4 * sin(t / 3.4 + seed))).toLong()
            }
            val down = series(3_900_000.0, 500_000.0, 1)
            val up = series(330_000.0, 60_000.0, 5)
            Traffic(upRate = 330_000, downRate = 3_900_000, upTotal = up.sum(), downTotal = down.sum(), upHistory = up, downHistory = down)
        }

        /** What a fetch in this engine finds: share links, at the epoch. */
        val FETCHED = SubscriptionFetch(0, SubscriptionFormat.ShareLinks)

        val SAMPLE_SUBSCRIPTIONS = listOf(
            Subscription(id = "s1", name = "harbor", url = "https://example.invalid/sub", nodes = 4, lastFetch = FETCHED),
        )
    }
}

private const val NAME_CAP = 64

private fun displayName(text: String): String? {
    if (text.any { it.isISOControl() || it == '\u2028' || it == '\u2029' }) return null
    return text.trim().takeIf { it.codePointCount(0, it.length) in 1..NAME_CAP }
}

private const val MAX_USER_AGENT = 256

/**
 * The core's rule for a subscription's User-Agent (`fetch::user_agent`): null is the default; a value must be 1 to 256
 * bytes of printable ASCII and not blank, and is kept trimmed. Printable ASCII has one byte per char, so the length in
 * chars is the length in bytes for every value that passes.
 */
private fun userAgentOf(text: String?): Result<String?> = when {
    text == null -> Result.success(null)

    text.length !in 1..MAX_USER_AGENT || text.any { it !in ' '..'~' } || text.isBlank() ->
        Result.failure(EngineException(ErrorCode.InvalidArgument))

    else -> Result.success(text.trim())
}

/** The number of an id `s<n>`, or 0 for an id of another shape. */
private fun subscriptionNumber(subscription: Subscription): Int = subscription.id.removePrefix("s").toIntOrNull() ?: 0
