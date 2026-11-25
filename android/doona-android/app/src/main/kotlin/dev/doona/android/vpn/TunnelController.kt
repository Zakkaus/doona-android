package dev.doona.android.vpn

import android.os.SystemClock
import dev.doona.android.data.geo.GeoAssets
import dev.doona.android.engine.EngineException
import dev.doona.android.engine.ErrorCode
import dev.doona.android.engine.FailureReason
import dev.doona.android.engine.PerApp
import dev.doona.android.engine.Status
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.serializer
import java.io.File

/** Starting or running: a session the user has not ended. */
internal val Status.active: Boolean get() = this is Status.Starting || this is Status.Running

/** Stopped or failed: no session, and nothing left to stop. */
internal val Status.ended: Boolean get() = this is Status.Stopped || this is Status.Failed

/** A status and the generation that set it: a later session that repeats a status is still a new value. */
internal data class Session(
    val generation: Long,
    val status: Status,
    val startBlock: VpnStartBlock? = null,
    val recovering: Boolean = false,
)

/**
 * Runs start, stop, rebuild and revoke one at a time (ARCH D15). Every accepted command takes a new generation at
 * once, on the caller's thread, so a stop or revoke invalidates a start that is still blocked in the core; that start
 * checks its generation before publishing [Status.Running] and stops a late success instead. Only the latest
 * generation publishes, so each one ends with at most one terminal status.
 */
internal class TunnelController(
    private val core: CoreBridge,
    private val opener: CoreOpener,
    private val perApp: suspend () -> PerApp,
    /** Runs the stop watchdogs; must not share a thread with [run]. */
    private val scope: CoroutineScope,
    private val killProcess: () -> Unit,
    private val endDebugCapture: () -> Unit,
    private val expireDebugCapture: () -> Job?,
    private val startBoundMs: Long = START_BOUND_MS,
    private val stopDeadlineMs: Long = STOP_DEADLINE_MS,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
    private val geoAssets: GeoAssets? = null,
) {
    private val _status = MutableStateFlow<Status>(Status.Stopped)
    val status: StateFlow<Status> = _status.asStateFlow()
    private val _session = MutableStateFlow(Session(0, Status.Stopped))

    /** [status] with its generation, for following one session to its end. */
    val session: StateFlow<Session> = _session.asStateFlow()

    private val lock = Any()
    private var generation = 0L // Guarded by lock.
    private var host: TunHost? = null // Guarded by lock; set while a session is in progress.
    private var cancelSystemRestart: (() -> Unit)? = null // Guarded by lock; null for app starts.
    private var recovery: Job? = null // Guarded by lock; set only while waiting to retry.
    private var recoveryDelayMs = FIRST_RECOVERY_DELAY_MS // Guarded by lock.
    private var runningSinceMs = 0L // Guarded by lock; elapsed time at the last Running publication.
    private val commands = Channel<Command>(Channel.UNLIMITED)

    private var coreRunning = false // Touched only by run().
    private var bootstrapDns = emptyList<String>() // Touched only by run(), like coreRunning.
    private var pendingBootstrapDns: List<String>? = null // Touched only by run().

    private var handled = 0L // Guarded by lock; written only by run(): the generation of the last command it finished.

    /** Executes commands in order until cancelled. The owner launches it once, off the main thread. */
    suspend fun run() = try {
        runCommands()
    } finally {
        // If cancellation arrived in JNI, the lane still owns the core it just started.
        withContext(NonCancellable) { stopCore() }
    }

    private suspend fun runCommands(): Unit = coroutineScope {
        launch {
            if (opener.awaitSchema() == CORE_SCHEMA) commands.send(Command.CoreOpened)
        }
        for (command in commands) {
            val commandGeneration = when (command) {
                is Command.RefreshDns -> {
                    pendingBootstrapDns = command.servers
                    applyPendingBootstrapDns()
                    continue
                }

                Command.CoreOpened -> {
                    applyPendingBootstrapDns()
                    continue
                }

                is Command.Open -> command.generation

                is Command.Close -> command.generation
            }
            stopCore()
            if (command is Command.Open && isCurrent(command.generation)) open(command)
            // The terminal status and completion are recorded together for the stop watchdog.
            synchronized(lock) {
                if (command is Command.Close && command.generation == generation) finishClose(command)
                handled = commandGeneration
            }
        }
    }

    /**
     * Shows [Status.Starting] at once, before the service exists, so a second tap or a stop sees the start; null while a
     * session is starting or running. The service's [start] must carry the returned generation.
     * During a recovery wait, Connect queues the retry immediately on the existing service instead.
     */
    fun requestStart(): Long? = synchronized(lock) {
        if (_session.value.recovering) {
            retryNow()
            return null
        }
        if (_status.value.active) return null
        generation++
        host = null
        show(generation, Status.Starting)
        generation
    }

    /** Takes a system start without an app request. Only the system-owned session can recover after core death. */
    fun startSystem(host: TunHost, startBlock: VpnStartBlock? = null, cancelRestart: () -> Unit): Long? = synchronized(lock) {
        if (!_status.value.ended || _session.value.recovering) return null
        cancelSystemRestart = cancelRestart
        recoveryDelayMs = FIRST_RECOVERY_DELAY_MS
        enqueueStart(++generation, host, startBlock)
        generation
    }

    /** Builds the tunnel [requested]; false when a stop or another command replaced the request before it arrived. */
    fun start(host: TunHost, requested: Long, startBlock: VpnStartBlock? = null): Boolean = synchronized(lock) {
        if (requested != generation || _status.value != Status.Starting || this.host != null) return false
        enqueueStart(requested, host, startBlock)
        true
    }

    private fun enqueueStart(g: Long, host: TunHost, startBlock: VpnStartBlock? = null) {
        this.host = host
        show(g, Status.Starting)
        commands.trySend(Command.Open(g, host, startBlock))
    }

    fun refreshBootstrapDns(servers: List<String>) {
        // An empty list cannot replace the last working resolvers.
        if (servers.isNotEmpty()) commands.trySend(Command.RefreshDns(servers))
    }

    fun stop() = close(accept = { it.active || cancelSystemRestart != null }, Status.Stopped)

    /** Revocation stops only the tunnel owned by this service, and cannot turn a user's stop into a drop. */
    fun revoke(host: TunHost) = close(
        accept = { this.host === host && (it.active || cancelSystemRestart != null) },
        terminal = Status.Failed(FailureReason.Revoked),
    )

    /**
     * The service [host] is going away: stops the session only if [host] took it. A service that took none, such as one
     * a disconnect sent away, leaves alone a start requested since.
     */
    fun release(host: TunHost) = close(accept = { this.host === host && (it.active || cancelSystemRestart != null) }, Status.Stopped)

    /** The start [requested] never reached the service: ends it, unless the service took it or a newer command replaced it. */
    fun abandon(requested: Long) = close(accept = { requested == generation && host == null }, Status.Stopped)

    /**
     * The core cannot be opened: shows [reason] before any session, so the UI does not wait for it; a start retries. Once
     * a command took a generation, that session's own terminal status stands.
     */
    fun unavailable(reason: FailureReason) = synchronized(lock) {
        if (generation == 0L) show(generation, Status.Failed(reason))
    }

    /** Builds the session's tunnel again with the current per-app policy; a start not yet taken reads it anyway. */
    fun rebuild() = submit(Status.Starting, accept = { it.active && host != null }) { g -> Command.Open(g, checkNotNull(host)) }

    /** Only the observed generation may fail; two generations may have the same running timestamp. */
    fun coreStopped(observed: Session) = close(
        accept = { it is Status.Running && _session.value == observed },
        terminal = Status.Failed(FailureReason.CoreStopped),
        recover = true,
    )

    private fun close(accept: (Status) -> Boolean, terminal: Status, recover: Boolean = false) {
        var cancelRestart: (() -> Unit)? = null
        val g = submit(Status.Stopping, accept) { g ->
            if (!recover) {
                cancelRestart = cancelSystemRestart
                cancelSystemRestart = null
                recovery?.cancel()
                recovery = null
            } else if (cancelSystemRestart != null && clock() - runningSinceMs >= STABLE_SESSION_MS) {
                recoveryDelayMs = FIRST_RECOVERY_DELAY_MS
            }
            Command.Close(g, terminal, recover && cancelSystemRestart != null)
        } ?: return
        cancelRestart?.invoke()
        // A start that never returns holds the runner, so the stop's own watchdog may never be armed. The runner gets
        // the start's bound plus the stop deadline to reach this command; past that only ending the process frees the tun.
        scope.launch {
            delay(startBoundMs + stopDeadlineMs)
            // Under the lock, so the stop cannot publish its status between this check and the kill.
            synchronized(lock) {
                if (handled < g) {
                    VpnLog.action("stop", "deadline", "failed", ErrorCode.Internal.name, startBoundMs + stopDeadlineMs)
                    killProcess()
                }
            }
        }
    }

    private fun finishClose(command: Command.Close) {
        if (command.recover) {
            scheduleRecovery(command.generation, command.terminal)
        } else {
            host = null
            show(command.generation, command.terminal)
        }
    }

    /** A failed retry and a new core death both consume the next delay for this system session. */
    private fun scheduleRecovery(g: Long, failure: Status) {
        val wait = recoveryDelayMs
        recoveryDelayMs = (wait * 2).coerceAtMost(MAX_RECOVERY_DELAY_MS)
        recovery = scope.launch {
            delay(wait)
            synchronized(lock) {
                if (generation == g) retryNow()
            }
        }
        show(g, failure, recovering = true)
    }

    /** Called under lock, either by the timer or a Connect tap during its wait. */
    private fun retryNow() {
        recovery?.cancel()
        recovery = null
        enqueueStart(++generation, checkNotNull(host))
    }

    /** The new generation, or null when [accept] refused the current status. */
    private inline fun submit(next: Status, accept: (Status) -> Boolean, command: (Long) -> Command): Long? = synchronized(lock) {
        if (!accept(_status.value)) return null
        generation++
        show(generation, next)
        // Queued under the lock, so the queue order is the generation order.
        commands.trySend(command(generation))
        generation
    }

    private fun isCurrent(g: Long) = synchronized(lock) { g == generation }

    /** Publishes [status] only if [g] is still the latest generation. */
    private fun publish(g: Long, status: Status, startBlock: VpnStartBlock? = null): Boolean = synchronized(lock) {
        (g == generation).also { current ->
            if (current) {
                if (status is Status.Failed && startBlock == null && cancelSystemRestart != null &&
                    status.reason != FailureReason.IncompatibleCore && status.reason != FailureReason.NoAllowedApps &&
                    status.reason != FailureReason.LockdownEnabled && status.reason != FailureReason.VpnNotEstablished
                ) {
                    scheduleRecovery(g, status)
                } else {
                    if (status.ended) {
                        host = null
                        cancelSystemRestart = null
                    }
                    if (status is Status.Running) runningSinceMs = clock()
                    show(g, status, startBlock)
                }
            }
        }
    }

    /** The caller holds [lock]. */
    private fun show(g: Long, status: Status, startBlock: VpnStartBlock? = null, recovering: Boolean = false) {
        // A flow collector may miss a short-lived session or its terminal status before the next start.
        if (status.ended && !_status.value.ended) endDebugCapture()
        _status.value = status
        _session.value = Session(g, status, startBlock, recovering)
    }

    private suspend fun open(command: Command.Open) {
        val (g, host) = command
        val begun = System.nanoTime()
        val failure = try {
            val block = command.startBlock
            if (block != null) {
                VpnLog.action("start", "complete", "failed", block.name)
                publish(g, Status.Failed(FailureReason.StartFailed), block)
                return
            }
            startCore(g, host)
                .also { currentCoroutineContext().ensureActive() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Status.Failed(FailureReason.StartFailed)
        } catch (e: LinkageError) {
            Status.Failed(FailureReason.StartFailed)
        }
        val ms = (System.nanoTime() - begun) / 1_000_000
        when {
            failure != null -> {
                VpnLog.action("start", "complete", "failed", failure.code?.name ?: failure.reason.name, ms)
                publish(g, failure)
            }

            publish(g, Status.Running(System.currentTimeMillis())) -> VpnLog.action("start", "complete", "ok", "ok", ms)

            else -> {
                VpnLog.action("start", "complete", "superseded", "ok", ms)
                stopCore()
            }
        }
    }

    /** Null once the core runs the tunnel, or when a newer command superseded [g] before the tun was built. */
    private suspend fun startCore(g: Long, host: TunHost): Status.Failed? {
        if (host.lockdownEnabled) return Status.Failed(FailureReason.LockdownEnabled)
        val schema = opener.schema() ?: return Status.Failed(FailureReason.StartFailed)
        if (schema != CORE_SCHEMA) return Status.Failed(FailureReason.IncompatibleCore)
        val routing = AppRouting.resolve(perApp(), host.selfPackage, host::installed) ?: return Status.Failed(FailureReason.NoAllowedApps)
        val mtu = host.mtu
        expireDebugCapture()?.join()
        if (!isCurrent(g)) return null
        val servers = host.bootstrapDns().ifEmpty { pendingBootstrapDns ?: bootstrapDns }
        val stage = geoAssets?.prepare(::activeGeoRelease)
        return startWithTun(g, host, routing, mtu, servers, stage)
    }

    private fun activeGeoRelease(): String? {
        val args = CoreJson.encode(ConfigAssetsArgs.serializer(), ConfigAssetsArgs("status"))
        return CoreJson.result(core.call("configAssets", args), ConfigAssetsDto.serializer()).release
    }

    private fun startWithTun(g: Long, host: TunHost, routing: AppRouting, mtu: Int, servers: List<String>, stage: File?): Status.Failed? {
        if (!isCurrent(g)) return null
        val config = CoreJson.encode(StartConfigDto.serializer(), StartConfigDto.of(servers, mtu, stage?.path))
        val tun = host.establish(routing, mtu) ?: return Status.Failed(FailureReason.VpnNotEstablished)
        // The core duplicates the borrowed fd; ours is closed whether start returns or throws.
        tun.use {
            try {
                core.start(it.fd, config)
                coreRunning = true
            } catch (e: IllegalStateException) {
                // Only the core speaks in codes: it reports its own as the message of this exception. The log takes the
                // code it maps to, never the message itself, which the diagnostic report carries (ARCH D21).
                val code = errorCodeOf(e.message.orEmpty())
                return Status.Failed(FailureReason.StartFailed, code)
            }
        }
        // A timed-out start may still be reading the files. The next start reaps its stage.
        if (stage != null) geoAssets?.discard(stage)
        bootstrapDns = servers
        pendingBootstrapDns = null
        return null
    }

    private fun applyPendingBootstrapDns() {
        val servers = pendingBootstrapDns ?: return
        if (opener.openedSchema != CORE_SCHEMA) return
        pendingBootstrapDns = null
        if (servers != bootstrapDns) applyBootstrapDns(servers)
    }

    private fun applyBootstrapDns(servers: List<String>) {
        val name = BootstrapDnsArgs.COMMAND
        try {
            val args = CoreJson.encode(BootstrapDnsArgs.serializer(), BootstrapDnsArgs(servers))
            CoreJson.result(core.call(name, args), Unit.serializer())
            bootstrapDns = servers
        } catch (e: EngineException) {
            VpnLog.action(name, "complete", "failed", e.code.name)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            VpnLog.warn("$name failed: ${e.javaClass.simpleName}")
        } catch (e: LinkageError) {
            VpnLog.warn("$name failed: ${e.javaClass.simpleName}")
        }
    }

    private suspend fun stopCore() {
        if (!coreRunning) return
        coreRunning = false
        val begun = System.nanoTime()
        // A native call cannot be cancelled: if stop hangs, only ending the process frees the tun.
        val watchdog = scope.launch {
            delay(stopDeadlineMs)
            VpnLog.action("stop", "deadline", "failed", ErrorCode.Internal.name, stopDeadlineMs)
            killProcess()
        }
        val failure = try {
            core.stop()
            null
        } catch (e: Exception) {
            e
        } catch (e: LinkageError) {
            e
        } finally {
            watchdog.cancel()
        }
        if (failure != null) {
            VpnLog.action("stop", "complete", "failed", ErrorCode.Internal.name)
            // The core may still hold the duplicate fd: nothing may report the tunnel stopped until the process is gone.
            killProcess()
            awaitCancellation()
        }
        VpnLog.action("stop", "complete", "ok", "ok", (System.nanoTime() - begun) / 1_000_000)
    }

    private sealed interface Command {
        data class Open(val generation: Long, val host: TunHost, val startBlock: VpnStartBlock? = null) : Command

        data class Close(val generation: Long, val terminal: Status, val recover: Boolean) : Command

        data class RefreshDns(val servers: List<String>) : Command

        data object CoreOpened : Command
    }

    companion object {
        /** `start` returns within this, per the core contract. */
        const val START_BOUND_MS = 10_000L
        const val STOP_DEADLINE_MS = 5_000L
        private const val FIRST_RECOVERY_DELAY_MS = 1_000L
        private const val MAX_RECOVERY_DELAY_MS = 60_000L
        private const val STABLE_SESSION_MS = 60_000L
    }
}
