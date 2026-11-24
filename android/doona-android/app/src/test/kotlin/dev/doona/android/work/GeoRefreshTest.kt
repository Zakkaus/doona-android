package dev.doona.android.work

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.doona.android.data.settings.AppSettings
import dev.doona.android.data.settings.GeoUpdateInterval
import dev.doona.android.engine.ConfigAssets
import dev.doona.android.engine.Engine
import dev.doona.android.engine.EngineException
import dev.doona.android.engine.ErrorCode
import dev.doona.android.engine.fake.FakeEngine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.time.Duration.Companion.days

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class GeoRefreshTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun TestScope.settings() = AppSettings(AppSettings.store(backgroundScope) { folder.root.resolve("app_settings.preferences_pb") })

    @Test
    fun skipsWhileDisconnectedOrOffAndUpdatesOnlyWhenRunningAndEnabled() = runTest {
        val settings = settings()
        val engine = FakeEngine()
        val refresh = GeoRefresh(settings) { engine }
        val initial = engine.configAssets().getOrThrow()
        assertEquals(GeoRefresh.Outcome.Skipped, refresh.run())
        assertEquals(initial, engine.configAssets().getOrThrow())

        engine.connect()
        settings.setGeoUpdateInterval(GeoUpdateInterval.Off)
        assertEquals(GeoRefresh.Outcome.Skipped, refresh.run())
        assertEquals(initial, engine.configAssets().getOrThrow())

        settings.setGeoUpdateInterval(GeoUpdateInterval.Daily)
        assertEquals(GeoRefresh.Outcome.Completed, refresh.run())
        assertTrue(engine.configAssets().getOrThrow().updateWaitingForNextStart)
    }

    @Test
    fun busySkipsAndAnImmediateErrorFailsWithoutRecordingSuccess() = runTest {
        val settings = settings()
        val fake = FakeEngine().also { it.connect() }
        var code = ErrorCode.Busy
        val engine = object : Engine by fake {
            override suspend fun updateConfigAssets(): Result<ConfigAssets> = Result.failure(EngineException(code))
        }
        val refresh = GeoRefresh(settings) { engine }
        assertEquals(GeoRefresh.Outcome.Skipped, refresh.run())
        code = ErrorCode.FetchFailed
        assertEquals(GeoRefresh.Outcome.Failed, refresh.run())
        assertNull(settings.geoUpdateSchedule.first().lastSuccessEpochMs)
    }

    @Test
    fun dispatchOutlivesCancellationAndKeepsOtherWorkersOutUntilItReturns() = runTest {
        val fake = FakeEngine().also { it.connect() }
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val returned = CompletableDeferred<Unit>()
        val engine = object : Engine by fake {
            override suspend fun updateConfigAssets(): Result<ConfigAssets> {
                entered.complete(Unit)
                release.await()
                returned.complete(Unit)
                return fake.updateConfigAssets()
            }
        }
        val refresh = GeoRefresh(settings()) { engine }
        val worker = launch { refresh.run() }
        entered.await()
        worker.cancel()
        runCurrent()
        try {
            assertFalse(worker.isCompleted)
            assertEquals(GeoRefresh.Outcome.Skipped, refresh.run())
        } finally {
            release.complete(Unit)
        }
        worker.join()
        assertTrue(returned.isCompleted)
    }

    @Test
    fun successIsRecordedOnlyAfterCoreCompletionAndControlsTheNextConnection() = runTest {
        val settings = settings()
        val fake = FakeEngine()
        var now = 10.days.inWholeMilliseconds
        var enqueues = 0
        val checked = CompletableDeferred<Unit>()
        val complete = CompletableDeferred<Unit>()
        val engine = object : Engine by fake {
            override suspend fun configAssets(): Result<ConfigAssets> {
                checked.complete(Unit)
                complete.await()
                return fake.configAssets()
            }
        }
        val refresh = GeoRefresh(settings, { now }) { engine }
        val watcher = launch { refresh.watchConnections { enqueues++ } }
        fake.connect()
        val worker = async { refresh.run() }
        checked.await()
        assertEquals(1, enqueues)
        assertNull(settings.geoUpdateSchedule.first().lastSuccessEpochMs)
        complete.complete(Unit)
        assertEquals(GeoRefresh.Outcome.Completed, worker.await())
        assertEquals(now, settings.geoUpdateSchedule.first().lastSuccessEpochMs)

        fake.disconnect()
        runCurrent()
        now += (7.days / 2).inWholeMilliseconds // Half the weekly interval: not yet due.
        fake.connect()
        runCurrent()
        assertEquals(1, enqueues)
        assertEquals(GeoRefresh.Outcome.Skipped, refresh.run())

        fake.disconnect()
        runCurrent()
        now++
        fake.connect()
        runCurrent()
        assertEquals(2, enqueues)
        watcher.cancelAndJoin()
    }

    @Test
    fun anAcceptedUpdateThatLaterFailsDoesNotAdvanceTheSchedule() = runTest {
        val settings = settings()
        val fake = FakeEngine().also { it.connect() }
        val engine = object : Engine by fake {
            override suspend fun configAssets(): Result<ConfigAssets> =
                Result.success(ConfigAssets("builtin", null, "failed", "update_failed", false))
        }
        val refresh = GeoRefresh(settings) { engine }
        assertEquals(GeoRefresh.Outcome.Failed, refresh.run())
        assertNull(settings.geoUpdateSchedule.first().lastSuccessEpochMs)
    }
}
