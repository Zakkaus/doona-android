package dev.doona.android.vpn

import android.content.Context
import android.os.Process
import android.os.SystemClock
import dev.doona.android.data.settings.PerAppSettings
import dev.doona.android.engine.Engine
import dev.doona.android.engine.EngineException
import dev.doona.android.engine.ErrorCode
import dev.doona.android.engine.FailureReason
import dev.doona.android.engine.Group
import dev.doona.android.engine.Mode
import dev.doona.android.engine.PerApp
import dev.doona.android.engine.Settings
import dev.doona.android.engine.Status
import dev.doona.android.engine.Subscription
import dev.doona.android.engine.Traffic
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
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retryWhen
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
 * every command and whenever a snapshot shows a new revision. Snapshots are taken every 5 s while running (health),
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
    /** The pause before the settings read a per-app policy again after a read failed. */
    private val perAppRetryMs: Long = PER_APP_RETRY_MS,
) : Engine {
    private val _ready = MutableStateFlow(false)
    override val ready: StateFlow<Boolean> = _ready.asStateFlow()
    override val status: StateFlow<Status> get() = controller.status
    private val _traffic = MutableStateFlow(Traffic())
    override val traffic: StateFlow<Traffic> = _traffic.asStateFlow()
    private val _groups = MutableStateFlow(emptyList<Group>())
    override val groups: StateFlow<List<Group>> = _groups.asStateFlow()
    private val _subscriptions = MutableStateFlow(emptyList<Subscription>())
    override val subscriptions: StateFlow<List<Subscription>> = _subscriptions.asStateFlow()
    private val _settings = MutableStateFlow(Settings())
    private val perAppEdits = Mutex()
    override val settings: StateFlow<Settings> = _settings.asStateFlow()

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
                delay(perAppRetryMs)
                true
            }
            .collect { perApp ->
                failing = false
                _settings.update { it.copy(perApp = perApp) }
            }
    }

    override fun connect() {
        val generation = controller.requestStart() ?: return
        var sent = false
        try {
            sent = startService(generation)
        } finally {
            // The system refused the foreground service or found none, so nothing will take this start.
            if (!sent) controller.abandon(generation)
        }
    }

    override fun disconnect() = controller.stop()

    override suspend fun select(groupId: String, memberId: String?): Result<Unit> =
        command("select", SelectArgs.serializer(), SelectArgs(groupId, memberId), Unit.serializer(), ordered = true)

    override suspend fun testLatency(groupId: String?): Result<Unit> =
        command("testLatency", GroupArgs.serializer(), GroupArgs(groupId), Unit.serializer(), ordered = true)

    override suspend fun addSubscription(url: String, name: String?): Result<Subscription> =
        command("addSubscription", AddSubscriptionArgs.serializer(), AddSubscriptionArgs(url, name), SubscriptionDto.serializer())
            .map(SubscriptionDto::toSubscription)

    override suspend fun updateSubscription(id: String): Result<Subscription> =
        command("updateSubscription", IdArgs.serializer(), IdArgs(id), SubscriptionDto.serializer()).map(SubscriptionDto::toSubscription)

    override suspend fun removeSubscription(id: String): Result<Unit> =
        command("removeSubscription", IdArgs.serializer(), IdArgs(id), Unit.serializer())

    override suspend fun setMode(mode: Mode): Result<Unit> = command("setMode", ModeArgs.serializer(), ModeArgs(mode), Unit.serializer())

    override suspend fun setPerApp(perApp: PerApp): Result<Unit> = try {
        // One save and its publication at a time, so an older save cannot show its policy after a newer one.
        perAppEdits.withLock {
            val saved = perAppSettings.update { perApp }
            // Shown at once: after a failed read the collector reads the file again only after a pause.
            _settings.update { it.copy(perApp = saved) }
        }
        controller.rebuild()
        Result.success(Unit)
    } catch (e: IOException) {
        VpnLog.warn("setPerApp threw ${e.javaClass.simpleName}")
        Result.failure(EngineException(ErrorCode.Internal))
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
            val outcome = try {
                turn?.before?.join()
                // A core whose init failed or speaks another schema takes no call and no snapshot.
                if (opener.schema() != CORE_SCHEMA) null else call(name, CoreJson.encode(argsSerializer, args), result)
            } finally {
                turn?.done?.complete()
            }
            if (outcome == null) Result.failure<R>(EngineException(ErrorCode.Internal)) else outcome.also { poll() }
        }.await()
    }

    private suspend fun <R> call(name: String, argsJson: String, result: DeserializationStrategy<R>): Result<R> {
        val begun = System.nanoTime()
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
        val code = (outcome.exceptionOrNull() as? EngineException)?.code?.name ?: "ok"
        VpnLog.info("$name: $code after ${(System.nanoTime() - begun) / 1_000_000} ms")
        return outcome
    }

    /** Reads a snapshot and publishes its catalog, if it has one; null when the core cannot be read. */
    private suspend fun poll(): SnapshotDto? {
        // A core that lost a native function takes no further snapshot.
        if (opener.schema() != CORE_SCHEMA) return null
        return try {
            withContext(io) {
                CoreJson.snapshot(core.snapshot(revision)).also { snapshot ->
                    snapshot.catalog?.let { publish(Catalog.of(snapshot.revision, it)) }
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

    /** Polls run concurrently and can finish out of order: a catalog older than the one shown is dropped. */
    private fun publish(catalog: Catalog) {
        synchronized(publishing) {
            if (catalog.revision <= revision) return
            revision = catalog.revision
            _groups.value = catalog.groups
            _subscriptions.value = catalog.subscriptions
            _settings.update { it.copy(mode = catalog.mode) }
            _ready.value = true
        }
    }

    /** One snapshot's catalog, mapped before it is published, and published whole. */
    private data class Catalog(val revision: Long, val mode: Mode, val groups: List<Group>, val subscriptions: List<Subscription>) {
        companion object {
            fun of(revision: Long, dto: CatalogDto) =
                Catalog(revision, dto.mode, dto.groups.map(GroupDto::toGroup), dto.subscriptions.map(SubscriptionDto::toSubscription))
        }
    }

    /** Runs whatever the screen does, so a core that dies with the screen off is noticed (ARCH D10). */
    private suspend fun checkHealth() {
        controller.status.collectLatest { status ->
            if (status !is Status.Running) return@collectLatest
            while (true) {
                delay(HEALTH_INTERVAL_MS)
                if (poll()?.alive != true) {
                    VpnLog.warn("health check: data path down")
                    controller.coreStopped(status)
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
                val sample = TrafficSample(snapshot.traffic.upTotal, snapshot.traffic.downTotal, SystemClock.elapsedRealtime())
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
        fun create(context: Context): ServiceEngine {
            val app = context.applicationContext
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val core = DeferredCore { NativeCore }
            // noBackupFilesDir keeps state.json, which holds subscription URLs with tokens, out of every backup.
            val opener = CoreOpener(core, app.noBackupFilesDir.path, Dispatchers.IO)
            val perApp = PerAppSettings.create(app)
            val controller = TunnelController(
                core = core,
                opener = opener,
                perApp = { perApp.perApp.first() },
                scope = scope,
                killProcess = { Process.killProcess(Process.myPid()) },
            )
            val engine = ServiceEngine(core, opener, controller, perApp, { DoonaVpnService.start(app, it) }, Dispatchers.IO, scope)
            engine.launch(Dispatchers.IO.limitedParallelism(1))
            return engine
        }
    }
}
