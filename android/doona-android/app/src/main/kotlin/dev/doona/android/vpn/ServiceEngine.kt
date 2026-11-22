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
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
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
    /** Starts the service with the generation of a requested start. */
    private val startService: (Long) -> Unit,
    private val io: CoroutineDispatcher,
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
    override val settings: StateFlow<Settings> = _settings.asStateFlow()

    private val reads = Mutex()
    private var revision = -1L // Guarded by reads.

    internal fun launchIn(scope: CoroutineScope, lane: CoroutineDispatcher) {
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

    /** An unreadable policy fails each start on its own; here it only stops updating the settings shown. */
    private suspend fun followPerApp() {
        perAppSettings.perApp
            .catch { e -> VpnLog.warn("per-app policy unreadable: ${e.javaClass.simpleName}") }
            .collect { perApp -> _settings.update { it.copy(perApp = perApp) } }
    }

    override fun connect() {
        val generation = controller.requestStart() ?: return
        try {
            startService(generation)
        } catch (e: IllegalStateException) {
            // The system refused the foreground service, so nothing will take the start.
            controller.stop()
            throw e
        }
    }

    override fun disconnect() = controller.stop()

    override suspend fun select(groupId: String, memberId: String?): Result<Unit> =
        command("select", SelectArgs.serializer(), SelectArgs(groupId, memberId), Unit.serializer())

    override suspend fun testLatency(groupId: String?): Result<Unit> =
        command("testLatency", GroupArgs.serializer(), GroupArgs(groupId), Unit.serializer())

    override suspend fun addSubscription(url: String, name: String?): Result<Subscription> =
        command("addSubscription", AddSubscriptionArgs.serializer(), AddSubscriptionArgs(url, name), SubscriptionDto.serializer())
            .map(SubscriptionDto::toSubscription)

    override suspend fun updateSubscription(id: String): Result<Subscription> =
        command("updateSubscription", IdArgs.serializer(), IdArgs(id), SubscriptionDto.serializer()).map(SubscriptionDto::toSubscription)

    override suspend fun removeSubscription(id: String): Result<Unit> =
        command("removeSubscription", IdArgs.serializer(), IdArgs(id), Unit.serializer())

    override suspend fun setMode(mode: Mode): Result<Unit> = command("setMode", ModeArgs.serializer(), ModeArgs(mode), Unit.serializer())

    override suspend fun setPerApp(perApp: PerApp): Result<Unit> = try {
        perAppSettings.update { perApp }
        controller.rebuild()
        Result.success(Unit)
    } catch (e: IOException) {
        VpnLog.warn("setPerApp threw ${e.javaClass.simpleName}")
        Result.failure(EngineException(ErrorCode.Internal))
    }

    private suspend fun <A, R> command(
        name: String,
        argsSerializer: SerializationStrategy<A>,
        args: A,
        result: DeserializationStrategy<R>,
    ): Result<R> {
        // A core whose init failed or speaks another schema takes no call and no snapshot.
        if (opener.schema() != CORE_SCHEMA) return Result.failure(EngineException(ErrorCode.Internal))
        val begun = System.nanoTime()
        val outcome = try {
            Result.success(withContext(io) { CoreJson.result(core.call(name, CoreJson.encode(argsSerializer, args)), result) })
        } catch (e: CancellationException) {
            throw e
        } catch (e: EngineException) {
            Result.failure(e)
        } catch (e: Exception) {
            VpnLog.warn("$name reply unreadable: ${e.javaClass.simpleName}")
            Result.failure(EngineException(ErrorCode.Internal))
        }
        val code = (outcome.exceptionOrNull() as? EngineException)?.code?.name ?: "ok"
        VpnLog.info("$name: $code after ${(System.nanoTime() - begun) / 1_000_000} ms")
        poll()
        return outcome
    }

    /** Reads a snapshot and publishes its catalog, if it has one; null when the core cannot be read. */
    private suspend fun poll(): SnapshotDto? = reads.withLock {
        try {
            withContext(io) { CoreJson.snapshot(core.snapshot(revision)) }.also { snapshot ->
                snapshot.catalog?.let { publish(snapshot.revision, it) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            VpnLog.warn("snapshot unreadable: ${e.javaClass.simpleName}")
            null
        }
    }

    private fun publish(newRevision: Long, catalog: CatalogDto) {
        revision = newRevision
        _groups.value = catalog.groups.map(GroupDto::toGroup)
        _subscriptions.value = catalog.subscriptions.map(SubscriptionDto::toSubscription)
        _settings.update { it.copy(mode = catalog.mode) }
        _ready.value = true
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
            val engine = ServiceEngine(core, opener, controller, perApp, { DoonaVpnService.start(app, it) }, Dispatchers.IO)
            engine.launchIn(scope, Dispatchers.IO.limitedParallelism(1))
            return engine
        }
    }
}
