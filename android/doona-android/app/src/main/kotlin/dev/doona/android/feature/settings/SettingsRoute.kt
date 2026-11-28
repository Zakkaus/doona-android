package dev.doona.android.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.doona.android.designsystem.rv.RV_NO_VALUE
import dev.doona.android.designsystem.rv.Rv
import dev.doona.android.engine.Engine
import dev.doona.android.feature.model.RvLanguage
import dev.doona.android.honk.NativeSettings

/**
 * The 設定 tab: builds the ViewModel over the [engine], [honk], the app's [store], [releases] (檢查更新) and the core's log
 * [levels] (日誌等級's row) and shows the page;
 * [system] takes what Android does, [nav] the pages 設定 opens, and [onTab] the floating nav's tabs. [version] is the
 * app's version name, a dash when the shell has none.
 */
@Composable
internal fun SettingsRoute(
    engine: Engine,
    honk: NativeSettings,
    store: SettingsStore,
    releases: ReleaseCheck,
    levels: LogLevels,
    system: SettingsSystem,
    nav: SettingsNav,
    version: String,
    onTab: (Int) -> Unit,
) {
    val viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.factory(engine, honk, store, releases, levels))
    SettingsPage(viewModel, system, nav, version, onTab)
}

/** The page on [viewModel]'s state, collected while the page is started; the goldens drive their own ViewModel through it. */
@Composable
internal fun SettingsPage(viewModel: SettingsViewModel, system: SettingsSystem, nav: SettingsNav, version: String, onTab: (Int) -> Unit) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.onResume() }
    val routes = remember(viewModel, system, nav, version) {
        RvSettingsRoutes(
            alwaysOn = system::openVpnSettings,
            addTile = { system.requestTile(viewModel::tileAnswer) },
            perApp = nav.perApp,
            dns = nav.dns,
            effectiveConfig = nav.effectiveConfig,
            report = nav.report,
            logLevel = nav.logLevel,
            geoInterval = nav.geoInterval,
            about = nav.about,
            config = nav.config,
            geoUpdate = viewModel::updateGeo,
            checkUpdate = { viewModel.checkUpdate(version) },
            release = system::openReleases,
            language = nav.language,
            interval = nav.interval,
            lab = nav.lab,
        )
    }
    // Until the store answers the page stays bare, never a switch or an interval the user may not have set.
    val drawn = state.settings ?: return RvSettingsLoading()
    // The page's switches send the state they were drawn from, so the ViewModel writes only the field turned.
    RvSettingsScreen(
        drawn,
        // A shell that could not read its version shows a dash, as every row with no value does.
        version.ifBlank { RV_NO_VALUE },
        { viewModel.onChange(drawn, it) },
        nav.theme,
        onTab,
        routes,
        configRev = state.configRev,
        geoData = state.geoData,
        perAppLoaded = state.perAppLoaded,
        dnsLoaded = state.dnsLoaded,
        update = state.update,
        logLevel = state.logLevel,
    )
}

/**
 * 設定 while the store has not answered (a read of the app's own preferences file, a frame or two): the page's
 * background alone. A skeleton would only flash, and no approved 設定 skeleton exists (STATE q-p6a-settingsloading).
 */
@Composable
internal fun RvSettingsLoading() = Box(Modifier.fillMaxSize().background(Rv.colors.page))

/** 更新間隔: the store's interval, a choice stored at once, and 自訂's sheet for a typed count of hours. */
@Composable
internal fun IntervalRoute(store: SettingsStore, onBack: () -> Unit) {
    val viewModel: IntervalViewModel = viewModel(factory = IntervalViewModel.factory(store))
    val state = viewModel.uiState.collectAsStateWithLifecycle().value ?: return RvSettingsLoading()
    RvIntervalScreen(state.selected, viewModel.choices, viewModel::select, onBack, viewModel::openCustom)
    RvIntervalCustomSheet(state, viewModel.range, viewModel::editCustom, viewModel::saveCustom, viewModel::dismissCustom)
}

/**
 * 語言: the app's per-app locale, which Android keeps; a choice goes to Android, which shows the app again in that
 * language. [system] is the language the phone's setting gives, which 跟隨系統 names.
 */
@Composable
internal fun LanguageRoute(store: SettingsStore, onBack: () -> Unit, system: RvLanguage = rvSystemLanguage()) {
    var selected by remember(store) { mutableStateOf(RvLanguage.of(store.languageTags())) }
    RvLanguageScreen(selected, system, { language ->
        selected = language
        store.setLanguageTags(language.tag)
    }, onBack)
}

/** GeoIP/GeoSite's 自動更新 over [honk]'s schedule: a choice goes to honk, and the page shows what honk then reports. */
@Composable
internal fun GeoIntervalRoute(honk: NativeSettings, onBack: () -> Unit) =
    GeoIntervalPage(viewModel(factory = GeoIntervalViewModel.factory(honk)), onBack)

/**
 * 自動更新 on [viewModel]'s schedule, the page's background alone until the first read ends, then disabled with 未連線
 * when honk did not answer; the goldens drive their own ViewModel through it.
 */
@Composable
internal fun GeoIntervalPage(viewModel: GeoIntervalViewModel, onBack: () -> Unit) {
    val state = viewModel.state.collectAsStateWithLifecycle().value ?: return RvSettingsLoading()
    RvGeoIntervalScreen(state.selected, viewModel::select, onBack)
}

/** 日誌等級 over the app's [levels]. */
@Composable
internal fun LogLevelRoute(levels: LogLevels, onBack: () -> Unit) {
    val viewModel: LogLevelViewModel = viewModel(factory = LogLevelViewModel.factory(levels))
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    RvLogLevelScreen(state.level, viewModel::select, onBack, retry = if (state.refused != null) viewModel::retry else null)
}

/**
 * 實驗室 over the [store]: 效能模式 is stored at once, and the core reads it when it starts (owner, 2026-11-25 15:20).
 * While the switch differs from what this run started with, the page offers 重開 App, which runs [onRestart]. Until
 * the store answers, the page's background alone.
 */
@Composable
internal fun LabRoute(store: SettingsStore, onBack: () -> Unit, onRestart: () -> Unit) {
    val viewModel: LabViewModel = viewModel(factory = LabViewModel.factory(store))
    val state = viewModel.uiState.collectAsStateWithLifecycle().value ?: return RvSettingsLoading()
    RvLabScreen(state.performance, viewModel::setPerformance, onBack, restart = onRestart.takeIf { state.restartPending })
}
