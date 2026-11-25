package dev.doona.android.feature.subscriptions

import dev.doona.android.engine.Engine
import dev.doona.android.engine.EngineException
import dev.doona.android.engine.ErrorCode
import dev.doona.android.engine.Subscription
import dev.doona.android.engine.SubscriptionFetch
import dev.doona.android.engine.SubscriptionFormat
import dev.doona.android.engine.fake.FakeEngine
import dev.doona.android.feature.model.RvSubFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    /** [FakeEngine] whose updates and deletes fail with [failing] while it is set. */
    private class Failing(val fake: FakeEngine = FakeEngine()) : Engine by fake {
        var failing: ErrorCode? = null
        val updated = mutableListOf<String>()

        override suspend fun updateSubscription(id: String): Result<Subscription> {
            updated += id
            return failing?.let { Result.failure(EngineException(it)) } ?: fake.updateSubscription(id)
        }

        override suspend fun removeSubscription(id: String): Result<Unit> =
            failing?.let { Result.failure(EngineException(it)) } ?: fake.removeSubscription(id)
    }

    private fun TestScope.list(engine: Engine) = SubscriptionsViewModel(engine, clock = { NOW }).also { vm ->
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
    }

    private fun TestScope.detail(engine: Engine, id: String, hours: Flow<Int?> = MutableStateFlow(6)) = SubDetailViewModel(engine, hours, id).also { vm ->
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
    fun `a delete the core refuses shows its banner over the update's, and 重試 that succeeds removes it`() = runTest {
        val engine = Failing().apply { failing = ErrorCode.Internal }
        val vm = list(engine)
        val id = engine.subscriptions.value.first().id
        vm.update(id)
        vm.confirmDelete(id)
        assertEquals(id, vm.uiState.value.page.deleteFailedId)
        assertEquals(id, vm.uiState.value.page.failedId)
        engine.failing = null
        vm.confirmDelete(id)
        assertNull(vm.uiState.value.page.deleteFailedId)
        assertTrue(engine.subscriptions.value.none { it.id == id })
    }

    @Test
    fun `a subscription's page shows a delete the core refuses, stays, and clears it on the next try`() = runTest {
        val engine = Failing().apply { failing = ErrorCode.Internal }
        val vm = detail(engine, engine.subscriptions.value.first().id)
        vm.delete()
        assertTrue(vm.uiState.value.sheet.deleteFailed)
        assertFalse(vm.uiState.value.gone)
        engine.failing = null
        vm.delete()
        assertFalse(vm.uiState.value.sheet.deleteFailed)
        assertTrue(vm.uiState.value.gone)
    }

    @Test
    fun `a subscription's page renames, sets its User-Agent and deletes through the engine`() = runTest {
        val engine = FakeEngine()
        val id = engine.subscriptions.value.first().id
        val vm = detail(engine, id)
        assertEquals(RvAutoUpdate.Every(6), vm.uiState.value.autoUpdate)
        vm.rename("Harbor 2")
        vm.setUserAgent("clash.meta")
        assertEquals("Harbor 2", vm.uiState.value.sub?.name)
        assertEquals("clash.meta", vm.uiState.value.sub?.userAgent)
        vm.setUserAgent(null)
        assertNull(vm.uiState.value.sub?.userAgent)
        vm.delete()
        assertTrue("the page leaves once its subscription is gone", vm.uiState.value.gone)
        assertEquals("and is drawn as it was while it leaves", "Harbor 2", vm.uiState.value.sub?.name)
    }

    @Test
    fun `a subscription's page restored before the engine is ready waits for it, and leaves only if the subscription is gone`() = runTest {
        val fake = FakeEngine()
        val id = fake.subscriptions.value.first().id
        val readyFlow = MutableStateFlow(false)
        val subscriptionsFlow = MutableStateFlow(emptyList<Subscription>())
        val engine = object : Engine by fake {
            override val ready = readyFlow
            override val subscriptions = subscriptionsFlow
        }
        val kept = detail(engine, id)
        val deleted = detail(engine, "s404")
        assertFalse("no snapshot yet: the page stays", kept.uiState.value.gone)
        assertNull(kept.uiState.value.sub)
        assertFalse(deleted.uiState.value.gone)
        subscriptionsFlow.value = fake.subscriptions.value
        readyFlow.value = true
        assertFalse(kept.uiState.value.gone)
        assertEquals(id, kept.uiState.value.sub?.id)
        assertTrue("a subscription deleted before the process died leaves once the engine is ready", deleted.uiState.value.gone)
    }

    @Test
    fun `a rename the core refuses opens the sheet again on the name with the failure, and the next rename goes through`() = runTest {
        val engine = FakeEngine()
        val sub = engine.subscriptions.value.first()
        val vm = detail(engine, sub.id)
        vm.rename("Harbor\nCloud")
        assertEquals(RvSubDetailState(RvSubSheet.Rename, "Harbor\nCloud", renameFailed = true), vm.uiState.value.sheet)
        assertEquals(sub.name, vm.uiState.value.sub?.name)
        vm.rename("Harbor 2")
        assertEquals("Harbor 2", vm.uiState.value.sub?.name)
    }

    @Test
    fun `a subscription's page follows the settings' interval, shows 關閉 while auto-update is off, and nothing before the settings answer`() = runTest {
        val engine = FakeEngine()
        val hours = MutableSharedFlow<Int?>()
        val vm = detail(engine, engine.subscriptions.value.first().id, hours)
        assertEquals(RvAutoUpdate.Unread, vm.uiState.value.autoUpdate)
        hours.emit(12)
        assertEquals(RvAutoUpdate.Every(12), vm.uiState.value.autoUpdate)
        hours.emit(null)
        assertEquals(RvAutoUpdate.Off, vm.uiState.value.autoUpdate)
        hours.emit(24)
        assertEquals(RvAutoUpdate.Every(24), vm.uiState.value.autoUpdate)
    }

    @Test
    fun `the engine's subscription maps to the page's`() {
        val sub = Subscription("s1", "Harbor", " https://sub.harbor.example/api?token=x ", 32, SubscriptionFetch(3, SubscriptionFormat.Base64))
        val mapped = rvSubscription(sub)
        assertEquals("sub.harbor.example", mapped.host)
        assertEquals(3L, mapped.updatedAtEpochMs)
        assertEquals(false, mapped.hasQuota)
        assertEquals(RvSubFormat.Links, mapped.format)
        assertEquals(
            listOf(RvSubFormat.Links, RvSubFormat.Links, RvSubFormat.Sip008, RvSubFormat.Clash, RvSubFormat.SingBox),
            SubscriptionFormat.entries.map(::rvSubFormat),
        )
        val quota = rvSubscription(sub.copy(usedBytes = 5, totalBytes = 10, expiresAtEpochMs = 7))
        assertEquals(listOf(5L, 10L, 7L), listOf(quota.usedBytes, quota.totalBytes, quota.expiresAtEpochMs))
    }

    @Test
    fun `a subscription the core never fetched has no update time and no format`() {
        val declared = rvSubscription(Subscription("s1", "office", "https://example.invalid/sub", 0, lastFetch = null))
        assertNull(declared.updatedAtEpochMs)
        assertNull(declared.format)
    }

    private companion object {
        const val NOW = 1_795_400_000_000L
    }
}
