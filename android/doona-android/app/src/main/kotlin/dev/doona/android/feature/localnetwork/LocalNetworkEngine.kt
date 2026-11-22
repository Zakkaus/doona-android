package dev.doona.android.feature.localnetwork

import dev.doona.android.engine.Engine
import dev.doona.android.engine.EngineException
import dev.doona.android.engine.ErrorCode
import dev.doona.android.engine.Subscription
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The [Engine] the screens use: the same engine, with the local network permission asked for where an action of the
 * user's failed with [ErrorCode.LocalNetwork] (ARCH D26). Adding or updating a subscription asks and, once allowed,
 * runs once more; a latency test, whose results arrive after it returns, asks when it ends with a node that failed on the
 * local network, and runs once more. Every other call passes straight through. [scope] outlives the screens.
 */
class LocalNetworkEngine(
    private val engine: Engine,
    private val access: LocalNetworkAccess,
    private val scope: CoroutineScope,
) : Engine by engine {
    private var probes: Job? = null

    /** Asks when the running tunnel reports a failed connection through a node on the local network. Call once. */
    fun watch(): Job = scope.launch {
        engine.warning.collect { if (it == ErrorCode.LocalNetwork) access.noticed() }
    }

    override suspend fun addSubscription(url: String, name: String?): Result<Subscription> =
        retried { engine.addSubscription(url, name) }

    override suspend fun updateSubscription(id: String): Result<Subscription> = retried { engine.updateSubscription(id) }

    override suspend fun testLatency(groupId: String?): Result<Unit> {
        val started = engine.testLatency(groupId)
        if (started.isSuccess) followProbes(groupId)
        return started
    }

    private suspend fun <T> retried(action: suspend () -> Result<T>): Result<T> {
        val first = action()
        val blocked = (first.exceptionOrNull() as? EngineException)?.code == ErrorCode.LocalNetwork
        return if (blocked && access.ask()) action() else first
    }

    /** One follow-up at a time: taps during a test share the one that waits for it. */
    private fun followProbes(groupId: String?) {
        synchronized(this) {
            if (probes?.isActive == true) return
            probes = scope.launch {
                val ended = engine.groups.first { groups -> groups.none { it.testing } }
                val blocked = ended.any { group -> group.members.any { it.error == ErrorCode.LocalNetwork } }
                if (blocked && access.ask()) engine.testLatency(groupId)
            }
        }
    }
}
