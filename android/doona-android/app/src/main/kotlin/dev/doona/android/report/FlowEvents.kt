package dev.doona.android.report

import android.net.ConnectivityManager
import android.net.InetAddresses
import android.os.Process
import android.system.OsConstants
import dev.doona.android.data.settings.LogLevelController
import dev.doona.android.engine.Engine
import dev.doona.android.engine.RecentFlow
import dev.doona.android.engine.Status
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress

internal const val TCP_FLOW_EVENT = "flow tcp"
internal const val UDP_FLOW_EVENT = "flow udp"

/** Serializes capture and export reads so a connection only enters the report ring once. */
class FlowEvents internal constructor(
    private val engine: Engine,
    private val io: CoroutineDispatcher,
    private val ownerUid: (protocol: Int, local: String, remote: String) -> Int,
    private val packagesForUid: (Int) -> Array<String>?,
) {
    private data class Key(val protocol: String, val local: String, val remote: String, val startedMs: Long)

    private val reading = Mutex()
    private val seen = HashSet<Key>()
    private var session: Status.Running? = null

    internal suspend fun followCapture(levels: LogLevelController) {
        combine(engine.status, levels.debugDeadlineMs, ::Pair).collectLatest { (status, deadline) ->
            if (status !is Status.Running || deadline == null) return@collectLatest
            val active = { levels.debugDeadlineMs.value == deadline && levels.remainingMs() > 0 }
            while (active() && engine.status.value === status) {
                recordRecent(status, active)
                delay(2_000L)
            }
        }
    }

    internal suspend fun recordRecent() {
        val running = engine.status.value as? Status.Running ?: return
        recordRecent(running) { true }
    }

    private suspend fun recordRecent(running: Status.Running, active: () -> Boolean) = withContext(io) {
        reading.withLock {
            if (engine.status.value !== running || !active()) return@withLock
            val flows = engine.recentFlows().getOrNull() ?: return@withLock
            if (session !== running) {
                seen.clear()
                session = running
            }
            val present = HashSet<Key>(flows.size)
            for (flow in flows.asReversed()) {
                if (engine.status.value !== running || !active()) return@withLock
                val key = Key(flow.protocol, flow.local, flow.remote, flow.startedMs)
                if (!present.add(key) || key in seen) continue
                val text = when (flow.protocol) {
                    "tcp" -> TCP_FLOW_EVENT
                    "udp" -> UDP_FLOW_EVENT
                    else -> continue
                }
                val owner = if (flow.open) ownerPackage(flow) else null
                currentCoroutineContext().ensureActive()
                if (engine.status.value !== running || !active()) return@withLock
                AppEvents.record(AppEventLevel.Info, text, visitedDomain = flow.name, appPackage = owner)
                seen.add(key)
            }
            // An entry that left this session's core ring cannot return.
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
}

internal fun ConnectivityManager.connectionOwnerUid(protocol: Int, local: String, remote: String): Int = try {
    getConnectionOwnerUid(protocol, numericSocketAddress(local), numericSocketAddress(remote))
} catch (_: IllegalArgumentException) {
    Process.INVALID_UID
}

private fun numericSocketAddress(address: String): InetSocketAddress = InetSocketAddress(
    InetAddresses.parseNumericAddress(address.substringBeforeLast(':').removeSurrounding("[", "]")),
    address.substringAfterLast(':').toInt(),
)
