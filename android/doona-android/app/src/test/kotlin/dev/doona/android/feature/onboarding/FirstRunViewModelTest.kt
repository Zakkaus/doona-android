package dev.doona.android.feature.onboarding

import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import dev.doona.android.engine.Engine
import dev.doona.android.engine.EngineException
import dev.doona.android.engine.ErrorCode
import dev.doona.android.engine.FailureReason
import dev.doona.android.engine.Status
import dev.doona.android.engine.Subscription
import dev.doona.android.engine.fake.FakeEngine
import dev.doona.android.feature.subscriptions.SubscriptionImports
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
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
class FirstRunViewModelTest {
    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    /** `WhileSubscribed` only follows the engine while someone collects, as the screen does. */
    private fun TestScope.collected(engine: Engine) = FirstRunViewModel(engine, SubscriptionImports(engine, CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler)))).also { vm ->
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
    }

    @Test
    fun `is not ready until the engine has loaded, so saved subscriptions are not taken for none`() = runTest {
        val engine = FakeEngine(subscriptions = emptyList()).apply { ready.value = false }
        val vm = collected(engine)
        assertFalse(vm.uiState.value.ready)
        engine.ready.value = true
        assertTrue(vm.uiState.value.ready)
        assertFalse(vm.uiState.value.hasSubscriptions)
    }

    @Test
    fun `a saved subscription means this is not a first run`() = runTest {
        assertTrue(collected(FakeEngine()).uiState.value.hasSubscriptions)
    }

    @Test
    fun `the first subscription added ends the first run`() = runTest {
        val engine = FakeEngine(subscriptions = emptyList())
        val vm = collected(engine)
        vm.fields.url.setTextAndPlaceCursorAtEnd("https://example.invalid/x")
        vm.onAction(FirstRunAction.Add)
        assertTrue(vm.uiState.value.hasSubscriptions)
        assertEquals("https://example.invalid/x", engine.subscriptions.value.single().url)
        assertEquals("", vm.fields.url.text.toString())
    }

    @Test
    fun `an empty address is named and not sent`() = runTest {
        val engine = FakeEngine(subscriptions = emptyList())
        val vm = collected(engine)
        vm.onAction(FirstRunAction.Add)
        assertTrue(vm.uiState.value.form.attempted)
        assertFalse(vm.uiState.value.hasSubscriptions)
        assertTrue(engine.subscriptions.value.isEmpty())
    }

    @Test
    fun `adding shows its busy state`() = runTest {
        val answer = CompletableDeferred<Result<Subscription>>()
        val engine = object : Engine by FakeEngine(subscriptions = emptyList()) {
            override suspend fun addSubscription(url: String, name: String?, userAgent: String?) = answer.await()
        }
        val vm = collected(engine)
        vm.fields.url.setTextAndPlaceCursorAtEnd("https://example.invalid/x")
        vm.onAction(FirstRunAction.Add)
        assertTrue(vm.uiState.value.form.busy)
        answer.complete(Result.failure(EngineException(ErrorCode.FetchFailed)))
        assertFalse(vm.uiState.value.form.busy)
    }

    @Test
    fun `a failed add keeps the address and shows the code until dismissed`() = runTest {
        val engine = object : Engine by FakeEngine(subscriptions = emptyList()) {
            override suspend fun addSubscription(url: String, name: String?, userAgent: String?) = Result.failure<Subscription>(EngineException(ErrorCode.FetchFailed))
        }
        val vm = collected(engine)
        vm.fields.url.setTextAndPlaceCursorAtEnd("https://example.invalid/x")
        vm.onAction(FirstRunAction.Add)
        assertEquals(ErrorCode.FetchFailed, vm.uiState.value.form.error)
        assertFalse(vm.uiState.value.hasSubscriptions)
        assertEquals("https://example.invalid/x", vm.fields.url.text.toString())
        vm.onAction(FirstRunAction.DismissError)
        assertNull(vm.uiState.value.form.error)
    }

    @Test
    fun `a start the core refused for want of a node gets the hint`() = runTest {
        val engine = FakeEngine(subscriptions = emptyList())
        val vm = collected(engine)
        engine.status.value = Status.Failed(FailureReason.StartFailed, ErrorCode.NoNodes)
        assertTrue(vm.uiState.value.noNodes)
        assertNull(vm.uiState.value.failure)
    }

    @Test
    fun `any other failed start keeps its own message and gets no hint`() = runTest {
        val engine = FakeEngine(subscriptions = emptyList())
        val vm = collected(engine)
        val others = listOf(
            Status.Failed(FailureReason.StartFailed) to FailureReason.StartFailed,
            Status.Failed(FailureReason.StartFailed, ErrorCode.Internal) to FailureReason.StartFailed,
            Status.Failed(FailureReason.StartFailed, ErrorCode.Busy) to FailureReason.StartFailed,
            Status.Failed(FailureReason.CoreStopped) to FailureReason.CoreStopped,
            Status.Failed(FailureReason.IncompatibleCore) to FailureReason.IncompatibleCore,
            Status.Failed(FailureReason.NoAllowedApps) to FailureReason.NoAllowedApps,
        )
        others.forEach { (status, reason) ->
            engine.status.value = status
            assertFalse("$status", vm.uiState.value.noNodes)
            assertEquals("$status", reason, vm.uiState.value.failure)
        }
        engine.status.value = Status.Stopped
        assertFalse(vm.uiState.value.noNodes)
        assertNull(vm.uiState.value.failure)
    }

    @Test
    fun `the first-run page stands only while no subscription exists and no tunnel runs`() = runTest {
        val engine = FakeEngine(subscriptions = emptyList())
        val vm = collected(engine)
        val shown = mapOf(
            Status.Stopped to true,
            Status.Failed(FailureReason.StartFailed) to true,
            Status.Failed(FailureReason.StartFailed, ErrorCode.NoNodes) to true,
            Status.Starting to false,
            Status.Running(sinceEpochMs = 0) to false,
            Status.Stopping to false,
        )
        shown.forEach { (status, expected) ->
            engine.status.value = status
            assertEquals("$status", expected, vm.uiState.value.showsFirstRun)
        }
    }

    @Test
    fun `a saved subscription never shows the first-run page`() = runTest {
        val engine = FakeEngine()
        val vm = collected(engine)
        listOf(Status.Stopped, Status.Failed(FailureReason.StartFailed), Status.Running(sinceEpochMs = 0)).forEach {
            engine.status.value = it
            assertFalse("$it", vm.uiState.value.showsFirstRun)
        }
    }

    @Test
    fun `removing the last subscription while a tunnel runs keeps the activity screen`() = runTest {
        val engine = FakeEngine(status = Status.Running(sinceEpochMs = 0))
        val vm = collected(engine)
        assertFalse(vm.uiState.value.showsFirstRun)
        engine.subscriptions.value = emptyList()
        assertFalse(vm.uiState.value.hasSubscriptions)
        assertFalse(vm.uiState.value.showsFirstRun)
        engine.status.value = Status.Stopped
        assertTrue(vm.uiState.value.showsFirstRun)
    }
}
