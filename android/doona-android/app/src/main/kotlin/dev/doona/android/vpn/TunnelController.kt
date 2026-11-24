package dev.doona.android.vpn

import dev.doona.android.engine.ErrorCode
import dev.doona.android.engine.FailureReason
import dev.doona.android.engine.PerApp
import dev.doona.android.engine.Status
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Starting or running: a session the user has not ended. */
internal val Status.active: Boolean get() = this is Status.Starting || this is Status.Running

/** Stopped or failed: no session, and nothing left to stop. */
internal val Status.ended: Boolean get() = this is Status.Stopped || this is Status.Failed

/** A status and the generation that set it: a later session that repeats a status is still a new value. */
internal data class Session(val generation: Long, val status: Status)

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
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val _status = MutableStateFlow<Status>(Status.Stopped)
    val status: StateFlow<Status> = _status.asStateFlow()
    private val _session = MutableStateFlow(Session(0, Status.Stopped))

    /** [status] with its generation, for following one session to its end. */
    val session: StateFlow<Session> = _session.asStateFlow()

    private val lock = Any()
    private var generation = 0L // Guarded by lock.
    private var host: TunHost? = null // Guarded by lock; set while a session is in progress.
    private val commands = Channel<Command>(Channel.UNLIMITED)

    private var coreRunning = false // Touched only by run().

    private var handled = 0L // Guarded by lock; written only by run(): the generation of the last command it finished.

    /** Executes commands in order until cancelled. The owner launches it once, off the main thread. */
    suspend fun run() {
        for (command in commands) {
            stopCore()
            if (command is Command.Open && isCurrent(command.generation)) open(command.generation, command.host)
            // A close's status and its completion are recorded together, so its watchdog never sees one without the other.
            synchronized(lock) {
                if (command is Command.Close) publish(command.generation, command.terminal)
                handled = command.generation
            }
        }
    }

    /**
     * Shows [Status.Starting] at once, before the service exists, so a second tap or a stop sees the start; null while a
     * session is starting or running. The service's [start] must carry the returned generation.
     */
    fun requestStart(): Long? = synchronized(lock) {
        if (_status.value.active) return null
        generation++
        host = null
        show(generation, Status.Starting)
        generation
    }

    /** Builds the tunnel [requested]; false when a stop or another command replaced the request before it arrived. */
    fun start(host: TunHost, requested: Long): Boolean = synchronized(lock) {
        if (requested != generation || _status.value != Status.Starting || this.host != null) return false
        this.host = host
        commands.trySend(Command.Open(requested, host))
        true
    }

    fun stop() = close(accept = { it.active }, Status.Stopped)

    /** Revocation stops only the tunnel owned by this service, and cannot turn a user's stop into a drop. */
    fun revoke(host: TunHost) = close(accept = { it.active && this.host === host }, Status.Failed(FailureReason.Revoked))

    /**
     * The service [host] is going away: stops the session only if [host] took it. A service that took none, such as one
     * a disconnect sent away, leaves alone a start requested since.
     */
    fun release(host: TunHost) = close(accept = { it.active && this.host === host }, Status.Stopped)

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

    /** The health check found [observed]'s data path dead; ignored once the status has moved on. */
    fun coreStopped(observed: Status.Running) = close(accept = { it == observed }, Status.Failed(FailureReason.CoreStopped))

    private fun close(accept: (Status) -> Boolean, terminal: Status) {
        val g = submit(Status.Stopping, accept) { g -> Command.Close(g, terminal) } ?: return
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
    private fun publish(g: Long, status: Status): Boolean = synchronized(lock) {
        (g == generation).also { current ->
            if (current) {
                show(g, status)
                if (!status.active) host = null
            }
        }
    }

    /** The caller holds [lock]. */
    private fun show(g: Long, status: Status) {
        // A flow collector may miss a short-lived session or its terminal status before the next start.
        if (status.ended && !_status.value.ended) endDebugCapture()
        _status.value = status
        _session.value = Session(g, status)
    }

    private suspend fun open(g: Long, host: TunHost) {
        val begun = System.nanoTime()
        val failure = try {
            startCore(g, host)
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

            publish(g, Status.Running(clock())) -> VpnLog.action("start", "complete", "ok", "ok", ms)

            else -> {
                VpnLog.action("start", "complete", "superseded", "ok", ms)
                stopCore()
            }
        }
    }

    /** Null once the core runs the tunnel, or when a newer command superseded [g] before the tun was built. */
    private suspend fun startCore(g: Long, host: TunHost): Status.Failed? {
        val schema = opener.schema() ?: return Status.Failed(FailureReason.StartFailed)
        if (schema != CORE_SCHEMA) return Status.Failed(FailureReason.IncompatibleCore)
        val routing = AppRouting.resolve(perApp(), host.selfPackage, host::installed) ?: return Status.Failed(FailureReason.NoAllowedApps)
        val mtu = host.mtu
        val config = CoreJson.encode(StartConfigDto.serializer(), StartConfigDto.of(host.bootstrapDns(), mtu))
        expireDebugCapture()?.join()
        if (!isCurrent(g)) return null
        val tun = host.establish(routing, mtu) ?: return Status.Failed(FailureReason.StartFailed)
        // The core duplicates the borrowed fd; ours is closed whether start returns or throws.
        tun.use {
            try {
                core.start(it.fd, config)
            } catch (e: IllegalStateException) {
                // Only the core speaks in codes: it reports its own as the message of this exception. The log takes the
                // code it maps to, never the message itself, which the diagnostic report carries (ARCH D21).
                val code = errorCodeOf(e.message.orEmpty())
                return Status.Failed(FailureReason.StartFailed, code)
            }
        }
        coreRunning = true
        return null
    }

    private suspend fun stopCore() {
        if (!coreRunning) return
        coreRunning = false
        expireDebugCapture()
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
        val generation: Long

        data class Open(override val generation: Long, val host: TunHost) : Command

        data class Close(override val generation: Long, val terminal: Status) : Command
    }

    companion object {
        /** `start` returns within this, per the core contract. */
        const val START_BOUND_MS = 10_000L
        const val STOP_DEADLINE_MS = 5_000L
    }
}
