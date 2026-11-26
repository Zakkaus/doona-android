package dev.doona.android.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.doona.android.engine.Engine
import dev.doona.android.feature.model.RvAppearance
import dev.doona.android.feature.model.RvGeoData
import dev.doona.android.feature.model.RvGeoInterval
import dev.doona.android.feature.model.RvGeoUpdate
import dev.doona.android.feature.model.RvLanguage
import dev.doona.android.feature.model.RvSettingsState
import dev.doona.android.vpn.NativeSettings
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * What 設定 shows: the page's state ([settings], null until the store has answered, so the page never shows a switch or
 * an interval the user may not have set), whether the per-app policy and the DNS servers have been read ([perAppLoaded],
 * [dnsLoaded]; until then each row has no second line rather than a value the user may not have), the GeoIP/GeoSite
 * row, where 檢查更新 stands ([update]), and the level the core logs at ([logLevel], 日誌等級's row).
 */
internal data class SettingsUiState(
    val settings: RvSettingsState?,
    val perAppLoaded: Boolean,
    val dnsLoaded: Boolean,
    val geoData: RvGeoData,
    val update: RvUpdate = RvUpdate.Idle,
    val logLevel: RvLogLevel = RvLogLevel.Info,
)

/**
 * 設定 over the app's [store], [honk] and the [engine]: the switches set the store, 依 App's row reads the engine's
 * policy, the DNS row the servers the core reports in its effective config. The GeoIP/GeoSite row and 自動更新's row
 * read honk ([load]: at the start, when the page shows again, and after an update), and 更新 asks honk to update,
 * reading where it stands every [poll] until honk reports the attempt or [followFor] has passed, which the row then
 * shows as a failure (重試 asks again). honk answers only while it runs, so until it does the row shows the APK's date
 * and 未連線. 檢查更新 asks [releases] for the latest release when the user taps it, and only then. 日誌等級's row
 * follows the core's [levels], which 除錯's return to 資訊 changes by itself; a test that does not look at it gets a
 * core that stays at 資訊.
 */
internal class SettingsViewModel(
    private val engine: Engine,
    private val honk: NativeSettings,
    private val store: SettingsStore,
    private val releases: ReleaseCheck = ReleaseCheck { Result.success(null) },
    private val levels: LogLevels = InfoLevels,
    private val poll: Duration = 1.seconds,
    private val followFor: Duration = 10.minutes,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val local = MutableStateFlow(Local(language = RvLanguage.of(store.languageTags())))
    private var geoJob: Job? = null

    private val prefs = combine(store.notifyOnDrop, store.autoUpdate, store.updateHours, store.appearance, store.tileAdded, ::Prefs)

    val uiState: StateFlow<SettingsUiState> =
        combine(prefs, engine.settings, engine.perAppLoaded, local) { prefs, settings, loaded, local ->
            SettingsUiState(
                RvSettingsState(
                    notifyOnDrop = prefs.notifyOnDrop,
                    perApp = rvPerApp(settings.perApp),
                    autoUpdate = prefs.autoUpdate,
                    updateHours = prefs.updateHours,
                    appearance = prefs.appearance,
                    dns = local.dnsServers?.let(::rvDnsUpstream),
                    language = local.language,
                    tileAdded = prefs.tileAdded,
                    geoInterval = local.geoInterval ?: RvGeoInterval.Weekly,
                    tileSheet = local.tileSheet,
                ),
                perAppLoaded = loaded,
                dnsLoaded = local.dnsServers != null,
                geoData = geoData(local),
                update = local.update,
            )
        }.combine(levels.level) { state, level -> state.copy(logLevel = level) }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), initialState())

    init {
        load()
    }

    /**
     * The page's switches and the manual tile sheet: the field the user turned on the page [drawn] ([changed] differs
     * from it there) goes where it is kept. It is compared with what the page drew, not with the latest state, so a
     * second turn the page sends before it shows the first one does not write the first field back.
     */
    fun onChange(drawn: RvSettingsState, changed: RvSettingsState) {
        if (changed.notifyOnDrop != drawn.notifyOnDrop) store.setNotifyOnDrop(changed.notifyOnDrop)
        if (changed.autoUpdate != drawn.autoUpdate) store.setAutoUpdate(changed.autoUpdate)
        if (changed.tileSheet != drawn.tileSheet) local.update { it.copy(tileSheet = changed.tileSheet) }
    }

    /** The system's answer to 加到快速設定: an added tile hides the row (the store records it), a failed request opens the sheet. */
    fun tileAnswer(answer: TileAnswer) {
        if (answer == TileAnswer.Failed) local.update { it.copy(tileSheet = true) }
    }

    /**
     * The page shows again: the language may have changed in Android's settings meanwhile, the DNS servers on DNS's
     * page, and honk may have started, stopped or updated the data by itself.
     */
    fun onResume() {
        local.update { it.copy(language = RvLanguage.of(store.languageTags())) }
        load()
    }

    /** 更新 (or 重試) on the GeoIP/GeoSite row: honk downloads through the VPN, and the row follows it to the end. */
    fun updateGeo() {
        if (geoJob?.isActive == true) return
        geoJob = viewModelScope.launch {
            local.update { it.copy(geoAsked = RvGeoUpdate.Updating) }
            val updated = runCatching { withTimeout(followFor) { withContext(io) { honk.updateGeo(poll) } } }.getOrNull()
            local.update { it.copy(geo = updated ?: it.geo, geoAsked = RvGeoUpdate.Failed.takeIf { updated == null }) }
        }
    }

    /**
     * 檢查更新 (or its 重試) for the app's [version]: asks GitHub for the latest release and shows what it found; a tap
     * while it asks is the same check.
     *
     * Ruled, not open: the app asks GitHub itself. Decision 2026-11-23 21:35 had the row only open the releases page;
     * q-failform (2026-11-24 21:02) and the approved states (checking, 已是最新, a newer release, red 重試 on failure)
     * need an answer to show, so the in-app check stays. A newer release still opens the releases page
     * ([SettingsSystem.openReleases]); the app downloads nothing.
     */
    fun checkUpdate(version: String) {
        if (local.value.update == RvUpdate.Checking) return
        local.update { it.copy(update = RvUpdate.Checking) }
        viewModelScope.launch {
            val found = releases.latest().fold({ rvUpdate(version, it) }, { RvUpdate.Failed })
            local.update { it.copy(update = found) }
        }
    }

    /** Reads the DNS servers and honk's data and schedule; honk not answering shows as 未連線, with no data of its own. */
    private fun load() {
        viewModelScope.launch { readDns() }
        viewModelScope.launch {
            val read = runCatching { withContext(io) { honk.geoData() to honk.geoInterval() } }.getOrNull()
            // An update under way keeps its own state until it ends.
            if (geoJob?.isActive == true) return@launch
            local.update { it.copy(geo = read?.first, geoInterval = read?.second ?: it.geoInterval, geoAsked = null) }
        }
    }

    /** The DNS servers the core uses, from its effective config; a failed read leaves the row as it was. */
    private suspend fun readDns() {
        engine.effectiveConfig().onSuccess { config -> local.update { it.copy(dnsServers = config.dnsUpstreams) } }
    }

    private fun geoData(local: Local): RvGeoData {
        val data = rvGeoData(local.geo, store.shippedGeoDate)
        return local.geoAsked?.let { data.copy(update = it) } ?: data
    }

    private fun initialState() = SettingsUiState(
        settings = null,
        perAppLoaded = engine.perAppLoaded.value,
        dnsLoaded = false,
        geoData = geoData(local.value),
        logLevel = levels.level.value,
    )

    /** The store's values the page shows. */
    private data class Prefs(
        val notifyOnDrop: Boolean,
        val autoUpdate: Boolean,
        val updateHours: Int,
        val appearance: RvAppearance,
        val tileAdded: Boolean,
    )

    /**
     * What the ViewModel itself holds: the language as last read, the tile sheet, the core's DNS servers ([dnsServers],
     * null until read), honk's geo data as last read (null while honk does not answer), its schedule ([geoInterval],
     * null until honk first answers), the update's own outcome while it runs or after it failed ([geoAsked]), and
     * where 檢查更新 stands ([update]).
     */
    private data class Local(
        val language: RvLanguage,
        val tileSheet: Boolean = false,
        val dnsServers: List<String>? = null,
        val geo: RvGeoData? = null,
        val geoInterval: RvGeoInterval? = null,
        val geoAsked: RvGeoUpdate? = null,
        val update: RvUpdate = RvUpdate.Idle,
    )

    companion object {
        fun factory(engine: Engine, honk: NativeSettings, store: SettingsStore, releases: ReleaseCheck, levels: LogLevels): ViewModelProvider.Factory =
            viewModelFactory {
                initializer { SettingsViewModel(engine, honk, store, releases, levels = levels) }
            }
    }
}

/** A core that logs at 資訊 and takes no other level: the [SettingsViewModel] default for tests of other rows. */
private object InfoLevels : LogLevels {
    override val level: StateFlow<RvLogLevel> = MutableStateFlow(RvLogLevel.Info)

    override suspend fun set(level: RvLogLevel): Result<Unit> = Result.failure(UnsupportedOperationException("a fixed level"))
}
