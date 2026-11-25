package dev.doona.android.work

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.doona.android.data.settings.SubscriptionUpdate
import dev.doona.android.data.settings.SubscriptionUpdateSettings
import dev.doona.android.data.settings.UpdateInterval
import dev.doona.android.engine.Engine
import dev.doona.android.engine.EngineException
import dev.doona.android.engine.ErrorCode
import dev.doona.android.engine.Status
import dev.doona.android.engine.Subscription
import dev.doona.android.engine.SubscriptionFetch
import dev.doona.android.engine.SubscriptionFormat
import dev.doona.android.engine.fake.FakeEngine
import dev.doona.android.report.AppEventLevel
import dev.doona.android.report.AppEvents
import dev.doona.android.vpn.VpnLog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.time.Duration.Companion.hours

@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalCoroutinesApi::class)
class SubscriptionRefreshTest {
    private val settings = TestSettings()

    @Test
    fun staleSubscriptionsRunOnceInOrderAndFailureDoesNotBlockTheRest() = runTest {
        AppEvents.clear()
        val pending = CompletableDeferred<Unit>()
        val engine = RecordingEngine(
            listOf(sub("boundary", NOW - 12.hours.inWholeMilliseconds), sub("fresh", NOW - 11.hours.inWholeMilliseconds), sub("never", null)),
        ) { id ->
            if (id == "boundary") {
                pending.await()
                Result.failure(EngineException(ErrorCode.FetchFailed))
            } else {
                Result.success(sub(id, NOW))
            }
        }
        val refresh = SubscriptionRefresh(settings, { engine }) { NOW }
        val batch = async { refresh.run() }
        engine.began.await()
        assertEquals(SubscriptionRefresh.Outcome.Skipped, refresh.run())
        assertEquals(listOf("boundary"), engine.calls)
        pending.complete(Unit)
        assertEquals(SubscriptionRefresh.Outcome.Failed, batch.await())
        assertEquals(listOf("boundary", "never"), engine.calls)
        val warning = AppEvents.snapshot().single { it.level == AppEventLevel.Warn && it.text.startsWith("subscription-refresh:") }
        assertEquals("subscription-refresh: update failed (FetchFailed)", warning.text)
    }

    @Test
    fun coreReadinessDeadlineWarnsWithoutStartingARefresh() = runTest {
        AppEvents.clear()
        VpnLog.warn("init failed: NoClassDefFoundError") // what a background core open records into the shared log
        val engine = RecordingEngine(listOf(sub("stale", null))) { error("updated before the core was ready") }
        engine.ready.value = false

        assertEquals(SubscriptionRefresh.Outcome.Failed, SubscriptionRefresh(settings, { engine }).run())
        assertEquals(15_000L, testScheduler.currentTime)
        assertTrue(engine.calls.isEmpty())
        val warning = AppEvents.snapshot().single { it.text.startsWith("subscription-refresh:") }
        assertEquals(AppEventLevel.Warn, warning.level)
        assertEquals("subscription-refresh: core readiness timed out", warning.text)
    }

    @Test
    fun disablingDuringAFetchPreventsTheNextFetchAndFutureBatches() = runTest {
        val engine = RecordingEngine(listOf(sub("first", null), sub("second", null))) { id ->
            settings.setAutoUpdate(false)
            Result.success(sub(id, NOW))
        }
        val refresh = SubscriptionRefresh(settings, { engine }) { NOW }
        refresh.run()
        refresh.run()
        assertEquals(listOf("first"), engine.calls)
    }

    @Test
    fun connectionsUseTheCurrentSettingWithoutRestartingTheApp() = runTest {
        settings.setAutoUpdate(false)
        val engine = RecordingEngine(listOf(sub("stale", null))) { Result.success(sub(it, NOW)) }
        val refresh = SubscriptionRefresh(settings, { engine }) { NOW }
        backgroundScope.launch { refresh.watchConnections() }
        runCurrent()

        engine.status.value = Status.Running(1)
        runCurrent()
        assertTrue(engine.calls.isEmpty())

        engine.status.value = Status.Stopped
        settings.setAutoUpdate(true)
        runCurrent()
        engine.status.value = Status.Running(2)
        runCurrent()
        assertEquals(listOf("stale"), engine.calls)
    }

    @Test
    fun cancellingAWorkerDoesNotAllowAnOverlappingFetch() = runTest {
        val pending = CompletableDeferred<Unit>()
        val engine = RecordingEngine(listOf(sub("first", null), sub("next", null))) { id ->
            pending.await()
            Result.success(sub(id, NOW))
        }
        val refresh = SubscriptionRefresh(settings, { engine }) { NOW }
        val batch = launch { refresh.run() }
        engine.began.await()
        batch.cancel()
        yield()
        val overlapping = launch { refresh.run() }
        yield()
        pending.complete(Unit)
        batch.join()
        overlapping.join()
        assertEquals(listOf("first"), engine.calls)
    }

    private class TestSettings : SubscriptionUpdateSettings {
        override val subscriptionUpdate = MutableStateFlow(SubscriptionUpdate(true, UpdateInterval.Twelve))

        override suspend fun setAutoUpdate(enabled: Boolean) {
            subscriptionUpdate.value = subscriptionUpdate.value.copy(enabled = enabled)
        }

        override suspend fun setUpdateInterval(interval: UpdateInterval) {
            subscriptionUpdate.value = subscriptionUpdate.value.copy(interval = interval)
        }
    }

    private class RecordingEngine(subs: List<Subscription>, val result: suspend (String) -> Result<Subscription>) :
        Engine by FakeEngine(subscriptions = subs) {
        override val status = MutableStateFlow<Status>(Status.Stopped)
        override val ready = MutableStateFlow(true)
        val calls = mutableListOf<String>()
        val began = CompletableDeferred<Unit>()

        override suspend fun updateSubscription(id: String): Result<Subscription> {
            calls += id
            began.complete(Unit)
            return result(id)
        }
    }

    private companion object {
        val NOW = 72.hours.inWholeMilliseconds

        fun sub(id: String, updated: Long?) = Subscription(id, id, "https://example.invalid/sub", 1, updated?.let { SubscriptionFetch(it, SubscriptionFormat.ShareLinks) })
    }
}
