package dev.doona.android.feature.subscriptions

import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import dev.doona.android.engine.Engine
import dev.doona.android.engine.EngineException
import dev.doona.android.engine.ErrorCode
import dev.doona.android.engine.Subscription
import dev.doona.android.engine.fake.FakeEngine
import dev.doona.android.feature.onboarding.FirstRunViewModel
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
class SubscriptionsViewModelTest {
    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private val now = 1_000_000L

    /** The holder every page shares, in a scope of its own: it outlives the view models. */
    private fun TestScope.imports(engine: Engine) = SubscriptionImports(engine, CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler)))

    /** `WhileSubscribed` only follows the engine while someone collects, as the screen does. */
    private fun TestScope.collected(engine: Engine, imports: SubscriptionImports = imports(engine)) =
        SubscriptionsViewModel(engine, imports) { now }.also { vm ->
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        }

    private fun sub(id: String, error: String? = null, expiresAt: Long? = null) =
        Subscription(id, "name-$id", "https://example.invalid/$id", nodes = 3, updatedAtEpochMs = 0, error = error, expiresAtEpochMs = expiresAt)

    private fun engineException(code: ErrorCode = ErrorCode.FetchFailed) = EngineException(code)

    private fun SubscriptionsViewModel.type(url: String, name: String = "") {
        fields.url.setTextAndPlaceCursorAtEnd(url)
        fields.name.setTextAndPlaceCursorAtEnd(name)
    }

    /** An engine that logs the updates and removals it is asked for, and answers them like [FakeEngine]. */
    private class Recording(val base: FakeEngine = FakeEngine()) : Engine by base {
        val updated = mutableListOf<String>()
        val removed = mutableListOf<String>()

        override suspend fun updateSubscription(id: String): Result<Subscription> {
            updated += id
            return base.updateSubscription(id)
        }

        override suspend fun removeSubscription(id: String): Result<Unit> {
            removed += id
            return base.removeSubscription(id)
        }
    }

    @Test
    fun `lists the subscriptions with their error code and expiry`() = runTest {
        val subs = listOf(sub("a"), sub("b", error = "FetchFailed", expiresAt = now - 1), sub("c", error = "from-the-future", expiresAt = now + 1))
        val items = collected(FakeEngine(subscriptions = subs)).uiState.value.items
        assertEquals(listOf(null, ErrorCode.FetchFailed, ErrorCode.Internal), items.map { it.problem })
        assertEquals(listOf(false, true, false), items.map { it.expired })
        assertTrue(items.none { it.updating })
    }

    @Test
    fun `is not ready until the engine has loaded`() = runTest {
        val engine = FakeEngine().apply { ready.value = false }
        val vm = collected(engine)
        assertFalse(vm.uiState.value.ready)
        engine.ready.value = true
        assertTrue(vm.uiState.value.ready)
    }

    @Test
    fun `update shows its busy state until the engine answers, and asks for it once`() = runTest {
        val answer = CompletableDeferred<Result<Subscription>>()
        val recording = Recording()
        val engine = object : Engine by recording {
            override suspend fun updateSubscription(id: String): Result<Subscription> {
                recording.updated += id
                return answer.await()
            }
        }
        val vm = collected(engine)
        vm.onAction(SubscriptionsAction.Update("s1"))
        assertTrue(vm.uiState.value.items.single().updating)
        vm.onAction(SubscriptionsAction.Update("s1"))
        assertEquals(listOf("s1"), recording.updated)
        answer.complete(Result.success(sub("s1")))
        assertFalse(vm.uiState.value.items.single().updating)
        assertEquals(listOf("s1"), recording.updated)
        assertNull(vm.uiState.value.error)
    }

    @Test
    fun `a failed update shows its code until dismissed and a later success clears it`() = runTest {
        var fail = true
        val fake = FakeEngine()
        val engine = object : Engine by fake {
            override suspend fun updateSubscription(id: String) =
                if (fail) Result.failure(engineException(ErrorCode.Busy)) else fake.updateSubscription(id)
        }
        val vm = collected(engine)
        vm.onAction(SubscriptionsAction.Update("s1"))
        assertEquals(ErrorCode.Busy, vm.uiState.value.error)
        assertFalse(vm.uiState.value.items.single().updating)
        vm.onAction(SubscriptionsAction.DismissError)
        assertNull(vm.uiState.value.error)
        vm.onAction(SubscriptionsAction.Update("s1"))
        fail = false
        vm.onAction(SubscriptionsAction.Update("s1"))
        assertNull(vm.uiState.value.error)
    }

    @Test
    fun `remove asks first and only the confirmation removes`() = runTest {
        val engine = Recording(FakeEngine(subscriptions = listOf(sub("a"), sub("b"))))
        val vm = collected(engine)
        vm.onAction(SubscriptionsAction.AskRemove("a"))
        assertEquals("a", vm.uiState.value.removing?.id)
        assertEquals(emptyList<String>(), engine.removed)
        vm.onAction(SubscriptionsAction.CancelRemove)
        assertNull(vm.uiState.value.removing)
        assertEquals(emptyList<String>(), engine.removed)
        vm.onAction(SubscriptionsAction.AskRemove("a"))
        vm.onAction(SubscriptionsAction.ConfirmRemove)
        assertEquals(listOf("a"), engine.removed)
        assertNull(vm.uiState.value.removing)
        assertEquals(listOf("b"), vm.uiState.value.items.map { it.subscription.id })
        assertNull(vm.uiState.value.error)
    }

    @Test
    fun `a failed removal keeps the subscription and shows its code`() = runTest {
        val engine = object : Engine by FakeEngine() {
            override suspend fun removeSubscription(id: String) = Result.failure<Unit>(engineException(ErrorCode.NotFound))
        }
        val vm = collected(engine)
        vm.onAction(SubscriptionsAction.AskRemove("s1"))
        vm.onAction(SubscriptionsAction.ConfirmRemove)
        assertEquals(ErrorCode.NotFound, vm.uiState.value.error)
        assertEquals(1, vm.uiState.value.items.size)
        assertNull(vm.uiState.value.removing)
    }

    @Test
    fun `confirming with nothing asked removes nothing`() = runTest {
        val engine = Recording()
        collected(engine).onAction(SubscriptionsAction.ConfirmRemove)
        assertEquals(emptyList<String>(), engine.removed)
        assertEquals(1, engine.base.subscriptions.value.size)
    }

    @Test
    fun `the form opens and closes, and closing empties it`() = runTest {
        val vm = collected(FakeEngine())
        vm.onAction(SubscriptionsAction.OpenForm)
        assertTrue(vm.uiState.value.adding)
        vm.type("https://example.invalid/x", "mine")
        vm.onAction(SubscriptionsAction.CloseForm)
        assertFalse(vm.uiState.value.adding)
        assertEquals("", vm.fields.url.text.toString())
        assertEquals("", vm.fields.name.text.toString())
    }

    @Test
    fun `an address that is not http or https is refused before the engine is asked`() = runTest {
        var asked = false
        val engine = object : Engine by FakeEngine() {
            override suspend fun addSubscription(url: String, name: String?): Result<Subscription> {
                asked = true
                return Result.failure(engineException())
            }
        }
        val vm = collected(engine)
        vm.onAction(SubscriptionsAction.OpenForm)
        vm.type("ftp://example.invalid/x")
        vm.onAction(SubscriptionsAction.Add)
        assertFalse(asked)
        assertTrue(vm.uiState.value.form.attempted)
        assertFalse(vm.uiState.value.form.busy)
        assertEquals("ftp://example.invalid/x", vm.fields.url.text.toString())
    }

    @Test
    fun `a good address is added, trimmed and with its name, and the form closes empty`() = runTest {
        val engine = FakeEngine(subscriptions = emptyList())
        val vm = collected(engine)
        vm.onAction(SubscriptionsAction.OpenForm)
        vm.type("  https://example.invalid/x\n", " mine ")
        vm.onAction(SubscriptionsAction.Add)
        val added = engine.subscriptions.value.single()
        assertEquals("https://example.invalid/x", added.url)
        assertEquals("mine", added.name)
        assertFalse(vm.uiState.value.adding)
        assertEquals(AddFormState(), vm.uiState.value.form)
        assertEquals("", vm.fields.url.text.toString())
    }

    @Test
    fun `a blank name is not sent`() = runTest {
        var sent: String? = "unset"
        val fake = FakeEngine()
        val engine = object : Engine by fake {
            override suspend fun addSubscription(url: String, name: String?): Result<Subscription> {
                sent = name
                return fake.addSubscription(url, name)
            }
        }
        val vm = collected(engine)
        vm.type("https://example.invalid/x", "   ")
        vm.onAction(SubscriptionsAction.Add)
        assertNull(sent)
    }

    @Test
    fun `adding shows its busy state, ignores a second tap, and keeps the form open`() = runTest {
        val answer = CompletableDeferred<Result<Subscription>>()
        var calls = 0
        val engine = object : Engine by FakeEngine() {
            override suspend fun addSubscription(url: String, name: String?): Result<Subscription> {
                calls++
                return answer.await()
            }
        }
        val vm = collected(engine)
        vm.onAction(SubscriptionsAction.OpenForm)
        vm.type("https://example.invalid/x")
        vm.onAction(SubscriptionsAction.Add)
        assertTrue(vm.uiState.value.form.busy)
        vm.onAction(SubscriptionsAction.Add)
        assertEquals(1, calls)
        assertTrue(vm.uiState.value.adding)
        answer.complete(Result.success(sub("new")))
        assertFalse(vm.uiState.value.form.busy)
        assertFalse(vm.uiState.value.adding)
    }

    @Test
    fun `a failed add keeps what was typed and shows the code until dismissed`() = runTest {
        val engine = object : Engine by FakeEngine() {
            override suspend fun addSubscription(url: String, name: String?) = Result.failure<Subscription>(engineException(ErrorCode.ParseFailed))
        }
        val vm = collected(engine)
        vm.onAction(SubscriptionsAction.OpenForm)
        vm.type("https://example.invalid/x", "mine")
        vm.onAction(SubscriptionsAction.Add)
        val form = vm.uiState.value.form
        assertEquals(ErrorCode.ParseFailed, form.error)
        assertFalse(form.busy)
        assertTrue(vm.uiState.value.adding)
        assertEquals("https://example.invalid/x", vm.fields.url.text.toString())
        assertEquals("mine", vm.fields.name.text.toString())
        vm.onAction(SubscriptionsAction.DismissFormError)
        assertNull(vm.uiState.value.form.error)
    }

    /** A page left and entered again is a new view model over the same holder, collected while it is shown. */
    private fun TestScope.page(store: ViewModelStore, engine: Engine, imports: SubscriptionImports) =
        ViewModelProvider(store, SubscriptionsViewModel.factory(engine, imports))[SubscriptionsViewModel::class.java].also { vm ->
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        }

    private class SlowAdd(val base: FakeEngine = FakeEngine(subscriptions = emptyList())) : Engine by base {
        var calls = 0
        var answer = CompletableDeferred<Result<Subscription>>()

        override suspend fun addSubscription(url: String, name: String?): Result<Subscription> {
            calls++
            val result = answer.await()
            if (result.isSuccess) base.addSubscription(url, name)
            return result
        }
    }

    @Test
    fun `an import left running blocks a second one from the page entered again, and commits once`() = runTest {
        val engine = SlowAdd()
        val imports = imports(engine)
        val first = ViewModelStore()
        val before = page(first, engine, imports)
        before.onAction(SubscriptionsAction.OpenForm)
        before.type("https://example.invalid/x")
        before.onAction(SubscriptionsAction.Add)
        first.clear()

        val second = ViewModelStore()
        val after = page(second, engine, imports)
        assertTrue("the form comes back with the import still running", after.uiState.value.adding)
        assertTrue(after.uiState.value.form.busy)
        after.type("https://example.invalid/x")
        after.onAction(SubscriptionsAction.Add)
        assertEquals(1, engine.calls)

        engine.answer.complete(Result.success(sub("s1")))
        assertEquals(1, engine.base.subscriptions.value.size)
        assertFalse(after.uiState.value.form.busy)
        assertFalse("the form closes when the import ends", after.uiState.value.adding)
        assertEquals("", after.fields.url.text.toString())
        second.clear()
    }

    @Test
    fun `an import that failed while the page was left shows its error when the page is entered again`() = runTest {
        val engine = SlowAdd()
        val imports = imports(engine)
        val first = ViewModelStore()
        val before = page(first, engine, imports)
        before.type("https://example.invalid/x")
        before.onAction(SubscriptionsAction.Add)
        first.clear()
        engine.answer.complete(Result.failure(engineException(ErrorCode.FetchFailed)))

        val second = ViewModelStore()
        val after = page(second, engine, imports)
        assertEquals(ErrorCode.FetchFailed, after.uiState.value.form.error)
        assertTrue(after.uiState.value.adding)
        after.onAction(SubscriptionsAction.DismissFormError)
        assertNull(after.uiState.value.form.error)
        second.clear()
    }

    @Test
    fun `an import error with the form closed is shown on the page, and dismissing it clears the holder`() = runTest {
        val engine = SlowAdd()
        val imports = imports(engine)
        val vm = collected(engine, imports)
        vm.type("https://example.invalid/x")
        vm.onAction(SubscriptionsAction.Add)
        engine.answer.complete(Result.failure(engineException(ErrorCode.ParseFailed)))
        vm.onAction(SubscriptionsAction.CloseForm)
        assertEquals(ErrorCode.ParseFailed, vm.uiState.value.error)
        vm.onAction(SubscriptionsAction.DismissError)
        assertNull(vm.uiState.value.error)
        assertNull(imports.state.value.error)
    }

    @Test
    fun `a success from another form leaves this form's draft alone, and its own form is emptied`() = runTest {
        val engine = FakeEngine(subscriptions = emptyList())
        val imports = imports(engine)
        val onboarding = FirstRunViewModel(engine, imports)
        val page = collected(engine, imports)
        onboarding.fields.url.setTextAndPlaceCursorAtEnd("https://example.invalid/draft")
        onboarding.fields.name.setTextAndPlaceCursorAtEnd("draft")

        page.onAction(SubscriptionsAction.OpenForm)
        page.type("https://example.invalid/x", "mine")
        page.onAction(SubscriptionsAction.Add)

        assertEquals(listOf("https://example.invalid/x"), engine.subscriptions.value.map { it.url })
        assertEquals("https://example.invalid/draft", onboarding.fields.url.text.toString())
        assertEquals("draft", onboarding.fields.name.text.toString())
        assertEquals("", page.fields.url.text.toString())
        assertFalse(page.uiState.value.adding)
    }

    @Test
    fun `an import that succeeded while the page was left leaves the page entered again plain`() = runTest {
        val engine = SlowAdd()
        val imports = imports(engine)
        val first = ViewModelStore()
        val before = page(first, engine, imports)
        before.type("https://example.invalid/x")
        before.onAction(SubscriptionsAction.Add)
        first.clear()
        engine.answer.complete(Result.success(sub("s1")))

        val second = ViewModelStore()
        val after = page(second, engine, imports)
        assertFalse(after.uiState.value.adding)
        assertEquals(AddFormState(), after.uiState.value.form)
        assertEquals(1, engine.base.subscriptions.value.size)
        second.clear()
    }
}
