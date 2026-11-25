package dev.doona.android.feature.settings

import dev.doona.android.designsystem.rv.RvFormat
import dev.doona.android.engine.AppPolicy
import dev.doona.android.engine.ConfigAssets
import dev.doona.android.engine.EffectiveConfig
import dev.doona.android.engine.Engine
import dev.doona.android.engine.EngineException
import dev.doona.android.engine.ErrorCode
import dev.doona.android.engine.LogLevel
import dev.doona.android.engine.PerApp
import dev.doona.android.engine.Status
import dev.doona.android.engine.fake.FakeEngine
import dev.doona.android.feature.model.RvAppPolicy
import dev.doona.android.feature.model.RvDnsUpstream
import dev.doona.android.feature.model.RvGeoInterval
import dev.doona.android.feature.model.RvGeoUpdate
import dev.doona.android.feature.model.RvLanguage
import dev.doona.android.feature.model.RvPerApp
import dev.doona.android.feature.model.RvSettingsState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.util.Locale
import java.util.TimeZone
import kotlin.time.Duration.Companion.minutes
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

    /** The page's state once the store has answered. */
    private val SettingsViewModel.settings: RvSettingsState get() = checkNotNull(uiState.value.settings) { "the store has not answered" }

    @Test
    fun `the page has no settings until the store answers, then the store's`() = runTest {
        val answered = MutableSharedFlow<Boolean>()
        val fake = FakeSettingsStore(notifyOnDrop = false, updateHours = 6)
        val store = object : SettingsStore by fake {
            override val notifyOnDrop: Flow<Boolean> = answered
        }
        val vm = collected(SettingsViewModel(FakeEngine(), store))
        assertNull("no switch or interval before the store answers", vm.uiState.value.settings)
        answered.emit(false)
        assertFalse(vm.settings.notifyOnDrop)
        assertEquals(6, vm.settings.updateHours)
    }

    @Test
    fun `the first settings the page draws hold the tile store's answer, so no row comes in later`() = runTest {
        val tile = MutableSharedFlow<Boolean>()
        val fake = FakeSettingsStore(tileAdded = true)
        val store = object : SettingsStore by fake {
            override val tileAdded: Flow<Boolean> = tile
        }
        val vm = collected(SettingsViewModel(FakeEngine(), store))
        assertNull("nothing drawn before the tile store answers", vm.uiState.value.settings)
        tile.emit(false)
        assertFalse("the first drawn state offers the tile row", vm.settings.tileAdded)
    }

    @Test
    fun `a second tap on the same switch from the same drawing writes the same value`() = runTest {
        val store = FakeSettingsStore()
        val vm = collected(SettingsViewModel(FakeEngine(), store))
        val drawn = vm.settings
        // Two taps on 中斷連線時通知 before the page shows the first: both say off, so it stays off.
        vm.onChange(drawn, drawn.copy(notifyOnDrop = false))
        vm.onChange(drawn, drawn.copy(notifyOnDrop = false))
        assertFalse(store.notifyOnDrop.value)
        assertFalse(vm.settings.notifyOnDrop)
        // A tap on the page that shows it off turns it on again.
        val next = vm.settings
        vm.onChange(next, next.copy(notifyOnDrop = true))
        assertTrue(store.notifyOnDrop.value)
        assertTrue("the other switch was left as it was", store.autoUpdate.value)
    }

    @Test
    fun `a page opened before the core runs reads the DNS servers and the geo data once it does`() = runTest {
        val fake = FakeEngine(status = Status.Stopped)
        var geoReads = 0
        val engine = object : Engine by fake {
            override suspend fun effectiveConfig(): Result<EffectiveConfig> =
                if (fake.status.value is Status.Running) fake.effectiveConfig() else Result.failure(EngineException(ErrorCode.Internal))

            override suspend fun configAssets(): Result<ConfigAssets> {
                geoReads++
                return Result.success(assets("up_to_date"))
            }
        }
        val vm = collected(SettingsViewModel(engine, FakeSettingsStore()))
        assertFalse(vm.uiState.value.dnsLoaded)
        assertEquals(1, geoReads)

        fake.status.value = Status.Running(sinceEpochMs = 0)
        assertTrue(vm.uiState.value.dnsLoaded)
        assertEquals(RvDnsUpstream.Cloudflare, vm.settings.dns)
        assertEquals(2, geoReads)
        assertEquals(RvGeoUpdate.Latest, vm.uiState.value.geoData.update)

        // Stopping reads nothing; the next start reads again.
        fake.status.value = Status.Stopped
        assertEquals(2, geoReads)
        fake.status.value = Status.Running(sinceEpochMs = 0)
        assertEquals(3, geoReads)
    }

    @Test
    fun `the switches go to the store and the page shows the store's values`() = runTest {
        val store = FakeSettingsStore(updateHours = 6, tileAdded = false)
        val vm = collected(SettingsViewModel(FakeEngine(), store))
        val shown = vm.settings
        assertEquals(6, shown.updateHours)
        assertFalse(shown.tileAdded)

        vm.onChange(shown, shown.copy(notifyOnDrop = false))
        assertFalse(store.notifyOnDrop.value)
        val next = vm.settings
        vm.onChange(next, next.copy(autoUpdate = false))
        assertFalse(store.autoUpdate.value)
        assertFalse(vm.settings.notifyOnDrop)
        assertFalse(vm.settings.autoUpdate)
        assertEquals("the interval was left as it was", 6, store.updateHours.value)
    }

    @Test
    fun `two quick turns write only the switch each turned`() = runTest {
        val store = FakeSettingsStore()
        val vm = collected(SettingsViewModel(FakeEngine(), store))
        val drawn = vm.settings
        vm.onChange(drawn, drawn.copy(notifyOnDrop = false))
        assertFalse(vm.settings.notifyOnDrop)
        // The second turn comes from the same drawing, before the page showed the first one.
        vm.onChange(drawn, drawn.copy(autoUpdate = false))
        assertFalse("the first turn stands", store.notifyOnDrop.value)
        assertFalse(store.autoUpdate.value)
    }

    @Test
    fun `the DNS row reads the servers the core reports, and again when the page shows again`() = runTest {
        val engine = FakeEngine()
        val vm = collected(SettingsViewModel(engine, FakeSettingsStore()))
        assertTrue(vm.uiState.value.dnsLoaded)
        assertEquals("the core's default, 1.1.1.1:53 then 8.8.8.8:53", RvDnsUpstream.Cloudflare, vm.settings.dns)

        engine.setDnsUpstreams(listOf("9.9.9.9:53"))
        assertEquals(RvDnsUpstream.Cloudflare, vm.settings.dns)
        vm.onResume()
        assertEquals(RvDnsUpstream.Quad9, vm.settings.dns)

        engine.setDnsUpstreams(listOf("10.0.0.1:53"))
        vm.onResume()
        assertNull("servers no preset names are 自訂", vm.settings.dns)
        assertTrue(vm.uiState.value.dnsLoaded)
    }

    @Test
    fun `the DNS row has no value until the core's servers are read`() = runTest {
        val engine = object : Engine by FakeEngine() {
            override suspend fun effectiveConfig(): Result<EffectiveConfig> = Result.failure(EngineException(ErrorCode.Internal))
        }
        val vm = collected(SettingsViewModel(engine, FakeSettingsStore()))
        assertFalse(vm.uiState.value.dnsLoaded)
    }

    @Test
    fun `the DNS row's preset is the first server's, on port 53`() {
        assertEquals(RvDnsUpstream.Cloudflare, rvDnsUpstream(listOf("1.1.1.1:53", "8.8.8.8:53")))
        assertEquals(RvDnsUpstream.Google, rvDnsUpstream(listOf("8.8.8.8:53", "1.1.1.1:53")))
        assertEquals(RvDnsUpstream.AliDns, rvDnsUpstream(listOf("223.5.5.5")))
        assertNull(rvDnsUpstream(listOf("1.1.1.1:5353")))
        assertNull(rvDnsUpstream(listOf("[2606:4700:4700::1111]:53")))
        assertNull(rvDnsUpstream(emptyList()))
    }

    @Test
    fun `依 App's row carries the engine's policy, and none before the policy is read`() = runTest {
        val engine = FakeEngine()
        val vm = collected(SettingsViewModel(engine, FakeSettingsStore()))
        assertEquals(RvPerApp(RvAppPolicy.All, emptySet()), vm.settings.perApp)
        assertTrue(vm.uiState.value.perAppLoaded)

        engine.setPerApp(PerApp(AppPolicy.OnlyListed, setOf("a", "b", "c")))
        assertEquals(RvPerApp(RvAppPolicy.Only, setOf("a", "b", "c")), vm.settings.perApp)
        engine.setPerApp(PerApp(AppPolicy.ExceptListed, setOf("a")))
        assertEquals(RvPerApp(RvAppPolicy.Except, setOf("a")), vm.settings.perApp)

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
    fun `the row's date is the core's release, else the APK's copy`() {
        val day = LocalDate.of(2026, 11, 23)
        assertEquals(day, rvGeoData(null, "2026-11-23", connected = false).date)
        val builtin = ConfigAssets("builtin", null, "idle", null, false)
        assertEquals(day, rvGeoData(builtin, "2026-11-23", connected = true).date)
        val download = ConfigAssets("download", "2026-11-30", "activated", null, false)
        val row = rvGeoData(download, "2026-11-23", connected = true)
        assertEquals(LocalDate.of(2026, 11, 30), row.date)
        assertEquals(RvGeoUpdate.Updated, row.update)
        assertTrue(row.connected)
        val odd = ConfigAssets("download", "20261130", "idle", null, false)
        assertEquals("a release that is no date falls back", day, rvGeoData(odd, "2026-11-23", connected = true).date)
    }

    @Test
    fun `the release day reads the same at UTC+14 and UTC-12`() {
        val zone = TimeZone.getDefault()
        try {
            for (id in listOf("Pacific/Kiritimati", "Etc/GMT+12", "UTC")) {
                TimeZone.setDefault(TimeZone.getTimeZone(id))
                val date = checkNotNull(rvGeoData(null, "2026-11-23", connected = true).date)
                assertEquals(id, "11/23/26", RvFormat(Locale.US).date(date))
            }
        } finally {
            TimeZone.setDefault(zone)
        }
    }

    @Test
    fun `no date at all when neither the core's release nor the APK's is a date, rather than ending the page`() {
        val odd = ConfigAssets("download", "20261130", "idle", null, false)
        assertNull(rvGeoData(odd, "unknown", connected = true).date)
        assertNull(rvGeoData(null, "", connected = false).date)
    }

    @Test
    fun `the update check finds a newer release by its version number`() {
        assertEquals(RvUpdate.Available("0.2.0"), rvUpdate("0.1.0", "v0.2.0"))
        assertEquals(RvUpdate.Available("0.1.10"), rvUpdate("0.1.9", "0.1.10"))
        assertEquals(RvUpdate.Available("1.0"), rvUpdate("0.9.3-debug", "1.0"))
        assertEquals(RvUpdate.Latest, rvUpdate("0.2.0", "v0.2.0"))
        assertEquals(RvUpdate.Latest, rvUpdate("0.2.0", "v0.2"))
        assertEquals("an older release on GitHub", RvUpdate.Latest, rvUpdate("0.3.0", "v0.2.9"))
        assertEquals("nothing published yet", RvUpdate.Latest, rvUpdate("0.1.0", null))
        assertEquals("a tag with no version", RvUpdate.Failed, rvUpdate("0.1.0", "nightly"))
        assertEquals("an app with no version name", RvUpdate.Failed, rvUpdate("", "v0.2.0"))
    }

    @Test
    fun `the update check shows Checking while it asks, then the answer, and a retry asks again`() = runTest {
        val answer = CompletableDeferred<Result<String?>>()
        var asked = 0
        val releases = ReleaseCheck {
            asked++
            if (asked == 1) answer.await() else Result.success("v0.2.0")
        }
        val vm = collected(SettingsViewModel(FakeEngine(), FakeSettingsStore(), releases))
        assertEquals(RvUpdate.Idle, vm.uiState.value.update)

        vm.checkUpdate("0.1.0")
        assertEquals(RvUpdate.Checking, vm.uiState.value.update)
        vm.checkUpdate("0.1.0")
        assertEquals("a tap while it asks is the same check", 1, asked)
        answer.complete(Result.failure(java.io.IOException("offline")))
        runCurrent()
        assertEquals(RvUpdate.Failed, vm.uiState.value.update)

        vm.checkUpdate("0.1.0")
        runCurrent()
        assertEquals(RvUpdate.Available("0.2.0"), vm.uiState.value.update)
        assertEquals(2, asked)
    }

    @Test
    fun `nothing newer on GitHub is Latest`() = runTest {
        val vm = collected(SettingsViewModel(FakeEngine(), FakeSettingsStore(), ReleaseCheck { Result.success("v0.1.0") }))
        vm.checkUpdate("0.1.0")
        runCurrent()
        assertEquals(RvUpdate.Latest, vm.uiState.value.update)
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
    fun `the row stays Updating until the core's first read, whatever it held before`() = runTest {
        val engine = GeoEngine(started = Result.failure(EngineException(ErrorCode.Busy)), reads = listOf(assets("up_to_date")))
        val vm = collected(SettingsViewModel(engine, FakeSettingsStore(), poll = 1.seconds))
        assertEquals(RvGeoUpdate.Latest, vm.uiState.value.geoData.update)
        engine.gate = CompletableDeferred()
        engine.reads = listOf(assets("downloading"))
        vm.updateGeo()
        assertEquals("not the Latest read before the update", RvGeoUpdate.Updating, vm.uiState.value.geoData.update)
        engine.gate!!.complete(Unit)
        runCurrent()
        assertEquals(RvGeoUpdate.Updating, vm.uiState.value.geoData.update)
    }

    @Test
    fun `an update the core never ends stops being followed and shows the failure`() = runTest {
        val engine = GeoEngine(started = Result.success(assets("checking")), reads = listOf(assets("idle"), assets("downloading")))
        val vm = collected(SettingsViewModel(engine, FakeSettingsStore(), poll = 1.seconds, followFor = 1.minutes))
        vm.updateGeo()
        advanceTimeBy(59.seconds)
        runCurrent()
        assertEquals(RvGeoUpdate.Updating, vm.uiState.value.geoData.update)
        advanceTimeBy(2.seconds)
        runCurrent()
        assertEquals(RvGeoUpdate.Failed, vm.uiState.value.geoData.update)
        val reads = engine.read
        advanceTimeBy(10.seconds)
        runCurrent()
        assertEquals("no read after it gave up", reads, engine.read)
    }

    @Test
    fun `the row is not connected while the VPN is off`() = runTest {
        val vm = collected(SettingsViewModel(FakeEngine(status = Status.Stopped), FakeSettingsStore()))
        assertFalse(vm.uiState.value.geoData.connected)
    }

    @Test
    fun `自動更新's row names the stored GeoIP_GeoSite interval, 每週 at first`() = runTest {
        val store = FakeSettingsStore()
        val vm = collected(SettingsViewModel(FakeEngine(), store))
        assertEquals(RvGeoInterval.Weekly, vm.settings.geoInterval)
        store.setGeoInterval(RvGeoInterval.Off)
        assertEquals(RvGeoInterval.Off, vm.settings.geoInterval)
        // A switch turned on the page after it does not write the interval back.
        val drawn = vm.settings
        vm.onChange(drawn, drawn.copy(notifyOnDrop = !drawn.notifyOnDrop))
        assertEquals(RvGeoInterval.Off, store.geoInterval.value)
    }

    @Test
    fun `日誌等級's row names the core's level, 資訊 at first, and follows it`() = runTest {
        val core = MutableStateFlow(LogLevel.Info)
        val levels = object : LogLevels {
            override val level: StateFlow<LogLevel> = core

            override suspend fun set(level: LogLevel): Result<Unit> = Result.success(Unit).also { core.value = level }
        }
        val vm = SettingsViewModel(FakeEngine(), FakeSettingsStore(), levels = levels)
        assertEquals("before the page collects", RvLogLevel.Info, vm.uiState.value.logLevel)
        collected(vm)
        core.value = LogLevel.Debug
        assertEquals(RvLogLevel.Debug, vm.uiState.value.logLevel)
        // 除錯's hour ends: the controller returns to 資訊 by itself.
        core.value = LogLevel.Info
        assertEquals(RvLogLevel.Info, vm.uiState.value.logLevel)
        core.value = LogLevel.Warn
        assertEquals(RvLogLevel.Warn, vm.uiState.value.logLevel)
    }

    @Test
    fun `a failed tile request opens the sheet, which the page closes`() = runTest {
        val vm = collected(SettingsViewModel(FakeEngine(), FakeSettingsStore(tileAdded = false)))
        vm.tileAnswer(TileAnswer.Kept)
        assertFalse(vm.settings.tileSheet)
        vm.tileAnswer(TileAnswer.Failed)
        assertTrue(vm.settings.tileSheet)
        val drawn = vm.settings
        vm.onChange(drawn, drawn.copy(tileSheet = false))
        assertFalse(vm.settings.tileSheet)
    }

    @Test
    fun `the language is read again when the page shows again`() = runTest {
        val store = FakeSettingsStore(languages = "")
        val vm = collected(SettingsViewModel(FakeEngine(), store))
        assertEquals(RvLanguage.System, vm.settings.language)
        store.languages = "en-AU"
        assertEquals(RvLanguage.System, vm.settings.language)
        vm.onResume()
        assertEquals(RvLanguage.English, vm.settings.language)
    }

    @Test
    fun `更新間隔 opens on the store's interval and stores each choice`() = runTest {
        val store = FakeSettingsStore(updateHours = 24)
        val vm = IntervalViewModel(store)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        assertEquals(24, vm.uiState.value?.selected)
        assertEquals(listOf(1, 6, 12, 24), vm.choices)
        vm.select(1)
        assertEquals(1, store.updateHours.value)
        assertEquals(1, vm.uiState.value?.selected)
    }

    private fun assets(lastResult: String) = ConfigAssets("builtin", null, lastResult, null, false)

    /** A running engine whose geo update starts as [started] and whose reads answer [reads] in turn, the last one again. */
    private class GeoEngine(var started: Result<ConfigAssets>, var reads: List<ConfigAssets>, private val fake: FakeEngine = FakeEngine(status = Status.Running(0))) :
        Engine by fake {
        var read = 0

        /** Set, each read waits for it. */
        var gate: CompletableDeferred<Unit>? = null

        override suspend fun configAssets(): Result<ConfigAssets> {
            gate?.await()
            return Result.success(reads[minOf(read++, reads.lastIndex)])
        }

        override suspend fun updateConfigAssets(): Result<ConfigAssets> = started
    }
}
