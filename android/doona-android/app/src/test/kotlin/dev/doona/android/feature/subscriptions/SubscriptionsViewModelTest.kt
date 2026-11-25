package dev.doona.android.feature.subscriptions

import dev.doona.android.engine.Engine
import dev.doona.android.engine.EngineException
import dev.doona.android.engine.ErrorCode
import dev.doona.android.engine.Subscription
import dev.doona.android.engine.SubscriptionFormat
import dev.doona.android.engine.fake.FakeEngine
import dev.doona.android.feature.model.RvSubFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SubscriptionsViewModelTest {
    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    /** [FakeEngine] whose updates fail with [failing] while it is set. */
    private class Failing(val fake: FakeEngine = FakeEngine()) : Engine by fake {
        var failing: ErrorCode? = null
        val updated = mutableListOf<String>()

        override suspend fun updateSubscription(id: String): Result<Subscription> {
            updated += id
            return failing?.let { Result.failure(EngineException(it)) } ?: fake.updateSubscription(id)
        }
    }

    private fun TestScope.list(engine: Engine) = SubscriptionsViewModel(engine, clock = { NOW }).also { vm ->
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
    }

    private fun TestScope.detail(engine: Engine, id: String, hours: Int = 6) = SubDetailViewModel(engine, MutableStateFlow(hours), id).also { vm ->
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
    }

    @Test
    fun `the cards are skeletons until the engine has loaded`() = runTest {
        val engine = FakeEngine().apply { ready.value = false }
        val vm = list(engine)
        assertTrue(vm.uiState.value.loading)
        engine.ready.value = true
        assertEquals(false, vm.uiState.value.loading)
        assertEquals(engine.subscriptions.value.map { it.id }, vm.uiState.value.page.subscriptions.map { it.id })
        assertEquals(NOW, vm.uiState.value.page.nowEpochMs)
    }

    @Test
    fun `a failed update shows its banner until the next update of it starts`() = runTest {
        val engine = Failing().apply { failing = ErrorCode.FetchFailed }
        val vm = list(engine)
        val id = engine.subscriptions.value.first().id
        vm.update(id)
        assertEquals(id, vm.uiState.value.page.failedId)
        assertEquals(emptySet<String>(), vm.uiState.value.updating)
        engine.failing = null
        vm.update(id)
        assertNull(vm.uiState.value.page.failedId)
        assertEquals(listOf(id, id), engine.updated)
    }

    @Test
    fun `the core's report of a failed refresh shows the banner too`() = runTest {
        val engine = FakeEngine()
        engine.subscriptions.value = engine.subscriptions.value.mapIndexed { i, sub -> if (i == 0) sub.copy(error = "FetchFailed") else sub }
        assertEquals(engine.subscriptions.value[0].id, list(engine).uiState.value.page.failedId)
    }

    @Test
    fun `update all updates each subscription once`() = runTest {
        val engine = Failing()
        list(engine).updateAll()
        assertEquals(engine.subscriptions.value.map { it.id }, engine.updated)
    }

    @Test
    fun `delete asks first, and only the answer removes`() = runTest {
        val engine = FakeEngine()
        val vm = list(engine)
        val id = engine.subscriptions.value.first().id
        vm.delete(id)
        assertEquals(id, vm.uiState.value.page.deletingId)
        vm.delete(null)
        assertNull(vm.uiState.value.page.deletingId)
        assertTrue(engine.subscriptions.value.any { it.id == id })
        vm.delete(id)
        vm.confirmDelete(id)
        assertNull(vm.uiState.value.page.deletingId)
        assertTrue(engine.subscriptions.value.none { it.id == id })
    }

    @Test
    fun `a subscription's page renames, sets its User-Agent and deletes through the engine`() = runTest {
        val engine = FakeEngine()
        val id = engine.subscriptions.value.first().id
        val vm = detail(engine, id)
        assertEquals(6, vm.uiState.value.updateHours)
        vm.rename("Harbor 2")
        vm.setUserAgent("clash.meta")
        assertEquals("Harbor 2", vm.uiState.value.sub?.name)
        assertEquals("clash.meta", vm.uiState.value.sub?.userAgent)
        vm.setUserAgent(null)
        assertNull(vm.uiState.value.sub?.userAgent)
        vm.delete()
        assertNull("the page leaves once its subscription is gone", vm.uiState.value.sub)
    }

    @Test
    fun `the engine's subscription maps to the page's`() {
        val sub = Subscription("s1", "Harbor", " https://sub.harbor.example/api?token=x ", 32, null, format = SubscriptionFormat.Base64)
        val mapped = rvSubscription(sub)
        assertEquals("sub.harbor.example", mapped.host)
        assertEquals(0L, mapped.updatedAtEpochMs)
        assertEquals(false, mapped.hasQuota)
        assertEquals(RvSubFormat.Links, mapped.format)
        assertEquals(
            listOf(RvSubFormat.Links, RvSubFormat.Links, RvSubFormat.Sip008, RvSubFormat.Clash, RvSubFormat.SingBox, RvSubFormat.Links),
            (SubscriptionFormat.entries + listOf(null)).map(::rvSubFormat),
        )
        val quota = rvSubscription(sub.copy(usedBytes = 5, totalBytes = 10, expiresAtEpochMs = 7, updatedAtEpochMs = 3))
        assertEquals(listOf(5L, 10L, 7L, 3L), listOf(quota.usedBytes, quota.totalBytes, quota.expiresAtEpochMs, quota.updatedAtEpochMs))
    }

    private companion object {
        const val NOW = 1_795_400_000_000L
    }
}
