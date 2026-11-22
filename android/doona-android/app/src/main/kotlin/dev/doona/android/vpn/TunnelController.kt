package dev.doona.android.vpn

import dev.doona.android.engine.FailureReason
import dev.doona.android.engine.PerApp
import dev.doona.android.engine.Status
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
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
    /** Runs the stop watchdog; must not share a thread with [run]. */
    private val scope: CoroutineScope,
    private val killProcess: () -> Unit,
    private val stopDeadlineMs: Long = STOP_DEADLINE_MS,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val _status = MutableStateFlow<Status>(Status.Stopped)
    val status: StateFlow<Status> = _status.asStateFlow()

    private val lock = Any()
    private var generation = 0L // Guarded by lock.
    private var host: TunHost? = null // Guarded by lock; set while a session is in progress.
    private val commands = Channel<Command>(Channel.UNLIMITED)

    private var coreRunning = false // Touched only by run().

    /** Executes commands in order until cancelled. The owner launches it once, off the main thread. */
    suspend fun run() {
        for (command in commands) {
            stopCore()
            when (command) {
                is Command.Open -> if (isCurrent(command.generation)) open(command.generation, command.host)
                is Command.Close -> publish(command.generation, command.terminal)
            }
        }
    }

    /** Ignored while a session is starting or running. */
    fun start(host: TunHost) = submit(Status.Starting, accept = { !it.active }) { g ->
        this.host = host
        Command.Open(g, host)
    }

    /** The user's stop and the system's revoke alike. */
    fun stop() = submit(Status.Stopping, accept = { it.active }) { g -> Command.Close(g, Status.Stopped) }

    /** Builds the session's tunnel again with the current per-app policy. */
    fun rebuild() = submit(Status.Starting, accept = { it.active }) { g -> Command.Open(g, checkNotNull(host)) }

    /** The health check found [observed]'s data path dead; ignored once the status has moved on. */
    fun coreStopped(observed: Status.Running) = submit(Status.Stopping, accept = { it == observed }) { g ->
        Command.Close(g, Status.Failed(FailureReason.CoreStopped))
    }

    private inline fun submit(next: Status, accept: (Status) -> Boolean, command: (Long) -> Command) {
        synchronized(lock) {
            if (!accept(_status.value)) return
            generation++
            _status.value = next
            // Queued under the lock, so the queue order is the generation order.
            commands.trySend(command(generation))
        }
    }

    private fun isCurrent(g: Long) = synchronized(lock) { g == generation }

    /** Publishes [status] only if [g] is still the latest generation. */
    private fun publish(g: Long, status: Status): Boolean = synchronized(lock) {
        (g == generation).also { current ->
            if (current) {
                _status.value = status
                if (!status.active) host = null
            }
        }
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

    private fun stopCore() {
        if (!coreRunning) return
        coreRunning = false
        val begun = System.nanoTime()
        // A native call cannot be cancelled: if stop hangs, only ending the process frees the tun.
        val watchdog = scope.launch {
            delay(stopDeadlineMs)
            VpnLog.warn("stop exceeded $stopDeadlineMs ms; ending the process")
            killProcess()
        }
        try {
            core.stop()
        } catch (e: Exception) {
            VpnLog.warn("stop threw ${e.javaClass.simpleName}")
        } finally {
            watchdog.cancel()
        }
        VpnLog.info("stop after ${(System.nanoTime() - begun) / 1_000_000} ms")
    }

    private sealed interface Command {
        data class Open(val generation: Long, val host: TunHost) : Command

        data class Close(val generation: Long, val terminal: Status) : Command
    }

    companion object {
        const val STOP_DEADLINE_MS = 5_000L
    }
}
