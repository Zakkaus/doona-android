package dev.doona.android.vpn.honk

import dev.doona.android.engine.EngineException
import dev.doona.android.engine.ErrorCode
import dev.doona.android.vpn.FakeHonk
import dev.doona.android.vpn.HonkFixtures
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class NativeConfigTest {
    private val honk = FakeHonk().apply {
        on("GET", "/api/v1/config", 200, HonkFixtures.body("config/editable"))
        on("PUT", "/api/v1/config/sources/source-main", 202, HonkFixtures.body("config-replace/queued"))
        on("GET", "/api/v1/operations/op-config-01", 200, HonkFixtures.body("operations/reload-complete"))
    }

    @Test
    fun aReplacementNamesTheTextItReplacesAndWaitsForTheReload() = runTest {
        val config = NativeConfig(honk)
        val source = config.config().sources.single()
        assertEquals("routing {\n  fallback: direct\n}\n", source.content)
        config.replace(source, "routing {\n  fallback: block\n}\n")
        val put = honk.calls.single { it.method == "PUT" }
        assertEquals("{\"content\":\"routing {\\n  fallback: block\\n}\\n\"}", put.body)
        assertEquals(mapOf("If-Match" to "\"d1f62f00c6da9ec33956e66b8cc3b4670f164556fc12453193904af23451dec1\""), put.headers)
        assertEquals("GET /api/v1/operations/op-config-01", honk.calls.last().let { "${it.method} ${it.path}" })
    }

    @Test
    fun aFileChangedSinceItWasReadIsBusyAndAReloadThatFailsIsRefused() = runTest {
        val config = NativeConfig(honk)
        val source = config.config().sources.single()
        honk.on("PUT", "/api/v1/config/sources/source-main", 412, HonkFixtures.body("errors/stale-revision"))
        assertEquals(ErrorCode.Busy, failure { config.replace(source, "") })
        honk.on("PUT", "/api/v1/config/sources/source-main", 202, HonkFixtures.body("config-replace/queued"))
        honk.on("GET", "/api/v1/operations/op-config-01", 200, HonkFixtures.body("operations/reload-running").replace("\"running\"", "\"failed\""))
        assertEquals(ErrorCode.InvalidArgument, failure { config.replace(source, "") })
    }

    private suspend fun failure(call: suspend () -> Unit): ErrorCode {
        try {
            call()
        } catch (error: EngineException) {
            return error.code
        }
        throw AssertionError("no failure")
    }
}
