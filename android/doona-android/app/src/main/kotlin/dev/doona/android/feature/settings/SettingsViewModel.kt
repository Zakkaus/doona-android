package dev.doona.android.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.doona.android.engine.ConfigAssets
import dev.doona.android.engine.Engine
import dev.doona.android.engine.EngineException
import dev.doona.android.engine.ErrorCode
import dev.doona.android.engine.LogLevel
import dev.doona.android.engine.Status
import dev.doona.android.feature.model.RvAppearance
import dev.doona.android.feature.model.RvGeoData
import dev.doona.android.feature.model.RvGeoInterval
import dev.doona.android.feature.model.RvGeoUpdate
import dev.doona.android.feature.model.RvLanguage
import dev.doona.android.feature.model.RvSettingsState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
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
 * 設定 over the app's [store] and the [engine]: the switches set the store, 依 App's row reads the engine's policy, the
 * DNS row the servers the core reports in its effective config, and the GeoIP/GeoSite row reads the core's data and
 * runs its update, asking the core where it stands every [poll] until it ends or [followFor] has passed, which the row
 * then shows as a failure (重試 follows it again). The DNS servers and the geo data are read at the start, when the page
 * shows again, and when the core comes up: a page opened before the core runs fills in once it does. 檢查更新 asks
 * [releases] for the latest release when the user taps it, and only then. 日誌等級's row follows the core's [levels],
 * which 除錯's return to 資訊 changes by itself; a test that does not look at it gets a core that stays at 資訊.
 */
internal class SettingsViewModel(
    private val engine: Engine,
    private val store: SettingsStore,
    private val releases: ReleaseCheck = ReleaseCheck { Result.success(null) },
    private val levels: LogLevels = InfoLevels,
    private val poll: Duration = 1.seconds,
    private val followFor: Duration = 10.minutes,
) : ViewModel() {
    private val local = MutableStateFlow(Local(language = RvLanguage.of(store.languageTags())))
    private var geoJob: Job? = null

    private val prefs = combine(store.notifyOnDrop, store.autoUpdate, store.updateHours, store.appearance, combine(store.tileAdded, store.geoInterval, ::Pair)) {
            notify, auto, hours, appearance, (tile, geo) ->
        Prefs(notify, auto, hours, appearance, tile, geo)
    }

    val uiState: StateFlow<SettingsUiState> =
        combine(prefs, engine.settings, engine.perAppLoaded, engine.status, local) { prefs, settings, loaded, status, local ->
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
                    geoInterval = prefs.geoInterval,
                    tileSheet = local.tileSheet,
                ),
                perAppLoaded = loaded,
                dnsLoaded = local.dnsServers != null,
                geoData = geoData(local, status is Status.Running),
                update = local.update,
            )
        }.combine(levels.level) { state, level -> state.copy(logLevel = rvLogLevel(level)) }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), initialState())

    init {
        viewModelScope.launch { readGeo() }
        viewModelScope.launch { readDns() }
        // The first value is the status at the start, which the reads above cover; each later turn to Running reads again.
        engine.status.map { it is Status.Running }.distinctUntilChanged().drop(1).filter { it }.onEach {
            readDns()
            readGeo()
        }.launchIn(viewModelScope)
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

    /** The page shows again: the language may have changed in Android's settings meanwhile, and the DNS servers on DNS's page. */
    fun onResume() {
        local.update { it.copy(language = RvLanguage.of(store.languageTags())) }
        viewModelScope.launch { readDns() }
    }

    /** 更新 (or 重試) on the GeoIP/GeoSite row: the core fetches through the VPN, and the row follows it to the end. */
    fun updateGeo() {
        if (geoJob?.isActive == true) return
        geoJob = viewModelScope.launch {
            local.update { it.copy(geoAsked = RvGeoUpdate.Updating) }
            val started = engine.updateConfigAssets()
            val failure = started.exceptionOrNull()
            // A busy core already holds newer data for its next start: the row shows that, not a failure.
            val busy = (failure as? EngineException)?.code == ErrorCode.Busy
            if (failure != null && !busy) {
                local.update { it.copy(geoAsked = RvGeoUpdate.Failed) }
                return@launch
            }
            // The row stays Updating until the core's first read says where the update stands.
            started.getOrNull()?.let { assets -> local.update { it.copy(geo = assets) } }
            followGeo()
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

    private suspend fun readGeo() {
        engine.configAssets().onSuccess { assets ->
            local.update { it.copy(geo = assets) }
            if (rvGeoUpdate(assets.lastResult) == RvGeoUpdate.Updating && geoJob?.isActive != true) {
                geoJob = viewModelScope.launch { followGeo() }
            }
        }
    }

    /** Follows the core's update to its end; a failed read, or [followFor] gone by with the core still at it, is a failure. */
    private suspend fun followGeo() {
        if (withTimeoutOrNull(followFor) { readGeoUntilEnded() } != true) local.update { it.copy(geoAsked = RvGeoUpdate.Failed) }
    }

    /** Reads the core's geo data every [poll] while it updates: true once the update ended, false when a read fails. */
    private suspend fun readGeoUntilEnded(): Boolean {
        while (true) {
            val assets = engine.configAssets().getOrNull() ?: return false
            local.update { it.copy(geo = assets, geoAsked = null) }
            if (rvGeoUpdate(assets.lastResult) != RvGeoUpdate.Updating) return true
            delay(poll)
        }
    }

    /** The DNS servers the core uses, from its effective config; a failed read leaves the row as it was. */
    private suspend fun readDns() {
        engine.effectiveConfig().onSuccess { config -> local.update { it.copy(dnsServers = config.dnsUpstreams) } }
    }

    private fun geoData(local: Local, connected: Boolean): RvGeoData {
        val data = rvGeoData(local.geo, store.shippedGeoDate, connected)
        return local.geoAsked?.let { data.copy(update = it) } ?: data
    }

    private fun initialState() = SettingsUiState(
        settings = null,
        perAppLoaded = engine.perAppLoaded.value,
        dnsLoaded = false,
        geoData = geoData(local.value, engine.status.value is Status.Running),
        logLevel = rvLogLevel(levels.level.value),
    )

    /** The store's values the page shows. */
    private data class Prefs(
        val notifyOnDrop: Boolean,
        val autoUpdate: Boolean,
        val updateHours: Int,
        val appearance: RvAppearance,
        val tileAdded: Boolean,
        val geoInterval: RvGeoInterval,
    )

    /**
     * What the ViewModel itself holds: the language as last read, the tile sheet, the core's DNS servers ([dnsServers],
     * null until read), the core's geo data as last read, the update's own outcome while the core has not reported
     * it ([geoAsked]: under way, or a request that failed), and where 檢查更新 stands ([update]).
     */
    private data class Local(
        val language: RvLanguage,
        val tileSheet: Boolean = false,
        val dnsServers: List<String>? = null,
        val geo: ConfigAssets? = null,
        val geoAsked: RvGeoUpdate? = null,
        val update: RvUpdate = RvUpdate.Idle,
    )

    companion object {
        fun factory(engine: Engine, store: SettingsStore, releases: ReleaseCheck, levels: LogLevels): ViewModelProvider.Factory = viewModelFactory {
            initializer { SettingsViewModel(engine, store, releases, levels = levels) }
        }
    }
}

/** A core that logs at 資訊 and takes no other level: the [SettingsViewModel] default for tests of other rows. */
private object InfoLevels : LogLevels {
    override val level: StateFlow<LogLevel> = MutableStateFlow(LogLevel.Info)

    override suspend fun set(level: LogLevel): Result<Unit> = Result.failure(UnsupportedOperationException("a fixed level"))
}
