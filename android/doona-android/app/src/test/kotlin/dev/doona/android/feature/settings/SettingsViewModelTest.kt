package dev.doona.android.feature.settings

import dev.doona.android.engine.AppPolicy
import dev.doona.android.engine.PerApp
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
class SettingsViewModelTest {
    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `shows the policy the engine saves and follows a change`() = runTest {
        val engine = FakeEngine()
        val vm = SettingsViewModel(engine)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        assertEquals(AppPolicy.All, vm.uiState.value.appPolicy)

        engine.setPerApp(PerApp(AppPolicy.OnlyListed, setOf("com.android.chrome")))
        assertEquals(AppPolicy.OnlyListed, vm.uiState.value.appPolicy)
    }
}
