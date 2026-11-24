package dev.doona.android.vpn

import android.content.Context
import android.os.Process
import android.os.SystemClock
import dev.doona.android.data.settings.PerAppSettings
import dev.doona.android.engine.Catalog
import dev.doona.android.engine.CoreDiagnostics
import dev.doona.android.engine.EffectiveConfig
import dev.doona.android.engine.Engine
import dev.doona.android.engine.EngineException
import dev.doona.android.engine.ErrorCode
import dev.doona.android.engine.FailureReason
import dev.doona.android.engine.Group
import dev.doona.android.engine.LogLevel
import dev.doona.android.engine.Mode
import dev.doona.android.engine.NodeInfo
import dev.doona.android.engine.PerApp
import dev.doona.android.engine.RebuildFailedException
import dev.doona.android.engine.RuleOptions
import dev.doona.android.engine.RulePage
import dev.doona.android.engine.ScoreState
import dev.doona.android.engine.Settings
import dev.doona.android.engine.Status
import dev.doona.android.engine.Subscription
import dev.doona.android.engine.TodayTraffic
import dev.doona.android.engine.Traffic
import dev.doona.android.engine.TrafficSeries
import dev.doona.android.engine.Usage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.builtins.serializer
import java.io.IOException

/**
 * The process-wide [Engine] behind the UI, the tile and the notification (ARCH D2). The tunnel status is the
 * [TunnelController]'s; the catalog, traffic and actions come from the core. The catalog is read after `init`, after
 * every command that is not a read, and whenever a snapshot shows a new revision. Snapshots are taken every 5 s while running (health),
 * every second while running and someone collects [traffic], and every second while a latency test runs.
 */
class ServiceEngine internal constructor(
    private val core: CoreBridge,
    private val opener: CoreOpener,
    internal val controller: TunnelController,
    private val perAppSettings: PerAppSettings,
    /** Starts the service with the generation of a requested start; false when the system finds no service to start. */
    private val startService: (Long) -> Boolean,
    private val io: CoroutineDispatcher,
    /** Owns the engine's own work: polling, and commands the core has begun. */
    private val scope: CoroutineScope,
    private val expireDebugCapture: () -> Job?,
    /** The pause before the settings read a per-app policy again after a read failed. */
    private val perAppRetryMs: Long = PER_APP_RETRY_MS,
) : Engine {
    private val _ready = MutableStateFlow(false)
    override val ready: StateFlow<Boolean> = _ready.asStateFlow()
    override val status: StateFlow<Status> get() = controller.status

    /** Platform start refusal; null for other session outcomes. */
    val startBlock = controller.session.map { it.startBlock }.distinctUntilChanged()

    private val _traffic = MutableStateFlow(Traffic())
    override val traffic: StateFlow<Traffic> = _traffic.asStateFlow()
    private val _today = MutableStateFlow(TodayTraffic())
    override val today: StateFlow<TodayTraffic> = _today.asStateFlow()
    private val _groups = MutableStateFlow(emptyList<Group>())
    override val groups: StateFlow<List<Group>> = _groups.asStateFlow()
    private val _subscriptions = MutableStateFlow(emptyList<Subscription>())
    override val subscriptions: StateFlow<List<Subscription>> = _subscriptions.asStateFlow()
    private val _catalog = MutableStateFlow<Catalog?>(null)
    override val catalog: StateFlow<Catalog?> = _catalog.asStateFlow()
    private val _settings = MutableStateFlow(Settings())
    private val perAppEdits = Mutex()
    override val settings: StateFlow<Settings> = _settings.asStateFlow()
    private val _perAppLoaded = MutableStateFlow(false)
    override val perAppLoaded: StateFlow<Boolean> = _perAppLoaded.asStateFlow()

    /**
     * The last snapshot's warning and the session that ran as it was taken. [warning] shows it only while that session
     * runs, so a run shows nothing an earlier run reported before its own first snapshot.
     */
    private val lastWarning = MutableStateFlow<Pair<Session, ErrorCode?>?>(null)
    override val warning: StateFlow<ErrorCode?> =
        combine(controller.session, lastWarning) { session, last ->
            last?.second.takeIf { session.status is Status.Running && last?.first == session }
        }.stateIn(scope, SharingStarted.Eagerly, null)

    private val publishing = Any()

    @Volatile
    private var revision = -1L // Written under publishing: the revision of the catalog the flows show.

    /** [lane] is the controller's single thread. */
    internal fun launch(lane: CoroutineDispatcher) {
        scope.launch(lane) { controller.run() }
        scope.launch { open() }
        scope.launch { followPerApp() }
        scope.launch { checkHealth() }
        scope.launch { sampleTraffic() }
        scope.launch { followProbes() }
    }

    /** Loads the catalog once the core is open. A core that cannot open is shown failed instead of loading forever. */
    private suspend fun open() {
        when (opener.schema()) {
            CORE_SCHEMA -> poll()
            null -> unusable(FailureReason.StartFailed)
            else -> unusable(FailureReason.IncompatibleCore)
        }
    }

    private fun unusable(reason: FailureReason) {
        controller.unavailable(reason)
        _ready.value = true
    }

    /** The library lacks a function `init` did not need: the core is shown as if `init` had failed, and takes no more calls. */
    private fun unlinked(name: String, e: LinkageError) {
        VpnLog.warn("$name threw ${e.javaClass.simpleName}")
        opener.disable()
        unusable(FailureReason.StartFailed)
    }

    /**
     * An unreadable policy fails each start on its own. Here the settings keep the policy shown last and read the file
     * again after a pause, so they follow it once it can be read, as after the save that repairs it.
     */
    private suspend fun followPerApp() {
        var failing = false
        perAppSettings.perApp
            .retryWhen { e, _ ->
                if (!failing) VpnLog.warn("per-app policy unreadable: ${e.javaClass.simpleName}")
                failing = true
                // An answer all the same: nothing readable is stored, so a save is what repairs the file.
                _perAppLoaded.value = true
                delay(perAppRetryMs)
                true
            }
            .collect { perApp ->
                failing = false
                _settings.update { it.copy(perApp = perApp) }
                _perAppLoaded.value = true
            }
    }

    override fun connect() {
        val generation = controller.requestStart() ?: return
        var sent = false
        try {
            sent = startService(generation)
        } finally {
            // The system refused the foreground service or found none, so nothing will take this start.
            if (!sent) {
                controller.abandon(generation)
                VpnLog.action("start", "dispatch", "failed", FailureReason.StartFailed.name)
            }
        }
    }

    override fun disconnect() = controller.stop()

    override suspend fun select(groupId: String, memberId: String?): Result<Unit> =
        command("select", SelectArgs.serializer(), SelectArgs(groupId, memberId), Unit.serializer(), ordered = true)

    override suspend fun testLatency(groupId: String?): Result<Unit> =
        command("testLatency", GroupArgs.serializer(), GroupArgs(groupId), Unit.serializer(), ordered = true)

    override suspend fun addSubscription(url: String, name: String?, userAgent: String?): Result<Subscription> =
        command("addSubscription", AddSubscriptionArgs.serializer(), AddSubscriptionArgs(url, name, userAgent), SubscriptionDto.serializer())
            .map(SubscriptionDto::toSubscription)

    override suspend fun updateSubscription(id: String): Result<Subscription> =
        command("updateSubscription", IdArgs.serializer(), IdArgs(id), SubscriptionDto.serializer()).map(SubscriptionDto::toSubscription)

    override suspend fun removeSubscription(id: String): Result<Unit> =
        command("removeSubscription", IdArgs.serializer(), IdArgs(id), Unit.serializer())

    override suspend fun setSubscriptionUserAgent(id: String, userAgent: String?): Result<Subscription> =
        command(
            "setSubscriptionUserAgent",
            SubscriptionUserAgentArgs.serializer(),
            SubscriptionUserAgentArgs(id, userAgent),
            SubscriptionDto.serializer(),
        ).map(SubscriptionDto::toSubscription)

    override suspend fun setMode(mode: Mode): Result<Unit> = command("setMode", ModeArgs.serializer(), ModeArgs(mode), Unit.serializer())

    override suspend fun rules(offset: Int, limit: Int): Result<RulePage> =
        read("rules", RulesArgs.serializer(), RulesArgs(offset, limit), RulesDto.serializer()).map(RulesDto::toRulePage)

    override suspend fun setRuleSource(template: String, options: RuleOptions): Result<Unit> =
        command("setRuleSource", RuleSourceArgs.serializer(), RuleSourceArgs.template(template, options), Unit.serializer())

    override suspend fun effectiveConfig(): Result<EffectiveConfig> =
        read("effectiveConfig", Unit.serializer(), Unit, EffectiveConfigDto.serializer()).map(EffectiveConfigDto::toEffectiveConfig)

    override suspend fun usage(): Result<Usage> = read("usage", Unit.serializer(), Unit, UsageDto.serializer()).map(UsageDto::toUsage)

    override suspend fun trafficSeries(seconds: Long): Result<TrafficSeries> =
        read("trafficSeries", SeriesArgs.serializer(), SeriesArgs(seconds), TrafficSeriesDto.serializer()).map(TrafficSeriesDto::toTrafficSeries)

    override suspend fun nodes(ids: List<String>?): Result<List<NodeInfo>> =
        read("nodes", NodesArgs.serializer(), NodesArgs(ids), NodesDto.serializer()).map(NodesDto::toNodes)

    override suspend fun scoreState(): Result<ScoreState> =
        read("scoreState", Unit.serializer(), Unit, ScoreStateDto.serializer()).map(ScoreStateDto::toScoreState)

    override suspend fun setPerApp(perApp: PerApp): Result<Unit> =
        // Stored, shown and rebuilt as one unit in the engine's scope: a caller that leaves the page cannot split them.
        scope.async { savePerApp(perApp) }.await()

    override suspend fun diagnostics(level: LogLevel?): Result<CoreDiagnostics> =
        readCore("diagnostics", DiagnosticsArgs.serializer(), DiagnosticsArgs(level?.let(CoreLevel::of)), DiagnosticsReplyReader)
            .map { it.dto.toCoreDiagnostics(it.json) }

    override suspend fun setLogLevel(level: LogLevel): Result<Unit> {
        val outcome = if (opener.schema() == CORE_SCHEMA) {
            call("setLogLevel", CoreJson.encode(LevelArgs.serializer(), LevelArgs(CoreLevel.of(level))), Unit.serializer())
        } else {
            Result.failure(EngineException(ErrorCode.Internal))
        }
        val code = (outcome.exceptionOrNull() as? EngineException)?.code?.name ?: "ok"
        VpnLog.action("setLogLevel", "complete", if (outcome.isSuccess) "ok" else "failed", code)
        return outcome
    }

    override suspend fun clearCrashes(names: List<String>, mark: Long): Result<Unit> =
        command("clearCrashes", ClearCrashesArgs.serializer(), ClearCrashesArgs(names, mark), Unit.serializer())

    private suspend fun savePerApp(perApp: PerApp): Result<Unit> {
        val rebuild = try {
            // One save and its publication at a time, so an older save cannot show its policy after a newer one.
            perAppEdits.withLock {
                val saved = perAppSettings.update { perApp }
                // Shown at once: after a failed read the collector reads the file again only after a pause.
                _settings.update { it.copy(perApp = saved) }
                _perAppLoaded.value = true
            }
            controller.rebuild()
        } catch (e: IOException) {
            VpnLog.warn("setPerApp threw ${e.javaClass.simpleName}")
            return Result.failure(EngineException(ErrorCode.Internal))
        }
        return if (rebuild == null) Result.success(Unit) else awaitRebuilt(rebuild)
    }

    /** The outcome of the rebuild [generation]; a newer command that replaced it, such as a stop, is not its failure. */
    private suspend fun awaitRebuilt(generation: Long): Result<Unit> {
        val settled = controller.session.first { it.generation != generation || it.status != Status.Starting }
        val failed = settled.status as? Status.Failed
        return if (settled.generation == generation && failed != null) Result.failure(RebuildFailedException(failed.reason)) else Result.success(Unit)
    }

    /** A place in the queue of ordered commands: [before] is the call ahead, [done] is completed when this one's call has returned. */
    private class Turn(val before: Job, val done: CompletableJob)

    private val turns = Any()
    private var lastTurn: Job = Job().apply { complete() } // Guarded by turns.

    /** Takes the next place in the queue. The caller's order is the queue's, so this must be the first thing a command does. */
    private fun takeTurn(): Turn = synchronized(turns) { Turn(lastTurn, Job()).also { lastTurn = it.done } }

    /**
     * Runs a command in the engine's scope. An [ordered] one waits for the ordered commands taken before it, whoever sent
     * them: the core keeps the last choice it is given, so taps from any screen must reach it in tap order, and a screen
     * that is left before its tap's turn comes does not take the tap with it. Only the core calls are queued; the
     * refresh after one is not.
     */
    private suspend fun <A, R> command(
        name: String,
        argsSerializer: SerializationStrategy<A>,
        args: A,
        result: DeserializationStrategy<R>,
        ordered: Boolean = false,
    ): Result<R> {
        val turn = if (ordered) takeTurn() else null
        // The core commits a command it has begun, so the refresh after it must run even if the caller is cancelled.
        return scope.async {
            val begun = System.nanoTime()
            val outcome = try {
                turn?.before?.join()
                expireDebugCapture()?.join()
                // A core whose init failed or speaks another schema takes no call and no snapshot.
                if (opener.schema() != CORE_SCHEMA) null else call(name, CoreJson.encode(argsSerializer, args), result)
            } finally {
                turn?.done?.complete()
            }
            val reply = outcome ?: Result.failure(EngineException(ErrorCode.Internal))
            val code = (reply.exceptionOrNull() as? EngineException)?.code?.name ?: "ok"
            VpnLog.action(name, if (outcome == null) "dispatch" else "complete", if (reply.isSuccess) "ok" else "failed", code, (System.nanoTime() - begun) / 1_000_000)
            if (outcome != null) poll()
            reply
        }.await()
    }

    /**
     * Runs a read in the caller's job: a caller that leaves before the core is reached never reaches it. A read changes
     * nothing, so it waits for no ordered command and reads no catalog after.
     */
    private suspend fun <A, R> read(
        name: String,
        argsSerializer: SerializationStrategy<A>,
        args: A,
        result: DeserializationStrategy<R>,
    ): Result<R> {
        expireDebugCapture()?.join()
        return readCore(name, argsSerializer, args, result)
    }

    private suspend fun <A, R> readCore(name: String, argsSerializer: SerializationStrategy<A>, args: A, result: DeserializationStrategy<R>): Result<R> {
        if (opener.schema() != CORE_SCHEMA) return Result.failure(EngineException(ErrorCode.Internal))
        val begun = System.nanoTime()
        return call(name, CoreJson.encode(argsSerializer, args), result).also { outcome ->
            val code = (outcome.exceptionOrNull() as? EngineException)?.code?.name ?: "ok"
            VpnLog.info("$name: $code after ${(System.nanoTime() - begun) / 1_000_000} ms")
        }
    }

    private suspend fun <R> call(name: String, argsJson: String, result: DeserializationStrategy<R>): Result<R> {
        val outcome = try {
            Result.success(withContext(io) { CoreJson.result(core.call(name, argsJson), result) })
        } catch (e: CancellationException) {
            throw e
        } catch (e: EngineException) {
            Result.failure(e)
        } catch (e: Exception) {
            VpnLog.warn("$name reply unreadable: ${e.javaClass.simpleName}")
            Result.failure(EngineException(ErrorCode.Internal))
        } catch (e: LinkageError) {
            unlinked(name, e)
            Result.failure(EngineException(ErrorCode.Internal))
        }
        return outcome
    }

    /** Reads a snapshot and publishes its catalog, if it has one; null when the core cannot be read. */
    private suspend fun poll(): SnapshotDto? {
        // A core that lost a native function takes no further snapshot.
        if (opener.schema() != CORE_SCHEMA) return null
        val during = controller.session.value
        return try {
            withContext(io) {
                CoreJson.snapshot(core.snapshot(revision)).also { snapshot ->
                    _today.value = snapshot.today.toTodayTraffic()
                    snapshot.catalog?.let { publish(snapshot.revision, it.toCatalog()) }
                    lastWarning.value = during to snapshot.warning?.let(::errorCodeOf)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            VpnLog.warn("snapshot unreadable: ${e.javaClass.simpleName}")
            null
        } catch (e: LinkageError) {
            unlinked("snapshot", e)
            null
        }
    }

    /**
     * Polls run concurrently and can finish out of order: a catalog older than the one shown is dropped. The catalog is
     * mapped before and published in one write; the flows that repeat parts of it follow.
     */
    private fun publish(revision: Long, catalog: Catalog) {
        synchronized(publishing) {
            if (revision <= this.revision) return
            this.revision = revision
            _catalog.value = catalog
            _groups.value = catalog.groups
            _subscriptions.value = catalog.subscriptions
            _settings.update { it.copy(mode = catalog.mode) }
            _ready.value = true
        }
    }

    /** Runs whatever the screen does, so a core that dies with the screen off is noticed (ARCH D10). */
    private suspend fun checkHealth() {
        controller.session.collectLatest { session ->
            if (session.status !is Status.Running) return@collectLatest
            while (true) {
                delay(HEALTH_INTERVAL_MS)
                if (poll()?.alive != true) {
                    VpnLog.warn("health check: data path down")
                    controller.coreStopped(session)
                    return@collectLatest
                }
            }
        }
    }

    private suspend fun sampleTraffic() {
        combine(controller.status, _traffic.subscriptionCount) { status, watchers -> (status is Status.Running) to (watchers > 0) }
            .distinctUntilChanged()
            .collectLatest { (running, watched) ->
                when {
                    !running -> _traffic.value = Traffic()
                    watched -> sampleRates()
                }
            }
    }

    private suspend fun sampleRates() {
        var previous: TrafficSample? = null
        while (true) {
            poll()?.let { snapshot ->
                val sample = TrafficSample(snapshot.traffic, SystemClock.elapsedRealtime())
                val baseline = previous
                _traffic.update { it.next(baseline, sample) }
                previous = sample
            }
            delay(SAMPLE_INTERVAL_MS)
        }
    }

    /** Probe results arrive after `testLatency` returns, and must show while stopped too. */
    private suspend fun followProbes() {
        _groups.map { groups -> groups.any { it.testing } }.distinctUntilChanged().collectLatest { testing ->
            while (testing) {
                delay(SAMPLE_INTERVAL_MS)
                poll()
            }
        }
    }

    companion object {
        private const val HEALTH_INTERVAL_MS = 5_000L
        private const val SAMPLE_INTERVAL_MS = 1_000L
        private const val PER_APP_RETRY_MS = 5_000L

        /** The process-wide engine. The app container calls this once; the core library loads later, on IO. */
        fun create(context: Context, endDebugCapture: () -> Unit, expireDebugCapture: () -> Job?): ServiceEngine {
            val app = context.applicationContext
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val core = DeferredCore { NativeCore }
            // noBackupFilesDir keeps state.json, which holds subscription URLs with tokens, out of every backup. Asking for
            // it makes it, which is disk work: the opener asks on IO.
            val opener = CoreOpener(core, { app.noBackupFilesDir.path }, Dispatchers.IO)
            val perApp = PerAppSettings.create(app)
            val controller = TunnelController(
                core = core,
                opener = opener,
                perApp = { perApp.perApp.first() },
                scope = scope,
                killProcess = { Process.killProcess(Process.myPid()) },
                endDebugCapture = endDebugCapture,
                expireDebugCapture = expireDebugCapture,
            )
            val engine = ServiceEngine(core, opener, controller, perApp, { DoonaVpnService.start(app, it) }, Dispatchers.IO, scope, expireDebugCapture)
            engine.launch(Dispatchers.IO.limitedParallelism(1))
            return engine
        }
    }
}
