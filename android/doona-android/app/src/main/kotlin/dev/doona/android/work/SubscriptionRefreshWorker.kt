package dev.doona.android.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.await
import dev.doona.android.app.DoonaApplication
import dev.doona.android.data.settings.SubscriptionUpdate
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.hours

class SubscriptionRefreshWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val refresh = (applicationContext as DoonaApplication).container.subscriptionRefresh
        // A failed fetch waits for the next period instead of asking WorkManager to retry it.
        return when (refresh.run()) {
            SubscriptionRefresh.Outcome.Completed, SubscriptionRefresh.Outcome.Skipped -> Result.success()
            SubscriptionRefresh.Outcome.Failed -> Result.failure()
        }
    }
}

internal suspend fun scheduleSubscriptionRefresh(workManager: WorkManager, setting: SubscriptionUpdate) {
    if (!setting.enabled) {
        workManager.cancelUniqueWork(SUBSCRIPTION_REFRESH_WORK).await()
        return
    }
    val request = PeriodicWorkRequestBuilder<SubscriptionRefreshWorker>(
        setting.interval.duration.inWholeMilliseconds,
        TimeUnit.MILLISECONDS,
        1.hours.inWholeMilliseconds,
        TimeUnit.MILLISECONDS,
    ).setConstraints(
        Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true)
            .build(),
    ).build()
    workManager.enqueueUniquePeriodicWork(SUBSCRIPTION_REFRESH_WORK, ExistingPeriodicWorkPolicy.UPDATE, request).await()
}

internal const val SUBSCRIPTION_REFRESH_WORK = "subscription-refresh"
