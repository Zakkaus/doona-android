package dev.doona.android.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.await
import dev.doona.android.app.DoonaApplication
import dev.doona.android.data.settings.GeoUpdateInterval
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.hours

class GeoRefreshWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val refresh = (applicationContext as DoonaApplication).container.geoRefresh
        return when (refresh.run()) {
            GeoRefresh.Outcome.Completed, GeoRefresh.Outcome.Skipped -> Result.success()
            GeoRefresh.Outcome.Failed -> Result.failure()
        }
    }
}

internal suspend fun scheduleGeoRefresh(workManager: WorkManager, setting: GeoUpdateInterval) {
    val interval = setting.duration
    if (interval == null) {
        workManager.cancelUniqueWork(GEO_REFRESH_WORK).await()
        workManager.cancelUniqueWork(GEO_ON_CONNECT_WORK).await()
        return
    }
    val request = PeriodicWorkRequestBuilder<GeoRefreshWorker>(
        interval.inWholeMilliseconds,
        TimeUnit.MILLISECONDS,
        1.hours.inWholeMilliseconds,
        TimeUnit.MILLISECONDS,
    ).setConstraints(geoRefreshConstraints()).build()
    workManager.enqueueUniquePeriodicWork(GEO_REFRESH_WORK, ExistingPeriodicWorkPolicy.UPDATE, request).await()
}

internal suspend fun scheduleGeoRefreshOnConnect(workManager: WorkManager) {
    val request = OneTimeWorkRequestBuilder<GeoRefreshWorker>().setConstraints(geoRefreshConstraints()).build()
    workManager.enqueueUniqueWork(GEO_ON_CONNECT_WORK, ExistingWorkPolicy.KEEP, request).await()
}

private fun geoRefreshConstraints() = Constraints.Builder()
    .setRequiredNetworkType(NetworkType.CONNECTED)
    .setRequiresBatteryNotLow(true)
    .build()

internal const val GEO_ON_CONNECT_WORK = "geo-refresh-on-connect"
internal const val GEO_REFRESH_WORK = "geo-refresh"
