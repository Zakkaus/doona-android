package dev.doona.android.feature.onboarding

import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import dev.doona.android.engine.Engine
import dev.doona.android.engine.EngineException
import dev.doona.android.engine.ErrorCode
import dev.doona.android.engine.FailureReason
import dev.doona.android.engine.Status
import dev.doona.android.engine.Subscription
import dev.doona.android.engine.fake.FakeEngine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
    private fun TestScope.collected(engine: Engine) = FirstRunViewModel(engine).also { vm ->
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
            override suspend fun addSubscription(url: String, name: String?) = answer.await()
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
            override suspend fun addSubscription(url: String, name: String?) = Result.failure<Subscription>(EngineException(ErrorCode.FetchFailed))
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
    fun `a start that failed gets the hint, and no other state does`() = runTest {
        val engine = FakeEngine(subscriptions = emptyList())
        val vm = collected(engine)
        assertFalse(vm.uiState.value.startFailed)
        engine.status.value = Status.Failed(FailureReason.StartFailed)
        assertTrue(vm.uiState.value.startFailed)
        engine.status.value = Status.Failed(FailureReason.CoreStopped)
        assertFalse(vm.uiState.value.startFailed)
        engine.status.value = Status.Stopped
        assertFalse(vm.uiState.value.startFailed)
    }
}
