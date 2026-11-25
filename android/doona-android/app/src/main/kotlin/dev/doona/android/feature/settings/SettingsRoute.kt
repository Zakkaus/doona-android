package dev.doona.android.feature.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.doona.android.designsystem.rv.RV_NO_VALUE
import dev.doona.android.engine.Engine
import dev.doona.android.feature.model.RvLanguage

/**
 * The 設定 tab: builds the ViewModel over the [engine] and the app's [store] and shows the page; [system] takes what
 * Android does, [nav] the pages 設定 opens, and [onTab] the floating nav's tabs. [version] is the app's version name, a dash when the shell has none.
 */
@Composable
internal fun SettingsRoute(engine: Engine, store: SettingsStore, system: SettingsSystem, nav: SettingsNav, version: String, onTab: (Int) -> Unit) {
    val viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.factory(engine, store))
    SettingsPage(viewModel, system, nav, version, onTab)
}

/** The page on [viewModel]'s state, collected while the page is started; the goldens drive their own ViewModel through it. */
@Composable
internal fun SettingsPage(viewModel: SettingsViewModel, system: SettingsSystem, nav: SettingsNav, version: String, onTab: (Int) -> Unit) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.onResume() }
    val routes = remember(viewModel, system, nav) {
        RvSettingsRoutes(
            alwaysOn = system::openVpnSettings,
            addTile = { system.requestTile(viewModel::tileAnswer) },
            perApp = nav.perApp,
            dns = nav.dns,
            effectiveConfig = nav.effectiveConfig,
            report = nav.report,
            about = nav.about,
            // 設定檔 shows only with a configuration revision (a preview flag), which the app does not pass.
            config = {},
            geoUpdate = viewModel::updateGeo,
            checkUpdate = system::openReleases,
            release = system::openReleases,
            language = nav.language,
            interval = nav.interval,
            lab = nav.lab,
        )
    }
    // The page's switches send the state they were drawn from, so the ViewModel writes only the field turned.
    val drawn = state.settings
    RvSettingsScreen(
        drawn,
        // A shell that could not read its version shows a dash, as every row with no value does.
        version.ifBlank { RV_NO_VALUE },
        { viewModel.onChange(drawn, it) },
        nav.theme,
        onTab,
        routes,
        geoData = state.geoData,
        perAppLoaded = state.perAppLoaded,
        dnsLoaded = state.dnsLoaded,
    )
}

/** 更新間隔: the store's interval, a choice stored at once. Its 自訂 has no page yet (plan 8: built, not hidden). */
@Composable
internal fun IntervalRoute(store: SettingsStore, onCustom: () -> Unit, onBack: () -> Unit) {
    val viewModel: IntervalViewModel = viewModel(factory = IntervalViewModel.factory(store))
    val hours by viewModel.uiState.collectAsStateWithLifecycle()
    RvIntervalScreen(hours, viewModel.choices, viewModel::select, onBack, onCustom)
}

/**
 * 語言: the app's per-app locale, which Android keeps; a choice goes to Android, which shows the app again in that
 * language.
 */
@Composable
internal fun LanguageRoute(store: SettingsStore, onBack: () -> Unit) {
    var selected by remember(store) { mutableStateOf(RvLanguage.of(store.languageTags())) }
    RvLanguageScreen(selected, rvSystemLanguage(), { language ->
        selected = language
        store.setLanguageTags(language.tag)
    }, onBack)
}

/** 日誌等級 over the app's [levels]. */
@Composable
internal fun LogLevelRoute(levels: LogLevels, onBack: () -> Unit) {
    val viewModel: LogLevelViewModel = viewModel(factory = LogLevelViewModel.factory(levels))
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    RvLogLevelScreen(state.level, viewModel::select, onBack, retry = if (state.refused != null) viewModel::retry else null)
}

/**
 * 實驗室: 效能模式 is the page's own until the core takes a worker count at its start (owner, 2026-11-25 15:20); no
 * setting or engine call carries it yet.
 */
@Composable
internal fun LabRoute(onBack: () -> Unit) {
    var performance by rememberSaveable { mutableStateOf(false) }
    RvLabScreen(performance, { performance = it }, onBack)
}
