package dev.doona.android.work

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import dev.doona.android.app.FakeEngineApplication
import dev.doona.android.data.settings.AppSettings
import dev.doona.android.data.settings.GeoUpdateInterval
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours

@RunWith(AndroidJUnit4::class)
// Use a separate pre-namespace sandbox from SubscriptionRefreshScheduleTest's WorkManager instance.
@Config(sdk = [33], application = FakeEngineApplication::class)
@LooperMode(LooperMode.Mode.INSTRUMENTATION_TEST)
class GeoRefreshScheduleTest {
    @Test
    fun changingTheStoredIntervalReschedulesTheSameJobAndOffCancelsIt() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<FakeEngineApplication>()
        val settings = AppSettings.get(app)
        val manager = WorkManager.getInstance(app)
        val jobs = manager.getWorkInfosForUniqueWorkFlow(GEO_REFRESH_WORK)
        withTimeout(10_000) {
            val initial = jobs.map { it.singleOrNull { job -> !job.state.isFinished } }.first { it != null }!!
            assertEquals(7.days.inWholeMilliseconds, initial.periodicityInfo?.repeatIntervalMillis)
            assertEquals(1.hours.inWholeMilliseconds, initial.periodicityInfo?.flexIntervalMillis)
            assertEquals(NetworkType.CONNECTED, initial.constraints.requiredNetworkType)
            assertTrue(initial.constraints.requiresBatteryNotLow())

            val connections = manager.getWorkInfosForUniqueWorkFlow(GEO_ON_CONNECT_WORK)
            assertTrue(connections.first().isEmpty())
            app.container.engine.connect()
            val job = connections.first { it.isNotEmpty() }.single()
            assertEquals(null, job.periodicityInfo)
            assertEquals(NetworkType.CONNECTED, job.constraints.requiredNetworkType)
            assertTrue(job.constraints.requiresBatteryNotLow())
            scheduleGeoRefreshOnConnect(manager)
            assertEquals(job.id, connections.first().single().id)

            settings.setGeoUpdateInterval(GeoUpdateInterval.Daily)
            val updated = jobs.map { it.filterNot { job -> job.state.isFinished } }.first { active ->
                active.any { it.periodicityInfo?.repeatIntervalMillis == 1.days.inWholeMilliseconds }
            }.single()
            assertEquals(initial.id, updated.id)

            settings.setGeoUpdateInterval(GeoUpdateInterval.Off)
            val cancelled = manager.getWorkInfoByIdFlow(initial.id).first { it?.state == WorkInfo.State.CANCELLED }
            assertEquals(initial.id, cancelled?.id)
        }
    }
}
