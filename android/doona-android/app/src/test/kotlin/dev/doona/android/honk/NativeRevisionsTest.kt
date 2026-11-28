package dev.doona.android.honk

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.doona.android.engine.EngineException
import dev.doona.android.engine.ErrorCode
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "zh-rTW")
class NativeRevisionsTest {
    private val honk = FakeHonk().apply {
        on("GET", "/api/v1/x-honk/config/revisions", 200, HonkFixtures.body("revisions/list"))
        on("POST", "/api/v1/x-honk/config/revisions/2/activate", 202, HonkFixtures.body("config-replace/queued"))
        on("GET", "/api/v1/operations/op-config-01", 200, HonkFixtures.body("operations/reload-complete"))
        on("GET", "/api/v1/config", 200, HonkFixtures.body("config/editable"))
    }

    @Test
    fun restoringActivatesTheNewestRevisionOlderThanTheActiveOne() = runTest {
        assertTrue(NativeRevisions(honk).restorePrevious())
        val post = honk.calls.single { it.method == "POST" }
        assertEquals("/api/v1/x-honk/config/revisions/2/activate", post.path)
        assertEquals("{}", post.body)
        assertEquals("GET /api/v1/operations/op-config-01", honk.calls.last().let { "${it.method} ${it.path}" })
    }

    @Test
    fun withNothingOlderThanTheActiveRevisionNothingIsActivated() = runTest {
        honk.on("GET", "/api/v1/x-honk/config/revisions", 200, HonkFixtures.body("revisions/list").replace("\"active\": 3", "\"active\": 1"))
        assertFalse(NativeRevisions(honk).restorePrevious())
        assertTrue(honk.calls.none { it.method == "POST" })
    }

    @Test
    fun anActivationHonkRejectsIsRefused() = runTest {
        honk.on("GET", "/api/v1/operations/op-config-01", 200, HonkFixtures.body("operations/reload-running").replace("\"running\"", "\"failed\""))
        val code = try {
            NativeRevisions(honk).activate(2)
            null
        } catch (error: EngineException) {
            error.code
        }
        assertEquals(ErrorCode.InvalidArgument, code)
    }

    @Test
    fun aFailedReloadNamesTheSourceAndLineInTheNotification() {
        honk.on("GET", "/api/v1/runtime", 200, HonkFixtures.body("runtime/runtime-reload-failed"))
        val failure = NativeRevisions(honk).lastFailure()
        assertEquals(ConfigFailure("config.dae", 1), failure)
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertEquals("目前設定無法使用", context.configUnusableTitle())
        assertEquals("config.dae 第 1 行有錯誤。點一下換回上一份能用的設定。", context.configUnusableText(failure))
        assertEquals("點一下換回上一份能用的設定。", context.configUnusableText(ConfigFailure(null, 1)))
    }

    @Test
    fun beforeTheFirstReloadSourcesAreUsableAndThereIsNoFailure() {
        val runtime = nativeJson.parseToJsonElement(HonkFixtures.body("runtime/runtime-example")).jsonObject
        honk.on("GET", "/api/v1/runtime", 200, JsonObject(runtime + ("last_reload" to JsonNull)).toString())
        assertFalse(NativeRouting(honk).sourcesRejected())
        assertNull(NativeRevisions(honk).lastFailure())
    }

    @Test
    fun aSucceededReloadIsNoFailure() {
        honk.on("GET", "/api/v1/runtime", 200, HonkFixtures.body("runtime/runtime-example"))
        assertNull(NativeRevisions(honk).lastFailure())
    }
}
