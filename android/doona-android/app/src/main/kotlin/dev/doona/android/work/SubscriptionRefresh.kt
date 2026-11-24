package dev.doona.android.work

import dev.doona.android.data.settings.SubscriptionUpdateSettings
import dev.doona.android.engine.Engine
import dev.doona.android.engine.EngineException
import dev.doona.android.engine.ErrorCode
import dev.doona.android.engine.Status
import dev.doona.android.vpn.VpnLog
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import kotlin.time.Duration.Companion.seconds

internal class SubscriptionRefresh(
    private val settings: SubscriptionUpdateSettings,
    private val engine: () -> Engine,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val refreshing = Mutex()

    enum class Outcome { Completed, Skipped, Failed }

    suspend fun watchConnections() {
        settings.subscriptionUpdate
            .map { it.enabled }
            .distinctUntilChanged()
            .catch {
                if (it !is IOException) throw it
                VpnLog.warn("subscription-refresh: settings read failed (${it.javaClass.simpleName})")
            }.collectLatest { enabled ->
                if (enabled) engine().status.filterIsInstance<Status.Running>().collect { run() }
            }
    }

    suspend fun run(): Outcome {
        if (!refreshing.tryLock()) return Outcome.Skipped
        try {
            if (!settings.subscriptionUpdate.first().enabled) return Outcome.Skipped
            val engine = engine()
            if (withTimeoutOrNull(15.seconds) { engine.ready.first { it } } == null) return Outcome.Failed
            var outcome = Outcome.Completed
            for (sub in engine.subscriptions.value) {
                // Disabling auto-update during a fetch must stop the rest of the batch.
                val (enabled, interval) = settings.subscriptionUpdate.first()
                if (!enabled) break
                val lastUpdate = sub.updatedAtEpochMs
                if (lastUpdate == null || lastUpdate <= clock() - interval.duration.inWholeMilliseconds) {
                    currentCoroutineContext().ensureActive()
                    // A native command outlives its caller; keep the batch locked until it returns.
                    val updated = withContext(NonCancellable) { engine.updateSubscription(sub.id) }
                    updated.exceptionOrNull()?.let { failure ->
                        val code = (failure as? EngineException)?.code ?: ErrorCode.Internal
                        VpnLog.warn("subscription-refresh: update failed ($code)")
                        outcome = Outcome.Failed
                    }
                }
            }
            return outcome
        } catch (e: IOException) {
            VpnLog.warn("subscription-refresh: settings read failed (${e.javaClass.simpleName})")
            return Outcome.Failed
        } finally {
            refreshing.unlock()
        }
    }
}
