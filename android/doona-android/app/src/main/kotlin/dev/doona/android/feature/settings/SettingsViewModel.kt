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
import dev.doona.android.engine.Status
import dev.doona.android.feature.model.RvAppearance
import dev.doona.android.feature.model.RvDnsUpstream
import dev.doona.android.feature.model.RvGeoData
import dev.doona.android.feature.model.RvGeoUpdate
import dev.doona.android.feature.model.RvLanguage
import dev.doona.android.feature.model.RvSettingsState
import dev.doona.android.feature.model.RvThemeMode
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * What 設定 shows: the page's state, whether the per-app policy has been read ([perAppLoaded]; until then its row has no
 * second line rather than a policy the user may not have), and the GeoIP/GeoSite row.
 */
internal data class SettingsUiState(val settings: RvSettingsState, val perAppLoaded: Boolean, val geoData: RvGeoData)

/**
 * 設定 over the app's [store] and the [engine]: the switches set the store, 依 App's row reads the engine's policy, and
 * the GeoIP/GeoSite row reads the core's data and runs its update, asking the core where it stands every [poll] until
 * it ends. The DNS row shows Cloudflare: the core keeps the chosen servers but tells nobody which they are.
 */
internal class SettingsViewModel(
    private val engine: Engine,
    private val store: SettingsStore,
    private val poll: Duration = 1.seconds,
) : ViewModel() {
    private val local = MutableStateFlow(Local(language = RvLanguage.of(store.languageTags())))
    private var geoJob: Job? = null

    private val prefs = combine(store.notifyOnDrop, store.autoUpdate, store.updateHours, store.appearance, store.tileAdded, ::Prefs)

    val uiState: StateFlow<SettingsUiState> =
        combine(prefs, engine.settings, engine.perAppLoaded, engine.status, local) { prefs, settings, loaded, status, local ->
            SettingsUiState(
                RvSettingsState(
                    notifyOnDrop = prefs.notifyOnDrop,
                    perApp = rvPerApp(settings.perApp),
                    autoUpdate = prefs.autoUpdate,
                    updateHours = prefs.updateHours,
                    appearance = prefs.appearance,
                    dns = RvDnsUpstream.Cloudflare,
                    language = local.language,
                    tileAdded = prefs.tileAdded,
                    tileSheet = local.tileSheet,
                ),
                perAppLoaded = loaded,
                geoData = geoData(local, status is Status.Running),
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), initialState())

    init {
        viewModelScope.launch { readGeo() }
    }

    /** The page's switches and the manual tile sheet: whatever [changed] from the state shown goes where it is kept. */
    fun onChange(changed: RvSettingsState) {
        val shown = uiState.value.settings
        if (changed.notifyOnDrop != shown.notifyOnDrop) viewModelScope.launch { store.setNotifyOnDrop(changed.notifyOnDrop) }
        if (changed.autoUpdate != shown.autoUpdate) viewModelScope.launch { store.setAutoUpdate(changed.autoUpdate) }
        if (changed.tileSheet != shown.tileSheet) local.update { it.copy(tileSheet = changed.tileSheet) }
    }

    /** The system's answer to 加到快速設定: an added tile hides the row (the store records it), a failed request opens the sheet. */
    fun tileAnswer(answer: TileAnswer) {
        if (answer == TileAnswer.Failed) local.update { it.copy(tileSheet = true) }
    }

    /** The page shows again: the language may have changed in Android's settings meanwhile. */
    fun onResume() {
        local.update { it.copy(language = RvLanguage.of(store.languageTags())) }
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
            local.update { it.copy(geoAsked = if (failure != null && !busy) RvGeoUpdate.Failed else null, geo = started.getOrNull() ?: it.geo) }
            if (failure == null || busy) followGeo()
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

    private suspend fun followGeo() {
        while (true) {
            val assets = engine.configAssets().getOrElse {
                local.update { it.copy(geoAsked = RvGeoUpdate.Failed) }
                return
            }
            local.update { it.copy(geo = assets) }
            if (rvGeoUpdate(assets.lastResult) != RvGeoUpdate.Updating) return
            delay(poll)
        }
    }

    private fun geoData(local: Local, connected: Boolean): RvGeoData {
        val data = rvGeoData(local.geo, store.shippedGeoDate, connected)
        return local.geoAsked?.let { data.copy(update = it) } ?: data
    }

    private fun initialState() = SettingsUiState(
        RvSettingsState(
            notifyOnDrop = true,
            perApp = rvPerApp(engine.settings.value.perApp),
            autoUpdate = true,
            updateHours = IntervalViewModel.DEFAULT_HOURS,
            appearance = RvAppearance(RvThemeMode.System, followWallpaper = false, accent = 0),
            dns = RvDnsUpstream.Cloudflare,
            language = local.value.language,
        ),
        perAppLoaded = engine.perAppLoaded.value,
        geoData = geoData(local.value, engine.status.value is Status.Running),
    )

    /** The store's values the page shows. */
    private data class Prefs(val notifyOnDrop: Boolean, val autoUpdate: Boolean, val updateHours: Int, val appearance: RvAppearance, val tileAdded: Boolean)

    /**
     * What the ViewModel itself holds: the language as last read, the tile sheet, the core's geo data as last read, and
     * the update's own outcome while the core has not reported it ([geoAsked]: under way, or a request that failed).
     */
    private data class Local(
        val language: RvLanguage,
        val tileSheet: Boolean = false,
        val geo: ConfigAssets? = null,
        val geoAsked: RvGeoUpdate? = null,
    )

    companion object {
        fun factory(engine: Engine, store: SettingsStore): ViewModelProvider.Factory = viewModelFactory {
            initializer { SettingsViewModel(engine, store) }
        }
    }
}
