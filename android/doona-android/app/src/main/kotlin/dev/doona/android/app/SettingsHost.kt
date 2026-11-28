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
import dev.doona.android.data.settings.ThemeAccent
import dev.doona.android.data.settings.ThemeAppearance
import dev.doona.android.data.settings.ThemeMode
import dev.doona.android.data.settings.UpdateCheck
import dev.doona.android.data.settings.UpdateInterval
import dev.doona.android.designsystem.rv.RV_NO_VALUE
import dev.doona.android.designsystem.rv.rvSystemSchemes
import dev.doona.android.engine.BuildInfo
import dev.doona.android.engine.Engine
import dev.doona.android.feature.activity.RvActivityRoutes
import dev.doona.android.feature.activity.RvActivityScreen
import dev.doona.android.feature.model.RvAppearance
import dev.doona.android.feature.model.RvThemeMode
import dev.doona.android.feature.report.AppReportFacts
import dev.doona.android.feature.report.ReportRoute
import dev.doona.android.feature.settings.DnsRoute
import dev.doona.android.feature.settings.DnsRuleEditRoute
import dev.doona.android.feature.settings.DnsRulesRoute
import dev.doona.android.feature.settings.DnsServersRoute
import dev.doona.android.feature.settings.GeoIntervalRoute
import dev.doona.android.feature.settings.IntervalRoute
import dev.doona.android.feature.settings.LabRoute
import dev.doona.android.feature.settings.LanguageRoute
import dev.doona.android.feature.settings.LogLevelRoute
import dev.doona.android.feature.settings.LogLevels
import dev.doona.android.feature.settings.ReleaseCheck
import dev.doona.android.feature.settings.RvAboutRoutes
import dev.doona.android.feature.settings.RvAboutScreen
import dev.doona.android.feature.settings.RvAboutVersions
import dev.doona.android.feature.settings.RvLicenseText
import dev.doona.android.feature.settings.RvLicensesScreen
import dev.doona.android.feature.settings.RvLogLevel
import dev.doona.android.feature.settings.RvThemePreviewPage
import dev.doona.android.feature.settings.SettingsNav
import dev.doona.android.feature.settings.SettingsRoute
import dev.doona.android.feature.settings.SettingsStore
import dev.doona.android.feature.settings.SettingsSystem
import dev.doona.android.feature.settings.ThemeRoute
import dev.doona.android.feature.settings.TileAnswer
import dev.doona.android.feature.settings.rvLicenseEntries
import dev.doona.android.honk.NativeSettings
import dev.doona.android.vpn.QuickTile
import dev.doona.android.vpn.QuickTileResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * 設定's store in the app: [settings] for the switches, the interval and the theme, [tile] for the quick settings
 * tile, Android's per-app locale list for the language. Each set is launched in [writes], the app's main-thread scope:
 * a page's own scope is cancelled when the user leaves it, which could drop a choice made just before; a launch there
 * starts at once, so DataStore queues the edits in the order they were asked for.
 */
internal class AppSettingsStore(
    private val context: Context,
    private val settings: AppSettings,
    private val tile: QuickTile,
    private val writes: CoroutineScope,
) : SettingsStore {
    override val shippedGeoDate: String = BuildInfo.GEO_SHIPPED_DATE
    override val notifyOnDrop: Flow<Boolean> = settings.notifyOnDrop
    override val autoUpdate: Flow<Boolean> = settings.subscriptionUpdate.map { it.enabled }
    override val updateHours: Flow<Int> = settings.subscriptionUpdate.map { it.interval.hours }
    override val updateIntervals: List<Int> = UpdateInterval.PRESETS.map { it.hours }
    override val updateHoursRange: IntRange = UpdateInterval.RANGE
    override val appearance: Flow<RvAppearance> = settings.appearance.map(::rvAppearance)

    override fun setAppearance(appearance: RvAppearance) = write { settings.setAppearance(themeAppearance(appearance)) }
    override val tileAdded: Flow<Boolean> = tile.added

    override fun languageTags(): String = LanguageSettings.languageTags(context)

    override fun setLanguageTags(tags: String) {
        LanguageSettings.setLanguageTags(context, tags)
    }

    override fun setNotifyOnDrop(enabled: Boolean) = write { settings.setNotifyOnDrop(enabled) }

    override fun setAutoUpdate(enabled: Boolean) = write { settings.setAutoUpdate(enabled) }

    override fun setUpdateHours(hours: Int) {
        // Out of range throws here, to the caller, not inside the launch.
        val interval = UpdateInterval.ofHours(hours)
        write { settings.setUpdateInterval(interval) }
    }

    override val performanceMode: Flow<Boolean> = settings.performanceMode

    override suspend fun performanceAtStart(): Boolean = startedPerformance ?: settings.performanceMode.first().also { startedPerformance = it }

    override fun setPerformanceMode(enabled: Boolean) = write { settings.setPerformanceMode(enabled) }

    private fun write(edit: suspend () -> Unit) {
        writes.launch { edit() }
    }

    companion object {
        /**
         * 效能模式 as first read in this process. The core reads the stored value when it starts, at the app's start and
         * before 實驗室 can open, and a turn of the switch is stored only after 實驗室 has read this, so the two agree.
         */
        @Volatile
        private var startedPerformance: Boolean? = null
    }
}

/** 主題's choices as the page shows them: the accent is the swatch's place, the scheme's own first. */
internal fun rvAppearance(theme: ThemeAppearance) = RvAppearance(
    when (theme.mode) {
        ThemeMode.System -> RvThemeMode.System
        ThemeMode.Light -> RvThemeMode.Light
        ThemeMode.Dark -> RvThemeMode.Dark
    },
    theme.followWallpaper,
    theme.accent.ordinal,
)

/** The page's choices as the store keeps them; [rvAppearance]'s inverse. */
internal fun themeAppearance(appearance: RvAppearance) = ThemeAppearance(
    when (appearance.mode) {
        RvThemeMode.System -> ThemeMode.System
        RvThemeMode.Light -> ThemeMode.Light
        RvThemeMode.Dark -> ThemeMode.Dark
    },
    appearance.followWallpaper,
    ThemeAccent.entries[appearance.accent],
)

/** What 設定 hands to Android from [activity]: its VPN settings, the releases, repository and privacy pages, and the quick settings [tile]'s request. */
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

    override fun openPrivacy() {
        UpdateCheck.openPrivacy(activity)
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
    override val level: StateFlow<RvLogLevel> get() = controller.level

    override suspend fun set(level: RvLogLevel): Result<Unit> = controller.set(level)
}

/** What 主題's 預覽 draws in the app: the RV 活動 page on [ThemePreviewSample], in the chosen scheme; none of its taps leads anywhere. */
internal val APP_THEME_PREVIEW: RvThemePreviewPage = {
    RvActivityScreen(
        ThemePreviewSample.state,
        onAction = {},
        onConnect = {},
        routes = PREVIEW_ROUTES,
        nodes = ThemePreviewSample.nodes,
        subscriptions = ThemePreviewSample.subscriptions,
        rules = emptyList(),
        cards = ThemePreviewSample.cards,
    )
}

private val PREVIEW_ROUTES = RvActivityRoutes(
    traffic = {},
    stats = {},
    search = {},
    nodes = {},
    node = {},
    tab = {},
    mode = {},
    rule = {},
    rules = {},
    test = {},
    subscriptions = {},
    more = {},
)

/** 檢查更新 in the app: GitHub's releases API, asked only when the user taps it. */
internal val APP_RELEASES = ReleaseCheck { UpdateCheck.latestRelease() }

/**
 * The honk revision the core is built on (`honk-config` and `honk-outbound`'s `rev` in `crates/doona-core/Cargo.toml`),
 * short, for 關於. A test checks it against that file.
 */
internal const val HONK_REVISION = "63de98b"

/**
 * 設定 and the pages it opens, as the app's back stack entries: 回報問題, 日誌等級, GeoIP/GeoSite's 自動更新, 關於, 主題,
 * 語言, 更新間隔, 實驗室, DNS, 授權 and each licence's text; 生效設定 opens P4's page, the app's [EffectiveConfigKey].
 * 關於's 隱私 opens the repository's privacy notice in the browser.
 */
internal fun EntryProviderScope<NavKey>.settingsEntries(backStack: MutableList<NavKey>, engine: Engine, version: String, onTab: (Int) -> Unit) {
    entry<SettingsKey> {
        val nav = remember(backStack) {
            SettingsNav(
                perApp = { backStack.push(PerAppKey) },
                dns = { backStack.push(DnsKey) },
                effectiveConfig = { backStack.push(EffectiveConfigKey) },
                report = { backStack.push(ReportKey) },
                logLevel = { backStack.push(LogLevelKey) },
                geoInterval = { backStack.push(GeoIntervalKey) },
                about = { backStack.push(AboutKey) },
                theme = { backStack.push(ThemeKey) },
                language = { backStack.push(LanguageKey) },
                interval = { backStack.push(IntervalKey) },
                lab = { backStack.push(LabKey) },
            )
        }
        SettingsRoute(engine, rememberNativeSettings(), rememberSettingsStore(), APP_RELEASES, rememberLogLevels(), rememberSettingsSystem(), nav, version, onTab)
    }
    entry<LogLevelKey> { key -> LogLevelRoute(rememberLogLevels(), onBack = { backStack.pop(key) }) }
    entry<GeoIntervalKey> { key -> GeoIntervalRoute(rememberNativeSettings(), onBack = { backStack.pop(key) }) }
    entry<ReportKey> { key ->
        val context = LocalContext.current
        val facts = remember(context) { AppReportFacts(context.applicationContext) }
        ReportRoute(engine, container().reportExporter, facts, onBack = { backStack.pop(key) })
    }
    entry<AboutKey> { key ->
        val system = rememberSettingsSystem()
        val routes = remember(backStack, system) {
            RvAboutRoutes(github = system::openRepository, report = { backStack.push(ReportKey) }, licenses = { backStack.push(LicensesKey) }, privacy = system::openPrivacy)
        }
        RvAboutScreen(RvAboutVersions(version.ifBlank { RV_NO_VALUE }, HONK_REVISION), onBack = { backStack.pop(key) }, routes)
    }
    entry<ThemeKey> { key ->
        ThemeRoute(rememberSettingsStore(), rvSystemSchemes(), APP_THEME_PREVIEW, onBack = { backStack.pop(key) })
    }
    entry<DnsKey> { key ->
        DnsRoute(nativeApi(), onBack = { backStack.pop(key) }, onServers = { backStack.push(DnsServersKey) }, onRules = { backStack.push(DnsRulesKey) })
    }
    // 查看規則 opens nothing until the DNS rules page is in the app.
    entry<DnsServersKey> { key -> DnsServersRoute(nativeApi(), onBack = { backStack.pop(key) }, onRules = { backStack.push(DnsRulesKey) }) }
    entry<DnsRulesKey> { key ->
        DnsRulesRoute(
            nativeApi(),
            onBack = { backStack.pop(key) },
            onOpen = { query, id -> backStack.push(DnsRuleEditKey(dnsList(query), id)) },
            onAdd = { backStack.push(DnsRuleEditKey(dnsList(it), null)) },
        )
    }
    entry<DnsRuleEditKey> { key ->
        DnsRuleEditRoute(nativeApi(), key.list == dnsList(true), key.ruleId, onBack = { backStack.pop(key) }, onOpenSource = { backStack.push(ConfigSourceKey(it)) })
    }
    entry<LanguageKey> { key -> LanguageRoute(rememberSettingsStore(), onBack = { backStack.pop(key) }) }
    // 自訂 has no page yet (plan 8: built, not hidden).
    entry<IntervalKey> { key -> IntervalRoute(rememberSettingsStore(), onBack = { backStack.pop(key) }) }
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
private fun rememberSettingsStore(): SettingsStore = container().settingsStore

@Composable
private fun rememberNativeSettings(): NativeSettings = container().nativeSettings

@Composable
private fun rememberLogLevels(): LogLevels {
    val container = container()
    return remember(container) { AppLogLevels(container.logLevels) }
}

@Composable
private fun rememberSettingsSystem(): SettingsSystem {
    val context = LocalContext.current
    val container = container()
    return remember(context, container) { ActivitySettingsSystem(context as Activity, container.quickTile) }
}

/** honk's name of a DNS list: `request` for queries, `response`. */
private fun dnsList(query: Boolean) = if (query) "request" else "response"
