package dev.doona.android.feature.subscriptions

import dev.doona.android.engine.ErrorCode
import dev.doona.android.vpn.HonkFixtures
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
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
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

/**
 * A subscription's page on honk's RV sample profile ([HonkFixtures.rvSample]) with its configuration file
 * (`config/rv-sample`): Harbor Cloud (`s1`) and Lantern Net as `'name': 'url'` entries.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SubDetailViewModelTest {
    private val honk = HonkFixtures.rvSample().apply {
        on("GET", "/api/v1/config", 200, HonkFixtures.body("config/rv-sample"))
        on("PUT", "/api/v1/config/sources/source-main", 202, HonkFixtures.body("config-replace/queued"))
        on("GET", "/api/v1/operations/op-config-01", 200, HonkFixtures.body("operations/reload-complete"))
    }

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.detail(id: String = "s1", hours: Flow<Int?> = MutableStateFlow(6)) =
        SubDetailViewModel(honk, hours, id, io = UnconfinedTestDispatcher(testScheduler)).also { vm ->
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        }

    @Test
    fun `the page shows honk's provider with the User-Agent its entry sets`() = runTest {
        val vm = detail()
        val sub = vm.uiState.value.sub!!
        assertEquals(listOf("s1", "Harbor Cloud", "sub.harbor-cloud.example"), listOf(sub.id, sub.name, sub.host))
        assertNull("no ua: honk's default", sub.userAgent)
        assertNull("honk names no body format", sub.format)
        assertEquals(RvAutoUpdate.Every(6), vm.uiState.value.autoUpdate)

        honk.on("GET", "/api/v1/config", 200, config { it.replace("'Harbor Cloud': 'https://sub.harbor-cloud.example/sub'", "'Harbor Cloud': 'https://sub.harbor-cloud.example/sub'(clash.meta)") })
        assertEquals("clash.meta", detail().uiState.value.sub?.userAgent)
    }

    @Test
    fun `a page restored before its first read waits for it, and leaves only if the provider is gone`() = runTest {
        val kept = SubDetailViewModel(honk, MutableStateFlow(6), "s1", io = StandardTestDispatcher(testScheduler))
        val deleted = SubDetailViewModel(honk, MutableStateFlow(6), "s404", io = StandardTestDispatcher(testScheduler))
        assertNull(kept.uiState.value.sub)
        assertFalse("no read yet: the page stays", deleted.uiState.value.gone)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { kept.uiState.collect {} }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { deleted.uiState.collect {} }
        advanceUntilIdle()
        assertFalse(kept.uiState.value.gone)
        assertEquals("s1", kept.uiState.value.sub?.id)
        assertTrue("a provider deleted before the process died leaves once read", deleted.uiState.value.gone)
    }

    @Test
    fun `a rename rewrites the entry's tag, and the page follows the provider by its new name`() = runTest {
        val vm = detail()
        honk.on("GET", "/api/v1/providers", 200, providers { it.replace("\"Harbor Cloud\"", "\"Harbor 2\"") })
        vm.rename("Harbor 2")
        advanceUntilIdle()
        assertEquals(config { it.replace("'Harbor Cloud':", "'Harbor 2':") }.let(::content), put())
        assertEquals("Harbor 2", vm.uiState.value.sub?.name)
        assertEquals(RvSubDetailState(), vm.uiState.value.sheet)
    }

    @Test
    fun `a rename honk's reload rejects opens the sheet again on the name with why, and the next one goes through`() = runTest {
        val vm = detail()
        honk.on("GET", "/api/v1/operations/op-config-01", 200, HonkFixtures.body("operations/reload-running").replace("\"running\"", "\"failed\""))
        vm.rename("Lantern Net")
        advanceUntilIdle()
        assertEquals(RvSubDetailState(RvSubSheet.Rename, "Lantern Net", renameFailed = ErrorCode.InvalidArgument), vm.uiState.value.sheet)
        assertEquals("Harbor Cloud", vm.uiState.value.sub?.name)

        vm.rename("Harbor\nCloud")
        assertEquals(RvSubDetailState(RvSubSheet.Rename, "Harbor\nCloud", renameFailed = ErrorCode.InvalidArgument), vm.uiState.value.sheet)
    }

    @Test
    fun `a rename honk refuses says why under the field and keeps the page's banner`() = runTest {
        honk.on("DELETE", "/api/v1/providers/s1", 503, BUSY)
        honk.on("PUT", "/api/v1/config/sources/source-main", 503, BUSY)
        val vm = detail()
        vm.delete()
        vm.rename("Harbor 2")
        assertEquals(RvSubDetailState(RvSubSheet.Rename, "Harbor 2", renameFailed = ErrorCode.Busy, failed = RvSubDetailFailure.Delete(ErrorCode.Busy)), vm.uiState.value.sheet)
        assertEquals("Harbor Cloud", vm.uiState.value.sub?.name)
    }

    @Test
    fun `a User-Agent opens a block on the entry, and the default takes it out again`() = runTest {
        val vm = detail()
        vm.setUserAgent("clash.meta")
        advanceUntilIdle()
        val entry = "'Harbor Cloud': 'https://sub.harbor-cloud.example/sub'\n"
        val block = content(config()).replace(entry, "'Harbor Cloud': 'https://sub.harbor-cloud.example/sub' {\n        ua: 'clash.meta'\n    }\n")
        assertEquals(block, put())
        assertNull(vm.uiState.value.sheet.failed)

        honk.on("GET", "/api/v1/config", 200, config { block })
        vm.setUserAgent(null)
        advanceUntilIdle()
        assertEquals(content(config()), put())
        assertNull(vm.uiState.value.sheet.failed)
    }

    @Test
    fun `a User-Agent change follows the provider to the id honk gives it`() = runTest {
        val vm = detail()
        honk.on("GET", "/api/v1/providers", 200, providers { it.replace("\"id\": \"s1\"", "\"id\": \"s9\"") })
        honk.on("GET", "/api/v1/config", 200, config { it.replace("'https://sub.harbor-cloud.example/sub'", "'https://sub.harbor-cloud.example/sub'(clash.meta)") })
        vm.setUserAgent("clash.meta")
        advanceUntilIdle()
        assertEquals("s9", vm.uiState.value.sub?.id)
        assertEquals("clash.meta", vm.uiState.value.sub?.userAgent)
        assertFalse(vm.uiState.value.gone)
    }

    @Test
    fun `an update or a User-Agent honk refuses says why, and the next try clears it`() = runTest {
        for ((status, code) in listOf(503 to ErrorCode.Busy, 409 to ErrorCode.Busy, 422 to ErrorCode.InvalidArgument)) {
            honk.on("POST", "/api/v1/providers/s1/refresh", status, BUSY)
            honk.on("PUT", "/api/v1/config/sources/source-main", status, BUSY)
            val vm = detail()
            vm.update()
            assertEquals(RvSubDetailFailure.Update(code), vm.uiState.value.sheet.failed)
            vm.setUserAgent("clash-verge/2.0")
            assertEquals(RvSubDetailFailure.UserAgent(code, "clash-verge/2.0"), vm.uiState.value.sheet.failed)
            assertNull(vm.uiState.value.sub?.userAgent)
            vm.setUserAgent(null)
            assertEquals(RvSubDetailFailure.UserAgent(code, null), vm.uiState.value.sheet.failed)

            honk.on("POST", "/api/v1/providers/s1/refresh", 202, HonkFixtures.body("operations/provider-refresh-accepted"))
            honk.on("GET", "/api/v1/operations/op-refresh", 200, HonkFixtures.body("operations/provider-refresh-s1"))
            vm.update()
            advanceUntilIdle()
            assertNull(vm.uiState.value.sheet.failed)
        }
    }

    @Test
    fun `an update whose fetch fails says why`() = runTest {
        honk.on("POST", "/api/v1/providers/s1/refresh", 202, HonkFixtures.body("operations/provider-refresh-accepted"))
        honk.on("GET", "/api/v1/operations/op-refresh", 200, HonkFixtures.body("operations/provider-refresh-failed"))
        val vm = detail()
        vm.update()
        advanceUntilIdle()
        assertEquals(RvSubDetailFailure.Update(ErrorCode.FetchFailed), vm.uiState.value.sheet.failed)
    }

    @Test
    fun `a delete honk refuses shows the banner and stays, and the next try leaves`() = runTest {
        honk.on("DELETE", "/api/v1/providers/s1", 503, BUSY)
        val vm = detail()
        vm.delete()
        assertEquals(RvSubDetailFailure.Delete(ErrorCode.Busy), vm.uiState.value.sheet.failed)
        assertFalse(vm.uiState.value.gone)

        honk.on("DELETE", "/api/v1/providers/s1", 202, HonkFixtures.body("operations/provider-delete-accepted"))
        honk.on("GET", "/api/v1/operations/op-delete", 200, HonkFixtures.body("operations/provider-delete-store-unavailable"))
        vm.delete()
        advanceUntilIdle()
        assertEquals(RvSubDetailFailure.Delete(ErrorCode.Internal), vm.uiState.value.sheet.failed)

        honk.on("DELETE", "/api/v1/providers/s1", 200, HonkFixtures.body("providers/deleted"))
        vm.delete()
        assertNull(vm.uiState.value.sheet.failed)
        assertTrue(vm.uiState.value.gone)
        assertEquals("and is drawn as it was while it leaves", "Harbor Cloud", vm.uiState.value.sub?.name)
    }

    @Test
    fun `a delete honk answers not_found is gone already, with no banner`() = runTest {
        honk.on("DELETE", "/api/v1/providers/s1", 404, BUSY)
        val vm = detail()
        vm.delete()
        assertNull(vm.uiState.value.sheet.failed)
        assertTrue(vm.uiState.value.gone)
    }

    @Test
    fun `the page follows the settings' interval, shows 關閉 while auto-update is off, and nothing before the settings answer`() = runTest {
        val hours = MutableSharedFlow<Int?>()
        val vm = detail(hours = hours)
        assertEquals(RvAutoUpdate.Unread, vm.uiState.value.autoUpdate)
        hours.emit(12)
        assertEquals(RvAutoUpdate.Every(12), vm.uiState.value.autoUpdate)
        hours.emit(null)
        assertEquals(RvAutoUpdate.Off, vm.uiState.value.autoUpdate)
        hours.emit(24)
        assertEquals(RvAutoUpdate.Every(24), vm.uiState.value.autoUpdate)
    }

    /** The text the last replacement of the configuration file sent. */
    private fun put(): String = Json.parseToJsonElement(honk.calls.last { it.method == "PUT" }.body!!).jsonObject.getValue("content").jsonPrimitive.content

    /** `config/rv-sample` with its file's text changed by [change]. */
    private fun config(change: (String) -> String = { it }): String {
        val body = HonkFixtures.body("config/rv-sample")
        return body.replace(Json.encodeToString(String.serializer(), content(body)), Json.encodeToString(String.serializer(), change(content(body))))
    }

    /** The main file's text in a `GET /config` [body]. */
    private fun content(body: String): String =
        Json.parseToJsonElement(body).jsonObject.getValue("sources").jsonArray[0].jsonObject.getValue("content").jsonPrimitive.content

    /** The sample's providers with their JSON changed by [change]. */
    private fun providers(change: (String) -> String) = change(HonkFixtures.body("providers/rv-sample"))

    private companion object {
        val BUSY = HonkFixtures.body("errors/temporarily-unavailable")
    }
}
