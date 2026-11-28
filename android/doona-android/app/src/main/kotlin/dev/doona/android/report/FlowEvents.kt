package dev.doona.android.report

import android.net.InetAddresses
import android.os.Process
import android.system.OsConstants
import dev.doona.android.data.settings.LogLevelController
import dev.doona.android.honk.NativeApi
import dev.doona.android.honk.epochMs
import dev.doona.android.honk.read
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.net.InetSocketAddress

internal const val TCP_FLOW_EVENT = "flow tcp"
internal const val UDP_FLOW_EVENT = "flow udp"

/**
 * Keeps the last 128 flow events, separately from the app's diagnostic actions and warnings: each flow honk recorded
 * (`GET /api/v1/flows`, with its addresses and domain) once, with the app that owns it while it is still open. A read
 * starts honk's flow recording for a minute, so the first one after a quiet spell finds few flows. honk names each
 * run by its instance; a new one starts the dedupe again. Without honk (the VPN off) a read finds nothing.
 */
class FlowEvents internal constructor(
    private val api: NativeApi,
    private val io: CoroutineDispatcher,
    private val ownerUid: (protocol: Int, local: String, remote: String) -> Int,
    private val packagesForUid: (Int) -> Array<String>?,
) {
    private val reading = Mutex()
    private val events = ArrayDeque<AppEvent>(CAPACITY)
    private val seen = HashSet<String>()
    private var instance: String? = null

    /** Reads honk's flows every 2 s while a debug capture runs. */
    internal suspend fun followCapture(levels: LogLevelController) {
        levels.debugDeadlineMs.collectLatest { deadline ->
            if (deadline == null) return@collectLatest
            while (levels.remainingMs() > 0) {
                recordRecent()
                delay(2_000L)
            }
        }
    }

    internal suspend fun snapshot(): List<AppEvent> = reading.withLock { events.toList() }

    internal suspend fun recordRecent() = withContext(io) {
        reading.withLock {
            val list = try {
                api.read("GET", FLOWS, HonkFlows.serializer())
            } catch (_: Exception) {
                return@withLock
            }
            if (list.instanceId != instance) {
                seen.clear()
                instance = list.instanceId
            }
            val present = HashSet<String>(list.flows.size)
            for (flow in list.flows.sortedBy { it.startedAt }) {
                if (!present.add(flow.id) || flow.id in seen) continue
                val started = flow.startedAt?.let(::epochMs) ?: continue
                val text = when (flow.network) {
                    "tcp" -> TCP_FLOW_EVENT
                    "udp" -> UDP_FLOW_EVENT
                    else -> continue
                }
                val owner = if (flow.state == "active") ownerPackage(flow) else null
                if (events.size == CAPACITY) events.removeFirst()
                events.addLast(AppEvent(started, AppEventLevel.Info, text, flow.input?.domain, owner))
                seen.add(flow.id)
            }
            // Keep dedupe bounded to the flows honk still holds.
            seen.retainAll(present)
        }
    }

    private fun ownerPackage(flow: HonkFlow): String? = try {
        val protocol = if (flow.network == "tcp") OsConstants.IPPROTO_TCP else OsConstants.IPPROTO_UDP
        val local = flow.input?.src ?: return null
        val remote = flow.input.dst ?: return null
        val uid = ownerUid(protocol, local, remote)
        if (uid == Process.INVALID_UID) null else packagesForUid(uid)?.singleOrNull()
    } catch (_: SecurityException) {
        null
    }

    private companion object {
        const val CAPACITY = 128
        const val FLOWS = "/api/v1/flows?detail=full&limit=$CAPACITY"
    }
}

/** honk's `FlowList`, the part the report reads. */
@Serializable
internal class HonkFlows(@SerialName("instance_id") val instanceId: String, val flows: List<HonkFlow>)

/** honk's `FlowSummary` with `detail=full`: [network] `tcp` or `udp`, [state] such as `active` or `closed`. */
@Serializable
internal class HonkFlow(
    val id: String,
    val network: String,
    val state: String,
    @SerialName("started_at") val startedAt: String? = null,
    val input: HonkFlowInput? = null,
)

/** honk's `FlowInput`: numeric `address:port` endpoints, and the domain honk saw (null when it saw none). */
@Serializable
internal class HonkFlowInput(val src: String?, val dst: String?, val domain: String?)

internal fun connectionOwnerUid(
    protocol: Int,
    local: String,
    remote: String,
    lookup: (Int, InetSocketAddress, InetSocketAddress) -> Int,
): Int {
    // honk sends numeric "address:port"; an endpoint that does not parse has no owner to look up.
    val localAddress = numericSocketAddress(local) ?: return Process.INVALID_UID
    val remoteAddress = numericSocketAddress(remote) ?: return Process.INVALID_UID
    return lookup(protocol, localAddress, remoteAddress)
}

private fun numericSocketAddress(address: String): InetSocketAddress? = try {
    InetSocketAddress(InetAddresses.parseNumericAddress(address.substringBeforeLast(':')), address.substringAfterLast(':').toInt())
} catch (_: IllegalArgumentException) {
    null
}
