package dev.doona.android.feature.localnetwork

import dev.doona.android.engine.Engine
import dev.doona.android.engine.EngineException
import dev.doona.android.engine.ErrorCode
import dev.doona.android.engine.Member
import dev.doona.android.engine.Subscription
import dev.doona.android.engine.fake.FakeEngine
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The screens' engine: an action that failed on the local network asks, and runs once more only after a yes. */
@OptIn(ExperimentalCoroutinesApi::class)
class LocalNetworkEngineTest {
    private var granted = false
    private val access = LocalNetworkAccess { granted }

    /** Fails each add and update with the next of [failures], then succeeds; counts the calls that reached it. */
    private class Scripted(private val fake: FakeEngine = FakeEngine()) : Engine by fake {
        val failures = ArrayDeque<ErrorCode>()
        var adds = 0
        var updates = 0
        var tests = 0

        override suspend fun addSubscription(url: String, name: String?): Result<Subscription> {
            adds++
            return failures.removeFirstOrNull()?.let { Result.failure(EngineException(it)) } ?: fake.addSubscription(url, name)
        }

        override suspend fun updateSubscription(id: String): Result<Subscription> {
            updates++
            return failures.removeFirstOrNull()?.let { Result.failure(EngineException(it)) } ?: fake.updateSubscription(id)
        }

        override suspend fun testLatency(groupId: String?): Result<Unit> {
            tests++
            return Result.success(Unit)
        }
    }

    /** The decorated engine, with its follow-ups in the test's background, so they end with the test. */
    private fun TestScope.engine(scripted: Scripted) = LocalNetworkEngine(scripted, access, backgroundScope)

    private fun Result<*>.code() = (exceptionOrNull() as? EngineException)?.code

    /** The user goes on from the reason and answers the system's dialog. */
    private fun answer(allowed: Boolean) {
        assertEquals(LocalNetworkPrompt.Ask, access.prompt.value)
        access.requesting()
        granted = allowed
        access.answered(allowed)
    }

    @Test
    fun aYesRunsTheActionOnceMore() = runTest(UnconfinedTestDispatcher()) {
        val scripted = Scripted().apply { failures += ErrorCode.LocalNetwork }
        val added = async { engine(scripted).addSubscription("http://192.168.8.161/sub") }
        answer(allowed = true)
        assertTrue(added.await().isSuccess)
        assertEquals(2, scripted.adds)
    }

    @Test
    fun theRetryRunsOnceEvenIfItFailsAgain() = runTest(UnconfinedTestDispatcher()) {
        val scripted = Scripted().apply { failures += listOf(ErrorCode.LocalNetwork, ErrorCode.LocalNetwork) }
        val updated = async { engine(scripted).updateSubscription("s1") }
        answer(allowed = true)
        assertEquals(ErrorCode.LocalNetwork, updated.await().code())
        assertEquals(2, scripted.updates)
        assertNull("allowed now: nothing more to ask", access.prompt.value)
    }

    @Test
    fun aNoKeepsTheFailureAndShowsTheWayToTheSettings() = runTest(UnconfinedTestDispatcher()) {
        val scripted = Scripted().apply { failures += listOf(ErrorCode.LocalNetwork, ErrorCode.LocalNetwork) }
        val engine = engine(scripted)
        val added = async { engine.addSubscription("http://192.168.8.161/sub") }
        answer(allowed = false)
        assertEquals(ErrorCode.LocalNetwork, added.await().code())
        assertEquals(1, scripted.adds)
        assertEquals(LocalNetworkPrompt.Denied, access.prompt.value)
        access.dismissed()
        // Never asked again in this process: the refusal shows at once and nothing runs twice.
        assertEquals(ErrorCode.LocalNetwork, engine.addSubscription("http://192.168.8.161/sub").code())
        assertEquals(2, scripted.adds)
        assertEquals(LocalNetworkPrompt.Denied, access.prompt.value)
    }

    @Test
    fun otherFailuresAndAnAllowedAccessAskNothing() = runTest(UnconfinedTestDispatcher()) {
        val scripted = Scripted().apply { failures += ErrorCode.FetchFailed }
        val engine = engine(scripted)
        assertEquals(ErrorCode.FetchFailed, engine.addSubscription("https://example.invalid/sub").code())
        assertNull(access.prompt.value)
        granted = true
        scripted.failures += ErrorCode.LocalNetwork
        assertEquals(ErrorCode.LocalNetwork, engine.addSubscription("http://192.168.8.161/sub").code())
        assertNull(access.prompt.value)
        assertEquals(2, scripted.adds)
    }

    @Test
    fun aLatencyTestWithANodeThatFailedOnTheLocalNetworkAsksThenTestsOnceMore() = runTest(UnconfinedTestDispatcher()) {
        val fake = FakeEngine()
        val scripted = Scripted(fake)
        val engine = engine(scripted)
        fake.groups.update { groups -> groups.map { it.copy(testing = true) } }
        assertTrue(engine.testLatency("g-Auto").isSuccess)
        assertNull("nothing is asked while the test runs", access.prompt.value)
        val failed = { m: Member -> if (m.id == "n-hk2") m.copy(failed = true, error = ErrorCode.LocalNetwork) else m }
        fake.groups.update { groups -> groups.map { g -> g.copy(testing = false, members = g.members.map(failed)) } }
        answer(allowed = true)
        assertEquals(2, scripted.tests)
    }

    @Test
    fun aLatencyTestWithoutSuchANodeAsksNothing() = runTest(UnconfinedTestDispatcher()) {
        val scripted = Scripted()
        assertTrue(engine(scripted).testLatency(null).isSuccess)
        assertNull(access.prompt.value)
        assertEquals(1, scripted.tests)
    }

    @Test
    fun theTunnelsWarningAsksAndAYesNeedsNothingMore() = runTest(UnconfinedTestDispatcher()) {
        val fake = FakeEngine()
        val scripted = Scripted(fake)
        engine(scripted).watch()
        assertNull(access.prompt.value)
        fake.warning.value = ErrorCode.LocalNetwork
        answer(allowed = true)
        assertNull(access.prompt.value)
        assertEquals(0, scripted.adds + scripted.updates + scripted.tests)
    }
}
