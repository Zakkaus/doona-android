package dev.doona.android.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import dev.doona.android.data.settings.AppSettings
import dev.doona.android.data.settings.LanguageSettings
import dev.doona.android.data.settings.LogLevelController
import dev.doona.android.data.settings.ThemeMode
import dev.doona.android.data.settings.UpdateCheck
import dev.doona.android.data.settings.UpdateInterval
import dev.doona.android.designsystem.rv.RV_NO_VALUE
import dev.doona.android.engine.Engine
import dev.doona.android.engine.LogLevel
import dev.doona.android.feature.NOT_WIRED
import dev.doona.android.feature.model.RvAppearance
import dev.doona.android.feature.model.RvThemeMode
import dev.doona.android.feature.report.AppReportFacts
import dev.doona.android.feature.report.ReportRoute
import dev.doona.android.feature.settings.IntervalRoute
import dev.doona.android.feature.settings.LabRoute
import dev.doona.android.feature.settings.LanguageRoute
import dev.doona.android.feature.settings.LogLevels
import dev.doona.android.feature.settings.ReleaseCheck
import dev.doona.android.feature.settings.RvAboutRoutes
import dev.doona.android.feature.settings.RvAboutScreen
import dev.doona.android.feature.settings.RvAboutVersions
import dev.doona.android.feature.settings.RvLicenseText
import dev.doona.android.feature.settings.RvLicensesScreen
import dev.doona.android.feature.settings.SettingsNav
import dev.doona.android.feature.settings.SettingsRoute
import dev.doona.android.feature.settings.SettingsStore
import dev.doona.android.feature.settings.SettingsSystem
import dev.doona.android.feature.settings.TileAnswer
import dev.doona.android.feature.settings.rvLicenseEntries
import dev.doona.android.vpn.QuickTile
import dev.doona.android.vpn.QuickTileResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * 設定's store in the app: [settings] for the switches, the interval and the theme, [tile] for the quick settings
 * tile, Android's per-app locale list for the language.
 */
internal class AppSettingsStore(
    private val context: Context,
    private val settings: AppSettings,
    private val tile: QuickTile,
) : SettingsStore {
    override val shippedGeoDate: String = SHIPPED_GEO_DATE
    override val notifyOnDrop: Flow<Boolean> = settings.notifyOnDrop
    override val autoUpdate: Flow<Boolean> = settings.subscriptionUpdate.map { it.enabled }
    override val updateHours: Flow<Int> = settings.subscriptionUpdate.map { it.interval.hours }
    override val updateIntervals: List<Int> = UpdateInterval.entries.map { it.hours }
    override val appearance: Flow<RvAppearance> = settings.appearance.map {
        RvAppearance(rvThemeMode(it.mode), it.followWallpaper, it.accent.ordinal)
    }
    override val tileAdded: Flow<Boolean> = tile.added

    override fun languageTags(): String = LanguageSettings.languageTags(context)

    override fun setLanguageTags(tags: String) {
        LanguageSettings.setLanguageTags(context, tags)
    }

    override suspend fun setNotifyOnDrop(enabled: Boolean) = settings.setNotifyOnDrop(enabled)

    override suspend fun setAutoUpdate(enabled: Boolean) = settings.setAutoUpdate(enabled)

    override suspend fun setUpdateHours(hours: Int) = settings.setUpdateInterval(UpdateInterval.ofHours(hours))

    override val performanceMode: Flow<Boolean> = settings.performanceMode

    override suspend fun performanceAtStart(): Boolean = startedPerformance ?: settings.performanceMode.first().also { startedPerformance = it }

    override suspend fun setPerformanceMode(enabled: Boolean) = settings.setPerformanceMode(enabled)

    private fun rvThemeMode(mode: ThemeMode) = when (mode) {
        ThemeMode.System -> RvThemeMode.System
        ThemeMode.Light -> RvThemeMode.Light
        ThemeMode.Dark -> RvThemeMode.Dark
    }

    companion object {
        /** `release_date` of `crates/doona-core/tools/geo-pins.json`: the GeoIP/GeoSite release the APK ships. */
        const val SHIPPED_GEO_DATE = "2026-11-23"

        /**
         * 效能模式 as first read in this process. The core reads the stored value when it starts, at the app's start and
         * before 實驗室 can open, and a turn of the switch is stored only after 實驗室 has read this, so the two agree.
         */
        @Volatile
        private var startedPerformance: Boolean? = null
    }
}

/** What 設定 hands to Android from [activity]: its VPN settings, the releases and repository pages, and the quick settings [tile]'s request. */
internal class ActivitySettingsSystem(private val activity: Activity, private val tile: QuickTile) : SettingsSystem {
    override fun openVpnSettings() {
        activity.startActivity(Intent(Settings.ACTION_VPN_SETTINGS))
    }

    override fun openReleases() {
        UpdateCheck.openReleases(activity)
    }

    override fun openRepository() {
        UpdateCheck.openRepository(activity)
    }

    override fun requestTile(onAnswer: (TileAnswer) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return onAnswer(TileAnswer.Failed)
        tile.request(activity) { result ->
            onAnswer(
                when (result) {
                    QuickTileResult.Added -> TileAnswer.Added
                    QuickTileResult.Kept -> TileAnswer.Kept
                    QuickTileResult.Failed -> TileAnswer.Failed
                },
            )
        }
    }
}

/** 日誌等級's levels: the app's process-wide [controller]. */
internal class AppLogLevels(private val controller: LogLevelController) : LogLevels {
    override val level: StateFlow<LogLevel> get() = controller.level

    override suspend fun set(level: LogLevel): Result<Unit> = controller.set(level)
}

/** 檢查更新 in the app: GitHub's releases API, asked only when the user taps it. */
internal val APP_RELEASES = ReleaseCheck { UpdateCheck.latestRelease() }

/**
 * The honk revision the core is built on (`honk-config` and `honk-outbound`'s `rev` in `crates/doona-core/Cargo.toml`),
 * short, for 關於. A test checks it against that file.
 */
internal const val HONK_REVISION = "63de98b"

/**
 * 設定 and the pages it opens, as the app's back stack entries: 回報問題, 關於, 語言, 更新間隔, 實驗室, 授權 and each
 * licence's text. 主題 and DNS stay in the debug sandbox (plan 1.2) and the effective config is P4's page, so their rows
 * open nothing here yet; 日誌等級 waits for the owner (q-p6a-logrow); 關於's 隱私 has no page to open (q-p6a-privacy).
 */
internal fun EntryProviderScope<NavKey>.settingsEntries(backStack: MutableList<NavKey>, engine: Engine, version: String, onTab: (Int) -> Unit) {
    entry<SettingsKey> {
        val nav = remember(backStack) {
            SettingsNav(
                perApp = { backStack.push(PerAppKey) },
                dns = NOT_WIRED,
                effectiveConfig = NOT_WIRED,
                report = { backStack.push(ReportKey) },
                about = { backStack.push(AboutKey) },
                theme = NOT_WIRED,
                language = { backStack.push(LanguageKey) },
                interval = { backStack.push(IntervalKey) },
                lab = { backStack.push(LabKey) },
            )
        }
        SettingsRoute(engine, rememberSettingsStore(), APP_RELEASES, rememberSettingsSystem(), nav, version, onTab)
    }
    entry<ReportKey> { key ->
        val context = LocalContext.current
        val facts = remember(context) { AppReportFacts(context.applicationContext) }
        ReportRoute(engine, container().reportExporter, facts, onBack = { backStack.pop(key) })
    }
    entry<AboutKey> { key ->
        val system = rememberSettingsSystem()
        val routes = remember(backStack, system) {
            RvAboutRoutes(github = system::openRepository, report = { backStack.push(ReportKey) }, licenses = { backStack.push(LicensesKey) }, privacy = NOT_WIRED)
        }
        RvAboutScreen(RvAboutVersions(version.ifBlank { RV_NO_VALUE }, HONK_REVISION), onBack = { backStack.pop(key) }, routes)
    }
    entry<LanguageKey> { key -> LanguageRoute(rememberSettingsStore(), onBack = { backStack.pop(key) }) }
    // 自訂 has no page yet (plan 8: built, not hidden).
    entry<IntervalKey> { key -> IntervalRoute(rememberSettingsStore(), onCustom = NOT_WIRED, onBack = { backStack.pop(key) }) }
    entry<LabKey> { key ->
        val context = LocalContext.current
        LabRoute(rememberSettingsStore(), onBack = { backStack.pop(key) }, onRestart = { restartApp(context) })
    }
    entry<LicensesKey> { key -> RvLicensesScreen(onOpen = { backStack.push(LicenseTextKey(it.toString())) }, onBack = { backStack.pop(key) }) }
    entry<LicenseTextKey> { key -> RvLicenseText(rvLicenseEntries()[key.index.toInt()], onBack = { backStack.pop(key) }) }
}

@Composable
private fun container(): AppContainer = (LocalContext.current.applicationContext as DoonaApplication).container

@Composable
private fun rememberSettingsStore(): SettingsStore {
    val container = container()
    return remember(container) { AppSettingsStore(container.application, AppSettings.get(container.application), container.quickTile) }
}

@Composable
private fun rememberSettingsSystem(): SettingsSystem {
    val context = LocalContext.current
    val container = container()
    return remember(context, container) { ActivitySettingsSystem(context as Activity, container.quickTile) }
}
