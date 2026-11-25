package dev.doona.android.feature.settings

import dev.doona.android.engine.AppPolicy
import dev.doona.android.engine.ConfigAssets
import dev.doona.android.engine.Engine
import dev.doona.android.engine.EngineException
import dev.doona.android.engine.ErrorCode
import dev.doona.android.engine.PerApp
import dev.doona.android.engine.Status
import dev.doona.android.engine.fake.FakeEngine
import dev.doona.android.feature.model.RvAppPolicy
import dev.doona.android.feature.model.RvGeoUpdate
import dev.doona.android.feature.model.RvLanguage
import dev.doona.android.feature.model.RvPerApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {
    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.collected(vm: SettingsViewModel): SettingsViewModel {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        return vm
    }

    @Test
    fun `the switches go to the store and the page shows the store's values`() = runTest {
        val store = FakeSettingsStore(updateHours = 6, tileAdded = false)
        val vm = collected(SettingsViewModel(FakeEngine(), store))
        val shown = vm.uiState.value.settings
        assertEquals(6, shown.updateHours)
        assertFalse(shown.tileAdded)

        vm.onChange(shown.copy(notifyOnDrop = false))
        assertFalse(store.notifyOnDrop.value)
        vm.onChange(vm.uiState.value.settings.copy(autoUpdate = false))
        assertFalse(store.autoUpdate.value)
        assertFalse(vm.uiState.value.settings.notifyOnDrop)
        assertFalse(vm.uiState.value.settings.autoUpdate)
        assertEquals("the interval was left as it was", 6, store.updateHours.value)
    }

    @Test
    fun `依 App's row carries the engine's policy, and none before the policy is read`() = runTest {
        val engine = FakeEngine()
        val vm = collected(SettingsViewModel(engine, FakeSettingsStore()))
        assertEquals(RvPerApp(RvAppPolicy.All, emptySet()), vm.uiState.value.settings.perApp)
        assertTrue(vm.uiState.value.perAppLoaded)

        engine.setPerApp(PerApp(AppPolicy.OnlyListed, setOf("a", "b", "c")))
        assertEquals(RvPerApp(RvAppPolicy.Only, setOf("a", "b", "c")), vm.uiState.value.settings.perApp)
        engine.setPerApp(PerApp(AppPolicy.ExceptListed, setOf("a")))
        assertEquals(RvPerApp(RvAppPolicy.Except, setOf("a")), vm.uiState.value.settings.perApp)

        engine.perAppLoaded.value = false
        assertFalse(vm.uiState.value.perAppLoaded)
    }

    @Test
    fun `each lastResult the core reports is the row's state`() {
        val expected = mapOf(
            "checking" to RvGeoUpdate.Updating,
            "downloading" to RvGeoUpdate.Updating,
            "verifying" to RvGeoUpdate.Updating,
            "up_to_date" to RvGeoUpdate.Latest,
            "ready_on_next_start" to RvGeoUpdate.Updated,
            "activated" to RvGeoUpdate.Updated,
            "failed" to RvGeoUpdate.Failed,
            "idle" to RvGeoUpdate.Idle,
            "something_new" to RvGeoUpdate.Idle,
        )
        for ((word, state) in expected) assertEquals(word, state, rvGeoUpdate(word))
    }

    @Test
    fun `the row's date is the core's release, else the APK's copy, at noon UTC`() {
        fun date(ms: Long) = Instant.ofEpochMilli(ms).toString()
        assertEquals("2026-11-23T12:00:00Z", date(rvGeoData(null, "2026-11-23", connected = false).dateEpochMs))
        val builtin = ConfigAssets("builtin", null, "idle", null, false)
        assertEquals("2026-11-23T12:00:00Z", date(rvGeoData(builtin, "2026-11-23", connected = true).dateEpochMs))
        val download = ConfigAssets("download", "2026-11-30", "activated", null, false)
        val row = rvGeoData(download, "2026-11-23", connected = true)
        assertEquals("2026-11-30T12:00:00Z", date(row.dateEpochMs))
        assertEquals(RvGeoUpdate.Updated, row.update)
        assertTrue(row.connected)
    }

    @Test
    fun `an update shows Updating at once and follows the core every poll until it ends`() = runTest {
        val engine = GeoEngine(
            started = Result.success(assets("checking")),
            reads = listOf(assets("idle"), assets("downloading"), assets("verifying"), assets("ready_on_next_start")),
        )
        val vm = collected(SettingsViewModel(engine, FakeSettingsStore(), poll = 1.seconds))
        assertEquals(RvGeoUpdate.Idle, vm.uiState.value.geoData.update)
        assertTrue(vm.uiState.value.geoData.connected)

        vm.updateGeo()
        assertEquals(RvGeoUpdate.Updating, vm.uiState.value.geoData.update)
        assertEquals("the first read follows the start at once", 2, engine.read)
        advanceTimeBy(1.seconds)
        runCurrent()
        assertEquals(3, engine.read)
        assertEquals(RvGeoUpdate.Updating, vm.uiState.value.geoData.update)
        advanceTimeBy(1.seconds)
        runCurrent()
        assertEquals(RvGeoUpdate.Updated, vm.uiState.value.geoData.update)
        advanceTimeBy(5.seconds)
        runCurrent()
        assertEquals("no read after the update ended", 4, engine.read)
    }

    @Test
    fun `an update the core refuses shows the failure on the row, and 重試 asks again`() = runTest {
        val engine = GeoEngine(started = Result.failure(EngineException(ErrorCode.FetchFailed)), reads = listOf(assets("idle")))
        val vm = collected(SettingsViewModel(engine, FakeSettingsStore()))
        vm.updateGeo()
        assertEquals(RvGeoUpdate.Failed, vm.uiState.value.geoData.update)
        assertEquals("no read follows a refused start", 1, engine.read)

        engine.started = Result.success(assets("up_to_date"))
        engine.reads = listOf(assets("up_to_date"))
        vm.updateGeo()
        assertEquals(RvGeoUpdate.Latest, vm.uiState.value.geoData.update)
    }

    @Test
    fun `a busy core shows where it stands, not a failure`() = runTest {
        val engine = GeoEngine(started = Result.failure(EngineException(ErrorCode.Busy)), reads = listOf(assets("idle"), assets("ready_on_next_start")))
        val vm = collected(SettingsViewModel(engine, FakeSettingsStore()))
        vm.updateGeo()
        assertEquals(RvGeoUpdate.Updated, vm.uiState.value.geoData.update)
    }

    @Test
    fun `a page opened during an update follows it`() = runTest {
        val engine = GeoEngine(started = Result.success(assets("checking")), reads = listOf(assets("downloading"), assets("downloading"), assets("up_to_date")))
        val vm = collected(SettingsViewModel(engine, FakeSettingsStore(), poll = 1.seconds))
        assertEquals(RvGeoUpdate.Updating, vm.uiState.value.geoData.update)
        advanceTimeBy(1.seconds)
        runCurrent()
        assertEquals(RvGeoUpdate.Latest, vm.uiState.value.geoData.update)
    }

    @Test
    fun `the row is not connected while the VPN is off`() = runTest {
        val vm = collected(SettingsViewModel(FakeEngine(status = Status.Stopped), FakeSettingsStore()))
        assertFalse(vm.uiState.value.geoData.connected)
    }

    @Test
    fun `a failed tile request opens the sheet, which the page closes`() = runTest {
        val vm = collected(SettingsViewModel(FakeEngine(), FakeSettingsStore(tileAdded = false)))
        vm.tileAnswer(TileAnswer.Kept)
        assertFalse(vm.uiState.value.settings.tileSheet)
        vm.tileAnswer(TileAnswer.Failed)
        assertTrue(vm.uiState.value.settings.tileSheet)
        vm.onChange(vm.uiState.value.settings.copy(tileSheet = false))
        assertFalse(vm.uiState.value.settings.tileSheet)
    }

    @Test
    fun `the language is read again when the page shows again`() = runTest {
        val store = FakeSettingsStore(languages = "")
        val vm = collected(SettingsViewModel(FakeEngine(), store))
        assertEquals(RvLanguage.System, vm.uiState.value.settings.language)
        store.languages = "en-AU"
        assertEquals(RvLanguage.System, vm.uiState.value.settings.language)
        vm.onResume()
        assertEquals(RvLanguage.English, vm.uiState.value.settings.language)
    }

    @Test
    fun `更新間隔 opens on the store's interval and stores each choice`() = runTest {
        val store = FakeSettingsStore(updateHours = 24)
        val vm = IntervalViewModel(store)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        assertEquals(24, vm.uiState.value)
        vm.select(1)
        assertEquals(1, store.updateHours.value)
        assertEquals(1, vm.uiState.value)
    }

    private fun assets(lastResult: String) = ConfigAssets("builtin", null, lastResult, null, false)

    /** A running engine whose geo update starts as [started] and whose reads answer [reads] in turn, the last one again. */
    private class GeoEngine(var started: Result<ConfigAssets>, var reads: List<ConfigAssets>, private val fake: FakeEngine = FakeEngine(status = Status.Running(0))) :
        Engine by fake {
        var read = 0

        override suspend fun configAssets(): Result<ConfigAssets> = Result.success(reads[minOf(read++, reads.lastIndex)])

        override suspend fun updateConfigAssets(): Result<ConfigAssets> = started
    }
}
