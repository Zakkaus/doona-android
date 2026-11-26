package dev.doona.android.feature.subscriptions

import dev.doona.android.engine.Engine
import dev.doona.android.engine.EngineException
import dev.doona.android.engine.ErrorCode
import dev.doona.android.engine.Subscription
import dev.doona.android.engine.SubscriptionFetch
import dev.doona.android.engine.SubscriptionFormat
import dev.doona.android.engine.fake.FakeEngine
import dev.doona.android.feature.model.RvSubFormat
import dev.doona.android.vpn.HonkFixtures
import dev.doona.android.vpn.honk.ProviderList
import dev.doona.android.vpn.nativeJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** 訂閱 on honk's RV sample profile ([HonkFixtures.rvSample]); a subscription's page still on [FakeEngine]. */
@OptIn(ExperimentalCoroutinesApi::class)
class SubscriptionsViewModelTest {
    private val honk = HonkFixtures.rvSample()

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    /** [FakeEngine] whose updates, renames, User-Agents and deletes fail with [failing] while it is set. */
    private class Failing(val fake: FakeEngine = FakeEngine()) : Engine by fake {
        var failing: ErrorCode? = null

        override suspend fun updateSubscription(id: String): Result<Subscription> =
            failing?.let { Result.failure(EngineException(it)) } ?: fake.updateSubscription(id)

        override suspend fun removeSubscription(id: String): Result<Unit> =
            failing?.let { Result.failure(EngineException(it)) } ?: fake.removeSubscription(id)

        override suspend fun renameSubscription(id: String, name: String): Result<Subscription> =
            failing?.let { Result.failure(EngineException(it)) } ?: fake.renameSubscription(id, name)

        override suspend fun setSubscriptionUserAgent(id: String, userAgent: String?): Result<Subscription> =
            failing?.let { Result.failure(EngineException(it)) } ?: fake.setSubscriptionUserAgent(id, userAgent)
    }

    private fun TestScope.list() = SubscriptionsViewModel(honk, clock = { NOW }, io = UnconfinedTestDispatcher(testScheduler)).also { vm ->
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
    }

    private fun TestScope.detail(engine: Engine, id: String, hours: Flow<Int?> = MutableStateFlow(6)) = SubDetailViewModel(engine, hours, id).also { vm ->
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
    }

    @Test
    fun `the cards are skeletons until the first read ends`() = runTest {
        val vm = SubscriptionsViewModel(honk, clock = { NOW }, io = StandardTestDispatcher(testScheduler))
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        assertTrue(vm.uiState.value.loading)
        advanceUntilIdle()
        assertFalse(vm.uiState.value.loading)
        assertEquals(listOf("s1", "s2"), vm.uiState.value.page.subscriptions.map { it.id })
        assertEquals(NOW, vm.uiState.value.page.nowEpochMs)
    }

    @Test
    fun `a first read that fails lists nothing and ends the skeletons`() = runTest {
        honk.on("GET", "/api/v1/providers", 503, HonkFixtures.body("errors/snapshot-unavailable"))
        val state = list().uiState.value
        assertFalse(state.loading)
        assertEquals(emptyList<Any>(), state.page.subscriptions)
    }

    @Test
    fun `only subscription providers are listed`() = runTest {
        val file = HonkFixtures.body("providers/rv-sample").replace(""""id": "s2",""", """"id": "f1",""").replace(
            """"kind": "subscription",
      "url_redacted": "https://lantern-net.example/sub",""",
            """"kind": "file",
      "url_redacted": null,""",
        )
        honk.on("GET", "/api/v1/providers", 200, file)
        assertEquals(listOf("s1"), list().uiState.value.page.subscriptions.map { it.id })
    }

    @Test
    fun `a failed update shows its banner until the next update of it starts`() = runTest {
        refreshes("s1", "provider-refresh-failed")
        val vm = list()
        vm.update("s1")
        advanceUntilIdle()
        assertEquals(RvSubFailure("s1", ErrorCode.FetchFailed), vm.uiState.value.page.failed)
        assertEquals(emptySet<String>(), vm.uiState.value.updating)
        refreshes("s1", "provider-refresh-s1")
        vm.update("s1")
        assertNull(vm.uiState.value.page.failed)
        advanceUntilIdle()
        assertNull(vm.uiState.value.page.failed)
        assertEquals(2, honk.calls.count { it.method == "POST" && it.path == "/api/v1/providers/s1/refresh" })
    }

    @Test
    fun `an update in flight keeps its card's bar until it ends, and the list is read again`() = runTest {
        refreshes("s1", "provider-refresh-s1")
        refreshes("s2", "provider-refresh-s1")
        val vm = list()
        vm.update("s1")
        assertEquals(setOf("s1"), vm.uiState.value.updating)
        val reads = providerReads()
        advanceUntilIdle()
        assertEquals(emptySet<String>(), vm.uiState.value.updating)
        assertEquals(reads + 1, providerReads())

        vm.updateAll()
        assertEquals(setOf("s1", "s2"), vm.uiState.value.updating)
        advanceUntilIdle()
        assertEquals(emptySet<String>(), vm.uiState.value.updating)
    }

    @Test
    fun `an update whose subscription is gone has no bar`() {
        val providers = nativeProviders("providers/rv-sample")
        val state = subsUiState(providers, SubsLocal(updating = setOf("s1", "gone")), NOW)
        assertEquals(setOf("s1"), state.updating)
    }

    @Test
    fun `honk's report of a failed fetch shows the banner too`() = runTest {
        honk.on("GET", "/api/v1/providers", 200, HonkFixtures.body("providers/rv-sample-failed"))
        val page = list().uiState.value.page
        assertEquals(RvSubFailure("s1", ErrorCode.FetchFailed), page.failed)
    }

    @Test
    fun `a failed update's banner says why`() = runTest {
        for ((status, code) in listOf(409 to ErrorCode.Busy, 503 to ErrorCode.Busy, 404 to ErrorCode.NotFound)) {
            honk.on("POST", "/api/v1/providers/s1/refresh", status, HonkFixtures.body("errors/temporarily-unavailable"))
            val vm = list()
            vm.update("s1")
            assertEquals(code, vm.uiState.value.page.failed?.code)
        }
    }

    @Test
    fun `update all updates each subscription once, one after another`() = runTest {
        refreshes("s1", "provider-refresh-s1")
        refreshes("s2", "provider-refresh-s1")
        list().updateAll()
        advanceUntilIdle()
        assertEquals(listOf("/api/v1/providers/s1/refresh", "/api/v1/providers/s2/refresh"), honk.calls.filter { it.method == "POST" }.map { it.path })
    }

    @Test
    fun `delete asks first, and only the answer removes`() = runTest {
        honk.on("DELETE", "/api/v1/providers/s1", 200, HonkFixtures.body("providers/deleted"))
        val vm = list()
        vm.delete("s1")
        assertEquals("s1", vm.uiState.value.page.deletingId)
        vm.delete(null)
        assertNull(vm.uiState.value.page.deletingId)
        assertTrue(honk.calls.none { it.method == "DELETE" })
        vm.delete("s1")
        honk.on("GET", "/api/v1/providers", 200, withoutS1())
        vm.confirmDelete("s1")
        assertNull(vm.uiState.value.page.deletingId)
        assertTrue(honk.calls.any { it.method == "DELETE" && it.path == "/api/v1/providers/s1" })
        assertEquals(listOf("s2"), vm.uiState.value.page.subscriptions.map { it.id })
    }

    @Test
    fun `a delete honk refuses shows its banner over the update's, and 重試 that succeeds removes it`() = runTest {
        refreshes("s1", "provider-refresh-failed")
        honk.on("DELETE", "/api/v1/providers/s1", 503, HonkFixtures.body("errors/temporarily-unavailable"))
        val vm = list()
        vm.update("s1")
        advanceUntilIdle()
        vm.confirmDelete("s1")
        assertEquals(RvSubFailure("s1", ErrorCode.Busy), vm.uiState.value.page.deleteFailed)
        assertEquals("s1", vm.uiState.value.page.failed?.id)
        honk.on("DELETE", "/api/v1/providers/s1", 200, HonkFixtures.body("providers/deleted"))
        honk.on("GET", "/api/v1/providers", 200, withoutS1())
        vm.confirmDelete("s1")
        assertNull(vm.uiState.value.page.deleteFailed)
        assertTrue(vm.uiState.value.page.subscriptions.none { it.id == "s1" })
    }

    @Test
    fun `a delete honk runs in the background and fails says why`() = runTest {
        honk.on("DELETE", "/api/v1/providers/s1", 202, HonkFixtures.body("operations/provider-delete-accepted"))
        honk.on("GET", "/api/v1/operations/op-delete", 200, HonkFixtures.body("operations/provider-delete-store-unavailable"))
        val vm = list()
        vm.confirmDelete("s1")
        advanceUntilIdle()
        assertEquals(RvSubFailure("s1", ErrorCode.Internal), vm.uiState.value.page.deleteFailed)
    }

    @Test
    fun `a delete honk answers not_found is gone already, with no banner`() = runTest {
        honk.on("DELETE", "/api/v1/providers/s1", 404, HonkFixtures.body("errors/temporarily-unavailable"))
        val vm = list()
        honk.on("GET", "/api/v1/providers", 200, withoutS1())
        vm.confirmDelete("s1")
        assertNull(vm.uiState.value.page.deleteFailed)
        assertTrue(vm.uiState.value.page.subscriptions.none { it.id == "s1" })
    }

    @Test
    fun `a subscription's page shows a delete the core refuses, stays, and clears it on the next try`() = runTest {
        val engine = Failing().apply { failing = ErrorCode.Internal }
        val vm = detail(engine, engine.subscriptions.value.first().id)
        vm.delete()
        assertEquals(RvSubDetailFailure.Delete(ErrorCode.Internal), vm.uiState.value.sheet.failed)
        assertFalse(vm.uiState.value.gone)
        engine.failing = null
        vm.delete()
        assertNull(vm.uiState.value.sheet.failed)
        assertTrue(vm.uiState.value.gone)
    }

    @Test
    fun `a refused delete's banner says why on the subscription's page`() = runTest {
        for (code in listOf(ErrorCode.StorageFull, ErrorCode.WriteFailed, ErrorCode.Busy)) {
            val engine = Failing().apply { failing = code }
            val page = detail(engine, engine.subscriptions.value.first().id)
            page.delete()
            assertEquals(RvSubDetailFailure.Delete(code), page.uiState.value.sheet.failed)
        }
    }

    @Test
    fun `a subscription's page shows why an update or a User-Agent the core refuses failed, and clears it on the next try`() = runTest {
        for (code in listOf(ErrorCode.StorageFull, ErrorCode.WriteFailed, ErrorCode.FetchFailed)) {
            val engine = Failing().apply { failing = code }
            val sub = engine.subscriptions.value.first()
            val vm = detail(engine, sub.id)
            vm.update()
            assertEquals(RvSubDetailFailure.Update(code), vm.uiState.value.sheet.failed)
            vm.setUserAgent("clash-verge/2.0")
            assertEquals(RvSubDetailFailure.UserAgent(code, "clash-verge/2.0"), vm.uiState.value.sheet.failed)
            assertEquals(sub.userAgent, vm.uiState.value.sub?.userAgent)
            engine.failing = null
            vm.setUserAgent("clash-verge/2.0")
            assertNull(vm.uiState.value.sheet.failed)
            assertEquals("clash-verge/2.0", vm.uiState.value.sub?.userAgent)
            engine.failing = code
            vm.setUserAgent(null)
            assertEquals(RvSubDetailFailure.UserAgent(code, null), vm.uiState.value.sheet.failed)
            engine.failing = null
            vm.update()
            assertNull(vm.uiState.value.sheet.failed)
        }
    }

    @Test
    fun `a rename the core refuses says why under the field and keeps the page's banner`() = runTest {
        for (code in listOf(ErrorCode.StorageFull, ErrorCode.WriteFailed)) {
            val engine = Failing().apply { failing = code }
            val sub = engine.subscriptions.value.first()
            val vm = detail(engine, sub.id)
            vm.delete()
            vm.rename("Harbor 2")
            val sheet = vm.uiState.value.sheet
            assertEquals(RvSubDetailState(RvSubSheet.Rename, "Harbor 2", renameFailed = code, failed = RvSubDetailFailure.Delete(code)), sheet)
            assertEquals(sub.name, vm.uiState.value.sub?.name)
        }
    }

    @Test
    fun `a delete the core answers not_found on the subscription's page is gone already, with no banner`() = runTest {
        val engine = Failing().apply { failing = ErrorCode.NotFound }
        val page = detail(engine, engine.subscriptions.value.first().id)
        page.delete()
        assertNull(page.uiState.value.sheet.failed)
        assertTrue(page.uiState.value.gone)
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
        assertEquals(RvSubDetailState(RvSubSheet.Rename, "Harbor\nCloud", renameFailed = ErrorCode.InvalidArgument), vm.uiState.value.sheet)
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

    /** [id]'s refresh accepted, then ended as the operation fixture [operation] says. */
    private fun refreshes(id: String, operation: String) {
        honk.on("POST", "/api/v1/providers/$id/refresh", 202, HonkFixtures.body("operations/provider-refresh-accepted"))
        honk.on("GET", "/api/v1/operations/op-refresh", 200, HonkFixtures.body("operations/$operation"))
    }

    private fun providerReads() = honk.calls.count { it.method == "GET" && it.path == "/api/v1/providers" }

    /** The sample's providers once Lantern Net is the only one left. */
    private fun withoutS1(): String {
        val sample = nativeJson.parseToJsonElement(HonkFixtures.body("providers/rv-sample")).jsonObject
        val left = JsonArray(sample.getValue("providers").jsonArray.filter { it.jsonObject.getValue("id").jsonPrimitive.content != "s1" })
        return JsonObject(sample + ("providers" to left)).toString()
    }

    private fun nativeProviders(name: String) = nativeJson.decodeFromString(ProviderList.serializer(), HonkFixtures.body(name)).providers

    private companion object {
        const val NOW = 1_795_400_000_000L
    }
}
