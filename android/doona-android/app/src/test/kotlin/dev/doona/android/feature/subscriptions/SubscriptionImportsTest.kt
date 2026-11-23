package dev.doona.android.feature.subscriptions

import dev.doona.android.engine.Engine
import dev.doona.android.engine.EngineException
import dev.doona.android.engine.ErrorCode
import dev.doona.android.engine.Subscription
import dev.doona.android.engine.fake.FakeEngine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SubscriptionImportsTest {
    /** The holder's scope: eager, and separate from any screen's. */
    private fun TestScope.appScope() = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))

    private class Gated(val base: FakeEngine = FakeEngine(subscriptions = emptyList())) : Engine by base {
        val calls = mutableListOf<Pair<String, String?>>()
        var answer = CompletableDeferred<Result<Subscription>>()

        override suspend fun addSubscription(url: String, name: String?, userAgent: String?): Result<Subscription> {
            calls += url to name
            return answer.await()
        }
    }

    private fun sub(id: String) = Subscription(id, id, "https://example.invalid/$id", nodes = 1, updatedAtEpochMs = 0)

    @Test
    fun `an import in flight is busy, and a second one is not started`() = runTest {
        val engine = Gated()
        val imports = SubscriptionImports(engine, appScope())
        imports.start("https://example.invalid/a", "a")
        imports.start("https://example.invalid/b", "b")
        assertTrue(imports.state.value.busy)
        assertEquals(listOf<Pair<String, String?>>("https://example.invalid/a" to "a"), engine.calls)
        engine.answer.complete(Result.success(sub("s1")))
        assertFalse(imports.state.value.busy)
        assertEquals(1, imports.state.value.imported)
        assertNull(imports.state.value.error)
    }

    @Test
    fun `start hands over the id of the import it began, and does nothing while one runs`() = runTest {
        val engine = Gated()
        val imports = SubscriptionImports(engine, appScope())
        val ids = mutableListOf<Long>()
        assertTrue(imports.start("https://example.invalid/a", null) { ids += it })
        assertFalse(imports.start("https://example.invalid/b", null) { ids += it })
        engine.answer.complete(Result.success(sub("s1")))
        engine.answer = CompletableDeferred()
        assertTrue(imports.start("https://example.invalid/c", null) { ids += it })
        assertEquals(listOf(1L, 2L), ids)
        assertEquals(2L, imports.state.value.lastId)
    }

    @Test
    fun `a failed import keeps its code until dismissed, and the next one starts clean`() = runTest {
        val engine = Gated()
        val imports = SubscriptionImports(engine, appScope())
        imports.start("https://example.invalid/a", null)
        engine.answer.complete(Result.failure(EngineException(ErrorCode.FetchFailed)))
        assertEquals(ImportState(busy = false, error = ErrorCode.FetchFailed, imported = 0, lastId = 1), imports.state.value)
        imports.dismissError()
        assertNull(imports.state.value.error)
        engine.answer = CompletableDeferred()
        imports.start("https://example.invalid/a", null)
        assertEquals(2, engine.calls.size)
        assertTrue(imports.state.value.busy)
    }

    @Test
    fun `starting an import drops the error of the last one`() = runTest {
        val engine = Gated()
        val imports = SubscriptionImports(engine, appScope())
        imports.start("https://example.invalid/a", null)
        engine.answer.complete(Result.failure(EngineException(ErrorCode.ParseFailed)))
        engine.answer = CompletableDeferred()
        imports.start("https://example.invalid/a", null)
        assertEquals(ImportState(busy = true, error = null, imported = 0, lastId = 2), imports.state.value)
    }

    @Test
    fun `an error that is not an engine error is internal`() = runTest {
        val engine = object : Engine by FakeEngine() {
            override suspend fun addSubscription(url: String, name: String?, userAgent: String?): Result<Subscription> = Result.failure(IllegalStateException("boom"))
        }
        val imports = SubscriptionImports(engine, appScope())
        imports.start("https://example.invalid/a", null)
        assertEquals(ErrorCode.Internal, imports.state.value.error)
    }
}
