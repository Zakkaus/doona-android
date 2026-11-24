package dev.doona.android.work

import dev.doona.android.data.settings.GeoUpdateInterval
import dev.doona.android.engine.Engine
import dev.doona.android.engine.EngineException
import dev.doona.android.engine.ErrorCode
import dev.doona.android.engine.Status
import dev.doona.android.vpn.VpnLog
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import java.io.IOException

internal class GeoRefresh(private val interval: Flow<GeoUpdateInterval>, private val engine: () -> Engine) {
    enum class Outcome { Completed, Skipped, Failed }

    suspend fun run(): Outcome {
        val setting = try {
            interval.first()
        } catch (e: IOException) {
            VpnLog.warn("geo-refresh: settings read failed (${e.javaClass.simpleName})")
            return Outcome.Failed
        }
        if (setting == GeoUpdateInterval.Off) return Outcome.Skipped
        val engine = engine()
        if (engine.status.value !is Status.Running) return Outcome.Skipped
        val failure = engine.updateConfigAssets().exceptionOrNull() ?: return Outcome.Completed
        val code = (failure as? EngineException)?.code ?: ErrorCode.Internal
        if (code == ErrorCode.Busy) return Outcome.Skipped
        VpnLog.warn("geo-refresh: update failed ($code)")
        return Outcome.Failed
    }
}
