package dev.doona.android.report

import android.net.InetAddresses
import android.os.Process
import android.system.OsConstants
import dev.doona.android.data.settings.LogLevelController
import dev.doona.android.engine.Engine
import dev.doona.android.engine.RecentFlow
import dev.doona.android.engine.Status
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress

internal const val TCP_FLOW_EVENT = "flow tcp"
internal const val UDP_FLOW_EVENT = "flow udp"

/** Keeps the last 128 flow events, separately from the app's diagnostic actions and warnings. */
class FlowEvents internal constructor(
    private val engine: Engine,
    private val io: CoroutineDispatcher,
    private val ownerUid: (protocol: Int, local: String, remote: String) -> Int,
    private val packagesForUid: (Int) -> Array<String>?,
) {
    private data class Key(val protocol: String, val local: String, val remote: String, val startedMs: Long)

    private val reading = Mutex()
    private val events = ArrayDeque<AppEvent>(CAPACITY)
    private val seen = HashSet<Key>()
    private var session: Status.Running? = null

    internal suspend fun followCapture(levels: LogLevelController) {
        combine(engine.status, levels.debugDeadlineMs, ::Pair).collectLatest { (status, deadline) ->
            if (status !is Status.Running || deadline == null) return@collectLatest
            while (levels.remainingMs() > 0) {
                recordRecent(status)
                delay(2_000L)
            }
        }
    }

    internal suspend fun recordRecent() {
        val running = engine.status.value as? Status.Running ?: return
        recordRecent(running)
    }

    internal suspend fun snapshot(): List<AppEvent> = reading.withLock { events.toList() }

    private suspend fun recordRecent(running: Status.Running) = withContext(io) {
        reading.withLock {
            val flows = engine.recentFlows().getOrNull() ?: return@withLock
            if (session != running) {
                seen.clear()
                session = running
            }
            val present = HashSet<Key>(flows.size)
            for (flow in flows.asReversed()) {
                val key = Key(flow.protocol, flow.local, flow.remote, flow.startedMs)
                if (!present.add(key) || key in seen) continue
                val text = when (flow.protocol) {
                    "tcp" -> TCP_FLOW_EVENT
                    "udp" -> UDP_FLOW_EVENT
                    else -> continue
                }
                val owner = if (flow.open) ownerPackage(flow) else null
                if (events.size == CAPACITY) events.removeFirst()
                events.addLast(AppEvent(System.currentTimeMillis(), AppEventLevel.Info, text, flow.name, owner))
                seen.add(key)
            }
            // Keep dedupe bounded to keys still in the core ring.
            seen.retainAll(present)
        }
    }

    private fun ownerPackage(flow: RecentFlow): String? = try {
        val protocol = if (flow.protocol == "tcp") OsConstants.IPPROTO_TCP else OsConstants.IPPROTO_UDP
        val uid = ownerUid(protocol, flow.local, flow.remote)
        if (uid == Process.INVALID_UID) null else packagesForUid(uid)?.minOrNull()
    } catch (_: SecurityException) {
        null
    }

    private companion object {
        const val CAPACITY = 128
    }
}

internal fun connectionOwnerUid(
    protocol: Int,
    local: String,
    remote: String,
    lookup: (Int, InetSocketAddress, InetSocketAddress) -> Int,
): Int {
    // The core sends numeric "address:port"; an endpoint that does not parse has no owner to look up.
    val localAddress = numericSocketAddress(local) ?: return Process.INVALID_UID
    val remoteAddress = numericSocketAddress(remote) ?: return Process.INVALID_UID
    return lookup(protocol, localAddress, remoteAddress)
}

private fun numericSocketAddress(address: String): InetSocketAddress? = try {
    InetSocketAddress(InetAddresses.parseNumericAddress(address.substringBeforeLast(':')), address.substringAfterLast(':').toInt())
} catch (_: IllegalArgumentException) {
    null
}
