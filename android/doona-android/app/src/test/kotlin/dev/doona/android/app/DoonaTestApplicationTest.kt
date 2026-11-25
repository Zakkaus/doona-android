package dev.doona.android.app

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Constraints
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.await
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

@RunWith(AndroidJUnit4::class)
class DoonaTestApplicationTest {
    @Test
    fun constrainedWorkRegistersNoReceiverThatCouldOutliveTheTest() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<DoonaTestApplication>()
        val manager = WorkManager.getInstance(app)
        val request = OneTimeWorkRequestBuilder<IdleWorker>().setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build()).build()
        manager.enqueue(request).await()
        // An in-process scheduler would start tracking on WorkManager's serial task thread, ahead of these operations.
        repeat(3) { manager.pruneWork().await() }
        val battery = shadowOf(app).registeredReceivers.filter { it.intentFilter.hasAction(Intent.ACTION_BATTERY_LOW) }
        assertEquals(emptyList<Any>(), battery)
    }
}

class IdleWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result = Result.success()
}
