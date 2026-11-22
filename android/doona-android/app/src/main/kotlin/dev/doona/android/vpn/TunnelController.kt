package dev.doona.android.vpn

import dev.doona.android.engine.FailureReason
import dev.doona.android.engine.PerApp
import dev.doona.android.engine.Status
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
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

    @Volatile
    private var handled = 0L // Written only by run(): the generation of the last command it finished.

    /** Executes commands in order until cancelled. The owner launches it once, off the main thread. */
    suspend fun run() {
        for (command in commands) {
            stopCore()
            when (command) {
                is Command.Open -> if (isCurrent(command.generation)) open(command.generation, command.host)
                is Command.Close -> publish(command.generation, command.terminal)
            }
            handled = command.generation
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

    /** The user's stop and the system's revoke alike. */
    fun stop() = close(accept = { it.active }, Status.Stopped)

    /** The core cannot be opened: shows [reason] while nothing runs, so the UI does not wait for it; a start retries. */
    fun unavailable(reason: FailureReason) = synchronized(lock) {
        if (_status.value == Status.Stopped) show(generation, Status.Failed(reason))
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
            if (handled < g) {
                VpnLog.warn("stop $g not reached in ${startBoundMs + stopDeadlineMs} ms; ending the process")
                killProcess()
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
            VpnLog.warn("start $g threw ${e.javaClass.simpleName}")
            FailureReason.StartFailed
        } catch (e: LinkageError) {
            VpnLog.warn("start $g threw ${e.javaClass.simpleName}")
            FailureReason.StartFailed
        }
        val ms = (System.nanoTime() - begun) / 1_000_000
        when {
            failure != null -> {
                VpnLog.warn("start $g failed: $failure after $ms ms")
                publish(g, Status.Failed(failure))
            }

            publish(g, Status.Running(clock())) -> VpnLog.info("start $g up after $ms ms")

            else -> {
                VpnLog.info("start $g superseded after $ms ms")
                stopCore()
            }
        }
    }

    /** Null once the core runs the tunnel, or when a newer command superseded [g] before the tun was built. */
    private suspend fun startCore(g: Long, host: TunHost): FailureReason? {
        val schema = opener.schema() ?: return FailureReason.StartFailed
        if (schema != CORE_SCHEMA) return FailureReason.IncompatibleCore
        val routing = AppRouting.resolve(perApp(), host.selfPackage, host::installed) ?: return FailureReason.NoAllowedApps
        val config = CoreJson.encode(StartConfigDto.serializer(), StartConfigDto.of(host.bootstrapDns()))
        if (!isCurrent(g)) return null
        val tun = host.establish(routing) ?: return FailureReason.StartFailed
        // The core duplicates the borrowed fd; ours is closed whether start returns or throws.
        tun.use { core.start(it.fd, config) }
        coreRunning = true
        return null
    }

    private suspend fun stopCore() {
        if (!coreRunning) return
        coreRunning = false
        val begun = System.nanoTime()
        // A native call cannot be cancelled: if stop hangs, only ending the process frees the tun.
        val watchdog = scope.launch {
            delay(stopDeadlineMs)
            VpnLog.warn("stop exceeded $stopDeadlineMs ms; ending the process")
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
            // The core may still hold the duplicate fd: nothing may report the tunnel stopped until the process is gone.
            VpnLog.warn("stop threw ${failure.javaClass.simpleName}; ending the process")
            killProcess()
            awaitCancellation()
        }
        VpnLog.info("stop after ${(System.nanoTime() - begun) / 1_000_000} ms")
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
