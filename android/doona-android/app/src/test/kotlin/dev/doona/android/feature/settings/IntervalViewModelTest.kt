package dev.doona.android.feature.settings

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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** 更新間隔's 自訂 stores a typed count of hours within the store's range and refuses any other text. */
@OptIn(ExperimentalCoroutinesApi::class)
class IntervalViewModelTest {
    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.watched(store: FakeSettingsStore) = IntervalViewModel(store).also { vm -> backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} } }

    private val IntervalViewModel.state: IntervalUiState get() = checkNotNull(uiState.value) { "the store has not answered" }

    @Test
    fun `a typed count is stored and closes the sheet, and opening again shows it`() = runTest {
        val store = FakeSettingsStore(updateHours = 12)
        val vm = watched(store)
        vm.openCustom()
        assertTrue(vm.state.customOpen)
        assertEquals("a preset opens the field empty", "", vm.state.customText)

        vm.editCustom(" 36 ")
        vm.saveCustom()
        assertEquals(36, store.updateHours.value)
        assertEquals(36, vm.state.selected)
        assertFalse(vm.state.customOpen)

        vm.openCustom()
        assertEquals("36", vm.state.customText)
        assertFalse(vm.state.customInvalid)
    }

    @Test
    fun `a count out of range or no number keeps the sheet open with why, and typing clears it`() = runTest {
        val store = FakeSettingsStore(updateHours = 6)
        val vm = watched(store)
        vm.openCustom()
        for (typed in listOf("0", "169", "1.5", "two", "")) {
            vm.editCustom(typed)
            vm.saveCustom()
            assertTrue(typed, vm.state.customOpen)
            assertTrue(typed, vm.state.customInvalid)
            assertEquals(typed, 6, store.updateHours.value)
        }
        vm.editCustom("168")
        assertFalse(vm.state.customInvalid)
        vm.saveCustom()
        assertEquals(168, store.updateHours.value)
    }

    @Test
    fun `dismissing keeps the stored interval, and a preset is still a tap`() = runTest {
        val store = FakeSettingsStore(updateHours = 12)
        val vm = watched(store)
        vm.openCustom()
        vm.editCustom("48")
        vm.dismissCustom()
        assertFalse(vm.state.customOpen)
        assertEquals(12, store.updateHours.value)
        vm.select(24)
        assertEquals(24, vm.state.selected)
        assertEquals(1..168, vm.range)
    }
}
