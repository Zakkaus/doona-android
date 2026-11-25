package dev.doona.android.feature.onboarding

import dev.doona.android.engine.FailureReason
import dev.doona.android.engine.Group
import dev.doona.android.engine.GroupKind
import dev.doona.android.engine.Member
import dev.doona.android.engine.Status
import dev.doona.android.engine.fake.FakeEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FirstRunGateTest {
    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `the welcome page does not show before the engine is ready, not even in the first frame`() = runTest {
        val engine = FakeEngine(subscriptions = emptyList()).apply { ready.value = false }
        val vm = FirstRunViewModel(engine)
        assertEquals("the first value, before anyone collects", false, vm.welcome.value)
        val seen = mutableListOf<Boolean>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.welcome.collect { seen += it } }
        engine.ready.value = true
        assertEquals(listOf(false, true), seen)
    }

    @Test
    fun `saved subscriptions mean no first run`() = runTest {
        val vm = FirstRunViewModel(FakeEngine())
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.welcome.collect {} }
        assertEquals(false, vm.welcome.value)
    }

    @Test
    fun `the first subscription added ends the first run`() = runTest {
        val engine = FakeEngine(subscriptions = emptyList())
        val vm = FirstRunViewModel(engine)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.welcome.collect {} }
        assertEquals(true, vm.welcome.value)
        engine.addSubscription("https://sub.example/s", null, null)
        assertEquals(false, vm.welcome.value)
    }

    @Test
    fun `nodes written into the configuration keep 活動 with no subscription`() = runTest {
        val static = Member(id = "n-0000000000000001", name = "home")
        val engine = FakeEngine(groups = listOf(Group("g-Proxy", "Proxy", GroupKind.Selector, listOf(static), selectedId = static.id)), subscriptions = emptyList())
        val vm = FirstRunViewModel(engine)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.welcome.collect {} }
        assertEquals(false, vm.welcome.value)
    }

    @Test
    fun `no node known yet, or nodes there, means no welcome page`() {
        assertEquals(false, showsWelcome(ready = true, subscriptions = 0, anyNode = null, status = Status.Stopped))
        assertEquals(false, showsWelcome(ready = true, subscriptions = 0, anyNode = true, status = Status.Stopped))
    }

    @Test
    fun `a running tunnel keeps 活動 after the last subscription is removed`() {
        assertEquals(false, showsWelcome(ready = true, subscriptions = 0, anyNode = false, status = Status.Running(0)))
        assertEquals(false, showsWelcome(ready = true, subscriptions = 0, anyNode = false, status = Status.Starting))
        assertEquals(false, showsWelcome(ready = true, subscriptions = 0, anyNode = false, status = Status.Stopping))
        assertEquals(true, showsWelcome(ready = true, subscriptions = 0, anyNode = false, status = Status.Stopped))
        assertEquals(true, showsWelcome(ready = true, subscriptions = 0, anyNode = false, status = Status.Failed(FailureReason.StartFailed)))
        assertEquals(false, showsWelcome(ready = false, subscriptions = 0, anyNode = false, status = Status.Stopped))
    }
}
