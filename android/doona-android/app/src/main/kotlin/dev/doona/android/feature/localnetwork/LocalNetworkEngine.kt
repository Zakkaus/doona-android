package dev.doona.android.feature.localnetwork

import dev.doona.android.engine.Engine
import dev.doona.android.engine.EngineException
import dev.doona.android.engine.ErrorCode
import dev.doona.android.engine.Subscription
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The [Engine] the screens use: the same engine, with the local network permission asked for where an action of the
 * user's failed with [ErrorCode.LocalNetwork] (ARCH D26). Adding or updating a subscription asks and, once allowed,
 * runs once more; refused, it waits on the refusal and runs once more if the user goes from it to the app's settings
 * and comes back with access allowed. A latency test, whose results arrive after it returns, asks when it ends with a node that failed on the
 * local network, and tests the groups that failed once more. Every other call passes straight through. [scope] outlives
 * the screens, and the asking and the retry run in it, so they go on after the page that started them is left.
 */
class LocalNetworkEngine(
    private val engine: Engine,
    private val access: LocalNetworkAccess,
    private val scope: CoroutineScope,
) : Engine by engine {
    private val lock = Any()
    private var following = false

    /** The tests started while the follower waits: a group's id, or null for all of them. */
    private val requested = LinkedHashSet<String?>()

    /**
     * Asks when the running tunnel reports a failed connection through a node on the local network, and withdraws the
     * question when the report clears before the user answered. Call once.
     */
    fun watch(): Job = scope.launch {
        engine.warning.collectLatest { if (it == ErrorCode.LocalNetwork) access.noticed() }
    }

    override suspend fun addSubscription(url: String, name: String?, userAgent: String?): Result<Subscription> =
        retried { engine.addSubscription(url, name, userAgent) }

    override suspend fun updateSubscription(id: String): Result<Subscription> = retried { engine.updateSubscription(id) }

    override suspend fun testLatency(groupId: String?): Result<Unit> {
        val started = engine.testLatency(groupId)
        if (started.isSuccess) followProbes(groupId)
        return started
    }

    private suspend fun <T> retried(action: suspend () -> Result<T>): Result<T> = scope.async {
        val first = action()
        val blocked = (first.exceptionOrNull() as? EngineException)?.code == ErrorCode.LocalNetwork
        if (blocked && (access.ask() || access.allowedInSettings())) action() else first
    }.await()

    /** One follower at a time: tests started while it waits add their groups to the ones it may test again. */
    private fun followProbes(groupId: String?) {
        val start = synchronized(lock) {
            requested += groupId
            !following.also { following = true }
        }
        if (start) scope.launch { follow() }
    }

    /** Waits for every test to end, then asks and tests again the requested groups that failed on the local network. */
    private suspend fun follow() {
        val ended = engine.groups.first { groups -> groups.none { it.testing } }
        val scopes = synchronized(lock) {
            following = false
            requested.toList().also { requested.clear() }
        }
        val blocked = ended.filter { group -> group.members.any { it.error == ErrorCode.LocalNetwork } }.map { it.id }.toSet()
        val again = scopes.filter { if (it == null) blocked.isNotEmpty() else it in blocked }
        if (again.isEmpty() || !access.ask()) return
        if (null in again) engine.testLatency(null) else again.forEach { engine.testLatency(it) }
    }
}
