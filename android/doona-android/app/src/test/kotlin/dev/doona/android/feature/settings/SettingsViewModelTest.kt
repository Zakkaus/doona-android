package dev.doona.android.feature.settings

import dev.doona.android.designsystem.rv.RvFormat
import dev.doona.android.engine.AppPolicy
import dev.doona.android.engine.Engine
import dev.doona.android.engine.PerApp
import dev.doona.android.engine.fake.FakeEngine
import dev.doona.android.feature.model.RvAppPolicy
import dev.doona.android.feature.model.RvDnsUpstream
import dev.doona.android.feature.model.RvGeoData
import dev.doona.android.feature.model.RvGeoInterval
import dev.doona.android.feature.model.RvGeoUpdate
import dev.doona.android.feature.model.RvLanguage
import dev.doona.android.feature.model.RvPerApp
import dev.doona.android.feature.model.RvSettingsState
import dev.doona.android.honk.HonkFixtures
import dev.doona.android.honk.NativeSettings
import dev.doona.android.honk.SettingsHonk
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
import java.time.ZoneId
import java.util.Locale
import java.util.TimeZone
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {
    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.collected(vm: SettingsViewModel): SettingsViewModel {
        vm.onResume()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        return vm
    }

/** A collected [SettingsViewModel] over [honk], which runs on the test's clock, as every other argument the page's defaults. */
    private fun TestScope.vm(
        engine: Engine = FakeEngine(),
        store: SettingsStore = FakeSettingsStore(),
        releases: ReleaseCheck = ReleaseCheck { Result.success(null) },
        levels: LogLevels? = null,
        honk: SettingsHonk = SettingsHonk.running(),
        poll: Duration = 1.seconds,
        followFor: Duration = 10.minutes,
    ): SettingsViewModel {
        val io = UnconfinedTestDispatcher(testScheduler)
        val native = NativeSettings(honk) { ZoneId.of("UTC") }
        val vm = if (levels == null) {
            SettingsViewModel(engine, native, store, releases, poll = poll, followFor = followFor, io = io)
        } else {
            SettingsViewModel(engine, native, store, releases, levels, poll, followFor, io)
        }
        return collected(vm)
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
        val vm = vm(FakeEngine(), store)
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
        val vm = vm(FakeEngine(), store)
        assertNull("nothing drawn before the tile store answers", vm.uiState.value.settings)
        tile.emit(false)
        assertFalse("the first drawn state offers the tile row", vm.settings.tileAdded)
    }

    @Test
    fun `a second tap on the same switch from the same drawing writes the same value`() = runTest {
        val store = FakeSettingsStore()
        val vm = vm(FakeEngine(), store)
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
    fun `the switches go to the store and the page shows the store's values`() = runTest {
        val store = FakeSettingsStore(updateHours = 6, tileAdded = false)
        val vm = vm(FakeEngine(), store)
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
        val vm = vm(FakeEngine(), store)
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
        val honk = SettingsHonk.running().now("GET", "/api/v1/config", 200, HonkFixtures.body("config/rv-sample"))
            .now("GET", "/api/v1/dns/rules", 200, HonkFixtures.body("dns-rules/rv-sample"))
        val vm = vm(honk = honk)
        assertTrue(vm.uiState.value.dnsLoaded)
        assertEquals(RvDnsUpstream.Cloudflare, vm.settings.dns)

        honk.now("GET", "/api/v1/dns/rules", 200, HonkFixtures.body("dns-rules/rv-sample").replace("cloudflare", "alidns"))
        assertEquals(RvDnsUpstream.Cloudflare, vm.settings.dns)
        vm.onResume()
        assertEquals(RvDnsUpstream.Google, vm.settings.dns)

        honk.now("GET", "/api/v1/config", 200, HonkFixtures.body("config/rv-sample").replace("8.8.8.8", "10.0.0.1"))
        vm.onResume()
        assertNull("servers no preset names are 自訂", vm.settings.dns)
        assertTrue(vm.uiState.value.dnsLoaded)

        val rules = HonkFixtures.body("dns-rules/rv-sample")
        honk.now("GET", "/api/v1/dns/rules", 200, rules.replace("generation-42", "generation-43"))
        vm.onResume()
        assertFalse(vm.uiState.value.dnsLoaded)
        honk.now("GET", "/api/v1/dns/rules", 200, rules.replace("fallback: cloudflare", "fallback: asis").replace("\"upstream\": \"cloudflare\"", "\"upstream\": null").replace("\"action\": \"upstream\",\n      \"upstream\": null", "\"action\": \"asis\",\n      \"upstream\": null"))
        vm.onResume()
        assertFalse(vm.uiState.value.dnsLoaded)
    }

    @Test
    fun `the DNS row has no value until the core's servers are read`() = runTest {
        val vm = vm(honk = SettingsHonk())
        assertFalse(vm.uiState.value.dnsLoaded)
    }

    @Test
    fun `the DNS row's preset is the first server's, on port 53`() {
        assertEquals(RvDnsUpstream.Cloudflare, rvDnsUpstream("1.1.1.1:53"))
        assertEquals(RvDnsUpstream.Google, rvDnsUpstream("8.8.8.8:53"))
        assertEquals(RvDnsUpstream.AliDns, rvDnsUpstream("223.5.5.5"))
        assertNull(rvDnsUpstream("1.1.1.1:5353"))
        assertNull(rvDnsUpstream("[2606:4700:4700::1111]:53"))
        assertNull(rvDnsUpstream(null))
    }

    @Test
    fun `依 App's row carries the engine's policy, and none before the policy is read`() = runTest {
        val engine = FakeEngine()
        val vm = vm(engine, FakeSettingsStore())
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
        val vm = vm(FakeEngine(), FakeSettingsStore(), releases)
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
        val vm = vm(FakeEngine(), FakeSettingsStore(), ReleaseCheck { Result.success("v0.1.0") })
        vm.checkUpdate("0.1.0")
        runCurrent()
        assertEquals(RvUpdate.Latest, vm.uiState.value.update)
    }

    @Test
    fun `without honk the row shows the APK date and no schedule`() = runTest {
        val vm = vm(honk = SettingsHonk())
        assertEquals(RvGeoData(LocalDate.of(2026, 11, 23), connected = false), vm.uiState.value.geoData)
        assertNull(vm.settings.geoInterval)
    }

    @Test
    fun `the row shows honk's date and schedule, read again when the page shows again`() = runTest {
        val honk = SettingsHonk.running()
        val vm = vm(honk = honk)
        assertEquals(RvGeoData(LocalDate.of(2026, 8, 14)), vm.uiState.value.geoData)
        assertEquals("the contract's 24 hours", RvGeoInterval.Daily, vm.settings.geoInterval)

        honk.now("GET", "/api/v1/runtime/settings", 200, SettingsHonk.runtimeSettings(enabled = false))
            .now("GET", "/api/v1/geodata", 200, SettingsHonk.geoData(error = SettingsHonk.GEODATA_ERROR))
        vm.onResume()
        assertEquals(RvGeoInterval.Off, vm.settings.geoInterval)
        assertEquals(RvGeoUpdate.Failed, vm.uiState.value.geoData.update)
    }

    @Test
    fun `an update shows Updating at once and reads honk every poll until it reports the attempt`() = runTest {
        val replaced = SettingsHonk.geoData(checked = "2026-08-15T10:00:09Z", updated = "2026-08-15T10:00:08Z", modified = "2026-08-15T10:00:08Z")
        val honk = SettingsHonk.running().on("GET", "/api/v1/geodata", 200, SettingsHonk.GEODATA).on("GET", "/api/v1/geodata", 200, SettingsHonk.GEODATA)
            .on("GET", "/api/v1/geodata", 200, replaced)
        val vm = vm(honk = honk, poll = 1.seconds)
        assertEquals(RvGeoUpdate.Idle, vm.uiState.value.geoData.update)

        vm.updateGeo()
        assertEquals(RvGeoUpdate.Updating, vm.uiState.value.geoData.update)
        advanceTimeBy(1.seconds)
        runCurrent()
        assertEquals("the first read is still the attempt before", RvGeoUpdate.Updating, vm.uiState.value.geoData.update)
        advanceTimeBy(1.seconds)
        runCurrent()
        assertEquals(RvGeoData(LocalDate.of(2026, 8, 15), RvGeoUpdate.Updated), vm.uiState.value.geoData)
        val reads = honk.calls.size
        advanceTimeBy(5.seconds)
        runCurrent()
        assertEquals("no read after the update ended", reads, honk.calls.size)
    }

    @Test
    fun `an update honk refuses shows the failure on the row, and 重試 asks again`() = runTest {
        val honk = SettingsHonk.running().now("POST", "/api/v1/geodata/update", 500, "").on("POST", "/api/v1/geodata/update", 202, SettingsHonk.GEODATA_QUEUED)
        val vm = vm(honk = honk)
        vm.updateGeo()
        assertEquals(RvGeoUpdate.Failed, vm.uiState.value.geoData.update)

        honk.on("GET", "/api/v1/geodata", 200, SettingsHonk.geoData(checked = "2026-08-15T10:00:09Z"))
        vm.updateGeo()
        advanceTimeBy(1.seconds)
        runCurrent()
        assertEquals(RvGeoUpdate.Latest, vm.uiState.value.geoData.update)
    }

    @Test
    fun `an update honk never reports stops being followed and shows the failure`() = runTest {
        val honk = SettingsHonk.running()
        val vm = vm(honk = honk, poll = 1.seconds, followFor = 1.minutes)
        vm.updateGeo()
        advanceTimeBy(59.seconds)
        runCurrent()
        assertEquals(RvGeoUpdate.Updating, vm.uiState.value.geoData.update)
        advanceTimeBy(2.seconds)
        runCurrent()
        assertEquals(RvGeoUpdate.Failed, vm.uiState.value.geoData.update)
        val reads = honk.calls.size
        advanceTimeBy(10.seconds)
        runCurrent()
        assertEquals("no read after it gave up", reads, honk.calls.size)
    }

    @Test
    fun `自動更新 opens on honk's schedule and sends each choice, then reads it again`() = runTest {
        val honk = SettingsHonk.running()
        val vm = GeoIntervalViewModel(NativeSettings(honk), UnconfinedTestDispatcher(testScheduler))
        assertEquals(GeoIntervalState(RvGeoInterval.Daily), vm.state.value)

        honk.now("GET", "/api/v1/runtime/settings", 200, SettingsHonk.runtimeSettings(hours = 168))
        vm.select(RvGeoInterval.Weekly)
        assertEquals(GeoIntervalState(RvGeoInterval.Weekly), vm.state.value)
        assertEquals("PATCH", honk.calls[honk.calls.lastIndex - 1].method)
    }

    @Test
    fun `自動更新 shows no choice and 未連線 while honk does not answer`() = runTest {
        val vm = GeoIntervalViewModel(NativeSettings(SettingsHonk()), UnconfinedTestDispatcher(testScheduler))
        assertEquals(GeoIntervalState(null), vm.state.value)
        val settings = kotlinx.serialization.json.Json.parseToJsonElement(SettingsHonk.RUNTIME_SETTINGS) as kotlinx.serialization.json.JsonObject
        val unsupported = SettingsHonk().on("GET", "/api/v1/runtime/settings", 200, kotlinx.serialization.json.JsonObject(settings - "geodata").toString())
        assertEquals(GeoIntervalState(RvGeoInterval.Unavailable), GeoIntervalViewModel(NativeSettings(unsupported), UnconfinedTestDispatcher(testScheduler)).state.value)
    }

    @Test
    fun `the row's date is honk's, else the APK's copy, and no date when neither is one`() {
        val day = LocalDate.of(2026, 11, 23)
        assertEquals(RvGeoData(day, connected = false), rvGeoData(null, "2026-11-23"))
        assertEquals(RvGeoData(day), rvGeoData(RvGeoData(null), "2026-11-23"))
        assertEquals(RvGeoData(LocalDate.of(2026, 8, 14), RvGeoUpdate.Updated), rvGeoData(RvGeoData(LocalDate.of(2026, 8, 14), RvGeoUpdate.Updated), "2026-11-23"))
        assertNull(rvGeoData(null, "20261123").date)
        assertNull(rvGeoData(RvGeoData(null), "").date)
    }

    @Test
    fun `the APK's day reads the same at UTC+14 and UTC-12`() {
        val zone = TimeZone.getDefault()
        try {
            for (id in listOf("Pacific/Kiritimati", "Etc/GMT+12", "UTC")) {
                TimeZone.setDefault(TimeZone.getTimeZone(id))
                val date = checkNotNull(rvGeoData(null, "2026-11-23").date)
                assertEquals(id, "11/23/26", RvFormat(Locale.US).date(date))
            }
        } finally {
            TimeZone.setDefault(zone)
        }
    }

    @Test
    fun `日誌等級's row names the core's level, 資訊 at first, and follows it`() = runTest {
        val core = MutableStateFlow(RvLogLevel.Info)
        val levels = object : LogLevels {
            override val level: StateFlow<RvLogLevel> = core

            override suspend fun set(level: RvLogLevel): Result<Unit> = Result.success(Unit).also { core.value = level }
        }
        val vm = SettingsViewModel(FakeEngine(), NativeSettings(SettingsHonk()), FakeSettingsStore(), levels = levels, io = UnconfinedTestDispatcher(testScheduler))
        assertEquals("before the page collects", RvLogLevel.Info, vm.uiState.value.logLevel)
        collected(vm)
        core.value = RvLogLevel.Debug
        assertEquals(RvLogLevel.Debug, vm.uiState.value.logLevel)
        // 除錯's hour ends: the controller returns to 資訊 by itself.
        core.value = RvLogLevel.Info
        assertEquals(RvLogLevel.Info, vm.uiState.value.logLevel)
        core.value = RvLogLevel.Warn
        assertEquals(RvLogLevel.Warn, vm.uiState.value.logLevel)
    }

    @Test
    fun `a failed tile request opens the sheet, which the page closes`() = runTest {
        val vm = vm(FakeEngine(), FakeSettingsStore(tileAdded = false))
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
        val vm = vm(FakeEngine(), store)
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
}
