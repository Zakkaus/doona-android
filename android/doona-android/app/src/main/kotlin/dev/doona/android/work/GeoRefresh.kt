package dev.doona.android.work

import dev.doona.android.data.settings.AppSettings
import dev.doona.android.data.settings.GeoUpdateSchedule
import dev.doona.android.engine.Engine
import dev.doona.android.engine.EngineException
import dev.doona.android.engine.ErrorCode
import dev.doona.android.engine.Status
import dev.doona.android.vpn.VpnLog
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.io.IOException
import kotlin.time.Duration.Companion.seconds

internal class GeoRefresh(
    private val settings: AppSettings,
    private val clock: () -> Long = System::currentTimeMillis,
    private val engine: () -> Engine,
) {
    private val refreshing = Mutex()

    enum class Outcome { Completed, Skipped, Failed }

    suspend fun watchConnections(enqueue: suspend () -> Unit) {
        engine().status.filterIsInstance<Status.Running>().collect {
            val schedule = readSchedule() ?: return@collect
            if (schedule.due(clock())) enqueue()
        }
    }

    suspend fun run(): Outcome {
        if (!refreshing.tryLock()) return Outcome.Skipped
        try {
            val schedule = readSchedule() ?: return Outcome.Failed
            if (!schedule.due(clock())) return Outcome.Skipped
            val engine = engine()
            if (engine.status.value !is Status.Running) return Outcome.Skipped
            currentCoroutineContext().ensureActive()
            // Native commands can outlive the caller. Do not release the lock while dispatch is in flight.
            val accepted = withContext(NonCancellable) { engine.updateConfigAssets() }
            accepted.exceptionOrNull()?.let { return failed(it) }
            return awaitResult(engine)
        } finally {
            refreshing.unlock()
        }
    }

    private suspend fun readSchedule(): GeoUpdateSchedule? = try {
        settings.geoUpdateSchedule.first()
    } catch (e: IOException) {
        VpnLog.warn("geo-refresh: settings read failed (${e.javaClass.simpleName})")
        null
    }

    // Periodic work runs anywhere inside each period, so a run can come a little before a full interval has passed.
    // Half an interval is due enough; with the full interval an always-on session would refresh every other period.
    private fun GeoUpdateSchedule.due(now: Long): Boolean {
        val intervalMs = interval.duration?.inWholeMilliseconds ?: return false
        return lastSuccessEpochMs == null || lastSuccessEpochMs < now - intervalMs / 2
    }

    private suspend fun awaitResult(engine: Engine): Outcome {
        // An accepted update is still checking or downloading in the core.
        while (true) {
            delay(1.seconds)
            val status = engine.configAssets().getOrElse { return failed(it) }
            when (status.lastResult) {
                "up_to_date", "ready_on_next_start", "activated" -> return recordSuccess()
                "checking", "downloading", "verifying" -> Unit
                else -> return failed(EngineException(ErrorCode.FetchFailed))
            }
        }
    }

    private suspend fun recordSuccess(): Outcome = try {
        settings.recordGeoUpdateSuccess(clock())
        Outcome.Completed
    } catch (e: IOException) {
        VpnLog.warn("geo-refresh: settings write failed (${e.javaClass.simpleName})")
        Outcome.Failed
    }

    private fun failed(failure: Throwable): Outcome {
        val code = (failure as? EngineException)?.code ?: ErrorCode.Internal
        if (code == ErrorCode.Busy) return Outcome.Skipped
        VpnLog.warn("geo-refresh: update failed ($code)")
        return Outcome.Failed
    }
}
