package dev.doona.android.work

import android.app.AlarmManager
import android.app.Application
import android.app.job.JobScheduler
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.impl.WorkManagerImpl
import androidx.work.impl.close
import dev.doona.android.data.settings.SubscriptionUpdate
import dev.doona.android.data.settings.UpdateInterval
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.hours

@RunWith(AndroidJUnit4::class)
// API 31 uses the default JobScheduler namespace that Robolectric exposes to this test.
@Config(sdk = [31], application = Application::class)
class SubscriptionRefreshScheduleTest {
    @Test
    fun changingTheIntervalUpdatesOneConstrainedJobAndDisablingCancelsIt() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        // A WorkManager of this test's own: the app-wide one is a static that an earlier test in this JVM may have made
        // for its own application, and `initialize` would then refuse or keep that one.
        val manager = WorkManagerImpl(context, Configuration.Builder().build())
        val jobs = context.getSystemService(JobScheduler::class.java)
        val alarms = shadowOf(context.getSystemService(AlarmManager::class.java))

        withTimeout(10_000) {
            scheduleSubscriptionRefresh(manager, SubscriptionUpdate(true, UpdateInterval.Six))
            val initial = manager.activeJob()
            assertEquals(6.hours.inWholeMilliseconds, initial.periodicityInfo?.repeatIntervalMillis)

            scheduleSubscriptionRefresh(manager, SubscriptionUpdate(true, UpdateInterval.FortyEight))
            val updated = manager.activeJob()
            assertEquals(initial.id, updated.id)
            assertEquals(48.hours.inWholeMilliseconds, updated.periodicityInfo?.repeatIntervalMillis)
            assertEquals(1.hours.inWholeMilliseconds, updated.periodicityInfo?.flexIntervalMillis)
            assertEquals(NetworkType.CONNECTED, updated.constraints.requiredNetworkType)
            assertTrue(updated.constraints.requiresBatteryNotLow())
            assertEquals(1, jobs.allPendingJobs.count { it.service.className.endsWith(".SystemJobService") })
            assertNull(alarms.nextScheduledAlarm)

            scheduleSubscriptionRefresh(manager, SubscriptionUpdate(false, UpdateInterval.FortyEight))
            assertEquals(WorkInfo.State.CANCELLED, manager.getWorkInfoById(initial.id).get(5, TimeUnit.SECONDS)?.state)
            assertTrue(jobs.allPendingJobs.none { it.service.className.endsWith(".SystemJobService") })
        }
        manager.close()
    }

    private fun WorkManager.activeJob(): WorkInfo = getWorkInfosForUniqueWork(SUBSCRIPTION_REFRESH_WORK)
        .get(5, TimeUnit.SECONDS)
        .single { !it.state.isFinished }
}
