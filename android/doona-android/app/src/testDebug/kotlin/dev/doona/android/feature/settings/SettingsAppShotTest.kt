package dev.doona.android.feature.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import com.github.takahirom.roborazzi.captureRoboImage
import dev.doona.android.R
import dev.doona.android.data.settings.LogLevelController
import dev.doona.android.data.settings.UpdateInterval
import dev.doona.android.designsystem.rv.RvSheetLayout
import dev.doona.android.engine.AppPolicy
import dev.doona.android.engine.Catalog
import dev.doona.android.engine.CrashFile
import dev.doona.android.engine.Engine
import dev.doona.android.engine.EngineException
import dev.doona.android.engine.ErrorCode
import dev.doona.android.engine.LogLevel
import dev.doona.android.engine.PerApp
import dev.doona.android.engine.fake.FakeEngine
import dev.doona.android.engine.fake.rvSampleEngine
import dev.doona.android.feature.model.RvAppPolicy
import dev.doona.android.feature.model.RvLanguage
import dev.doona.android.feature.report.ReportFacts
import dev.doona.android.feature.report.ReportPage
import dev.doona.android.feature.report.ReportViewModel
import dev.doona.android.feature.report.RvRedaction
import dev.doona.android.feature.report.RvReportBuild
import dev.doona.android.feature.report.RvReportScreen
import dev.doona.android.prototype.rv.RV_EN
import dev.doona.android.prototype.rv.RV_FS115
import dev.doona.android.prototype.rv.RV_FS200
import dev.doona.android.prototype.rv.assertWhole
import dev.doona.android.prototype.rv.data.RvData
import dev.doona.android.prototype.rv.rvAnimatorScale
import dev.doona.android.prototype.rv.rvContent
import dev.doona.android.prototype.rv.rvRowRules
import dev.doona.android.prototype.rv.seedSchemes
import dev.doona.android.prototype.rv.settings.RV_SAMPLE_GEO_SHIPPED
import dev.doona.android.prototype.rv.settings.RV_SAMPLE_THEME_PREVIEW
import dev.doona.android.prototype.rv.settings.RvPerAppData
import dev.doona.android.prototype.rv.settings.rvReportActions
import dev.doona.android.prototype.rv.settings.rvSampleReport
import dev.doona.android.prototype.rv.settings.rvSampleVersions
import dev.doona.android.report.AppInfo
import dev.doona.android.report.CrashFiles
import dev.doona.android.report.CrashOffer
import dev.doona.android.report.ExportTokens
import dev.doona.android.report.RedactionOptions
import dev.doona.android.report.ReportExport
import dev.doona.android.report.ReportExporter
import dev.doona.android.report.ReportFailure
import dev.doona.android.report.ReportShare
import dev.doona.android.report.ReportZip
import dev.doona.android.report.nodeMembers
import dev.doona.android.report.testFlowEvents
import dev.doona.android.testing.drawFully
import dev.doona.android.testing.savePanel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

private val SHOTS = System.getProperty("doona.shots") ?: "src/test/screenshots"

/**
 * 設定, 回報問題 and the pages 設定 opens as the app builds them (`app-rv-*`): the RV sample engine and a store holding
 * the sample's settings, through each page's ViewModel and route, into the page. Each is the state of its `proto-rv-*`
 * golden (same name after `app-rv-`), so a pair differs only where the app cannot carry the sample's value.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rTW-w412dp-h915dp-notnight-xhdpi")
class SettingsAppShotTest {
    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val tmp = TemporaryFolder()

    @After
    fun restoreScale() = rvAnimatorScale(1f)

    @Test
    @Config(qualifiers = LONG_PAGE)
    fun settingsLight() = compose.app("settings", dark = false, whole = true, content = settingsPage())

    @Test
    @Config(qualifiers = LONG_PAGE)
    fun settingsDark() = compose.app("settings", dark = true, whole = true, content = settingsPage())

    @Test
    @Config(qualifiers = LONG_PAGE_FS115)
    fun settingsFs115() = compose.app("settings", fontScale = RV_FS115, whole = true, content = settingsPage())

    @Test
    @Config(qualifiers = LONG_PAGE_FS200)
    fun settingsFs200() = compose.app("settings", fontScale = RV_FS200, whole = true, content = settingsPage())

    @Test
    @Config(qualifiers = READY)
    fun reportLight() = compose.app("report", dark = false, whole = true, content = reportPage(Built.Ready, tmp))

    @Test
    @Config(qualifiers = READY)
    fun reportDark() = compose.app("report", dark = true, whole = true, content = reportPage(Built.Ready, tmp))

    @Test
    @Config(qualifiers = READY_FS115)
    fun reportFs115() = compose.app("report", fontScale = RV_FS115, whole = true, content = reportPage(Built.Ready, tmp))

    @Test
    @Config(qualifiers = READY_FS200)
    fun reportFs200() = compose.app("report", fontScale = RV_FS200, whole = true, content = reportPage(Built.Ready, tmp))

    @Test
    @Config(qualifiers = BUILDING)
    fun reportBuildingLight() = compose.app("report-building", dark = false, whole = true, busy = true, content = reportPage(Built.Building, tmp))

    @Test
    @Config(qualifiers = BUILDING)
    fun reportBuildingDark() = compose.app("report-building", dark = true, whole = true, busy = true, content = reportPage(Built.Building, tmp))

    @Test
    @Config(qualifiers = BUILDING_FS115)
    fun reportBuildingFs115() = compose.app("report-building", fontScale = RV_FS115, whole = true, busy = true, content = reportPage(Built.Building, tmp))

    @Test
    @Config(qualifiers = BUILDING_FS200)
    fun reportBuildingFs200() = compose.app("report-building", fontScale = RV_FS200, whole = true, busy = true, content = reportPage(Built.Building, tmp))

    @Test
    @Config(qualifiers = FAILED)
    fun reportFailedLight() = compose.app("report-failed", dark = false, whole = true, content = reportPage(Built.Failed, tmp))

    @Test
    @Config(qualifiers = FAILED)
    fun reportFailedDark() = compose.app("report-failed", dark = true, whole = true, content = reportPage(Built.Failed, tmp))

    @Test
    @Config(qualifiers = FAILED_FS115)
    fun reportFailedFs115() = compose.app("report-failed", fontScale = RV_FS115, whole = true, content = reportPage(Built.Failed, tmp))

    @Test
    @Config(qualifiers = FAILED_FS200)
    fun reportFailedFs200() = compose.app("report-failed", fontScale = RV_FS200, whole = true, content = reportPage(Built.Failed, tmp))

    @Test
    @Config(qualifiers = LINK_FAILED)
    fun reportLinkFailedLight() = compose.app("report-link-failed", dark = false, whole = true, content = reportPage(Built.Ready, tmp, linkFailed = true))

    @Test
    @Config(qualifiers = LINK_FAILED)
    fun reportLinkFailedDark() = compose.app("report-link-failed", dark = true, whole = true, content = reportPage(Built.Ready, tmp, linkFailed = true))

    @Test
    @Config(qualifiers = LINK_FAILED_FS200)
    fun reportLinkFailedFs200() = compose.app("report-link-failed", fontScale = RV_FS200, whole = true, content = reportPage(Built.Ready, tmp, linkFailed = true))

    @Test fun intervalLight() = compose.app("settings-interval", dark = false) { Interval() }

    @Test fun intervalDark() = compose.app("settings-interval", dark = true) { Interval() }

    @Test fun intervalFs115() = compose.app("settings-interval", fontScale = RV_FS115) { Interval() }

    @Test fun intervalFs200() = compose.app("settings-interval", fontScale = RV_FS200) { Interval() }

    @Test fun logLevelLight() = compose.app("settings-log-level", dark = false) { LogLevel() }

    @Test fun logLevelDark() = compose.app("settings-log-level", dark = true) { LogLevel() }

    @Test fun logLevelFs115() = compose.app("settings-log-level", fontScale = RV_FS115) { LogLevel() }

    @Test fun logLevelFs200() = compose.app("settings-log-level", fontScale = RV_FS200) { LogLevel() }

    /** GeoIP/GeoSite's 自動更新 (sample, q-p6a-geointerval): the stored 每週. */
    @Test fun geoIntervalLight() = compose.app("settings-geo-interval", dark = false) { GeoIntervalRoute(FakeSettingsStore(), onBack = {}) }

    @Test fun geoIntervalDark() = compose.app("settings-geo-interval", dark = true) { GeoIntervalRoute(FakeSettingsStore(), onBack = {}) }

    @Test fun geoIntervalFs115() = compose.app("settings-geo-interval", fontScale = RV_FS115) { GeoIntervalRoute(FakeSettingsStore(), onBack = {}) }

    @Test fun geoIntervalFs200() = compose.app("settings-geo-interval", fontScale = RV_FS200) { GeoIntervalRoute(FakeSettingsStore(), onBack = {}) }

    /** The core refuses 除錯: the banner says so, with 重試. */
    @Test fun logLevelRefusedLight() = compose.app("settings-log-level-refused", dark = false, before = { pickDebug() }) { RefusedLogLevel() }

    @Test fun logLevelRefusedDark() = compose.app("settings-log-level-refused", dark = true, before = { pickDebug() }) { RefusedLogLevel() }

    @Test fun logLevelRefusedFs200() = compose.app("settings-log-level-refused", fontScale = RV_FS200, before = { pickDebug() }) { RefusedLogLevel() }

    @Test fun languageLight() = compose.app("settings-language", dark = false) { Language() }

    @Test fun languageDark() = compose.app("settings-language", dark = true) { Language() }

    @Test fun languageFs115() = compose.app("settings-language", fontScale = RV_FS115) { Language() }

    @Test fun languageFs200() = compose.app("settings-language", fontScale = RV_FS200) { Language() }

    @Test fun labLight() = compose.app("settings-lab", dark = false, whole = true) { LabRoute(FakeSettingsStore(), onBack = {}, onRestart = {}) }

    /** The dark sample shows 效能模式 on: the user turns it on. */
    @Test
    fun labDark() = compose.app("settings-lab", dark = true, whole = true, before = { turnPerformance() }) { LabRoute(FakeSettingsStore(), onBack = {}, onRestart = {}) }

    @Test fun labFs115() = compose.app("settings-lab", fontScale = RV_FS115, whole = true) { LabRoute(FakeSettingsStore(), onBack = {}, onRestart = {}) }

    @Test fun labFs200() = compose.app("settings-lab", fontScale = RV_FS200, whole = true) { LabRoute(FakeSettingsStore(), onBack = {}, onRestart = {}) }

    /** 效能模式 turned: the banner over the card says it takes effect after a restart, with 重開 App. */
    @Test
    fun labRestartLight() = compose.app("settings-lab-restart", dark = false, whole = true, before = { turnPerformance() }) { LabRoute(FakeSettingsStore(), onBack = {}, onRestart = {}) }

    @Test
    fun labRestartFs200() =
        compose.app("settings-lab-restart", fontScale = RV_FS200, whole = true, before = { turnPerformance() }) { LabRoute(FakeSettingsStore(), onBack = {}, onRestart = {}) }

    @Test
    @Config(qualifiers = THEME_PAGE)
    fun themeLight() = compose.app("settings-theme", dark = false, whole = true) { Theme() }

    @Test
    @Config(qualifiers = THEME_PAGE)
    fun themeDark() = compose.app("settings-theme", dark = true, whole = true) { Theme() }

    @Test
    @Config(qualifiers = THEME_PAGE_FS200)
    fun themeFs200() = compose.app("settings-theme", fontScale = RV_FS200, whole = true) { Theme() }

    @Test fun dnsLight() = compose.app("settings-dns", dark = false) { DnsRoute(FakeEngine(), onBack = {}) }

    @Test fun dnsDark() = compose.app("settings-dns", dark = true) { DnsRoute(FakeEngine(), onBack = {}) }

    @Test fun dnsFs200() = compose.app("settings-dns", fontScale = RV_FS200) { DnsRoute(FakeEngine(), onBack = {}) }

    /** 自訂 open on the core's two servers, over the page. */
    @Test fun dnsCustomLight() = compose.app("settings-dns-custom", dark = false) { DnsCustom(DnsUiState(SERVERS, true, TYPED)) }

    @Test fun dnsCustomDark() = compose.app("settings-dns-custom", dark = true) { DnsCustom(DnsUiState(SERVERS, true, TYPED)) }

    /** A name, which the core refuses: the sheet stays open and says what it takes. */
    @Test
    fun dnsCustomInvalidLight() = compose.app("settings-dns-custom-invalid", dark = false) { DnsCustom(DnsUiState(SERVERS, true, "dns.example", ErrorCode.InvalidArgument)) }

    @Test
    fun dnsCustomInvalidFs200() =
        compose.app("settings-dns-custom-invalid", fontScale = RV_FS200) { DnsCustom(DnsUiState(SERVERS, true, "dns.example", ErrorCode.InvalidArgument)) }

    /** The DNS page under 自訂's sheet, drawn open in the page's window as the other sheets' shots are. */
    @Composable
    private fun DnsCustom(state: DnsUiState) {
        Box(Modifier.fillMaxSize()) {
            RvDnsScreen(rvDnsUpstream(state.servers), onSelect = {}, onBack = {}, onCustom = {})
            RvSheetLayout({ 1f }, {}, stringResource(R.string.rv_settings_dns_custom_title)) { RvDnsCustomContent(state, onText = {}, onSave = {}, onDismiss = {}) }
        }
    }

    /** 更新間隔's 自訂 open on a typed count, over the page. */
    @Test fun intervalCustomLight() = compose.app("settings-interval-custom", dark = false) { IntervalCustom(IntervalUiState(RvData.UPDATE_HOURS, true, "36")) }

    @Test fun intervalCustomDark() = compose.app("settings-interval-custom", dark = true) { IntervalCustom(IntervalUiState(RvData.UPDATE_HOURS, true, "36")) }

    /** A count past a week: the sheet stays open and says the range. */
    @Test
    fun intervalCustomInvalidLight() = compose.app("settings-interval-custom-invalid", dark = false) { IntervalCustom(IntervalUiState(RvData.UPDATE_HOURS, true, "200", true)) }

    @Test
    fun intervalCustomInvalidFs200() =
        compose.app("settings-interval-custom-invalid", fontScale = RV_FS200) { IntervalCustom(IntervalUiState(RvData.UPDATE_HOURS, true, "200", true)) }

    /** The 更新間隔 page under 自訂's sheet, drawn open in the page's window as DNS's is. */
    @Composable
    private fun IntervalCustom(state: IntervalUiState) {
        Box(Modifier.fillMaxSize()) {
            RvIntervalScreen(state.selected, RvData.UPDATE_INTERVALS, onSelect = {}, onBack = {}, onCustom = {})
            RvSheetLayout({ 1f }, {}, stringResource(R.string.rv_settings_interval_custom_title)) {
                RvIntervalCustomContent(state, UpdateInterval.RANGE, onText = {}, onSave = {}, onDismiss = {})
            }
        }
    }

    @Composable
    private fun Theme() =
        ThemeRoute(FakeSettingsStore(), seedSchemes(), RV_SAMPLE_THEME_PREVIEW, onBack = {})

    @Composable
    private fun Interval() = IntervalRoute(sampleStore(), onBack = {})

    @Composable
    private fun LogLevel() = LogLevelRoute(InfoLevels, onBack = {})

    @Composable
    private fun RefusedLogLevel() = LogLevelRoute(RefusingLevels(), onBack = {})

    private fun ComposeContentTestRule.pickDebug() = onNodeWithText("除錯").performClick()

    @Composable
    private fun Language() = LanguageRoute(sampleStore(), onBack = {})
}

/** The English pages, light: the app's golden, and for the pages with no English golden the sample page, as the panels' approved column. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RV_EN)
class SettingsAppEnShotTest {
    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    @Config(qualifiers = "+h2040dp")
    fun settings() = compose.app("settings-en", dark = false, whole = true, content = settingsPage())

    @Test fun lab() = compose.app("settings-lab-en", dark = false, whole = true) { LabRoute(FakeSettingsStore(), onBack = {}, onRestart = {}) }

    @Test
    fun labRestart() =
        compose.app("settings-lab-restart-en", dark = false, whole = true, before = { turnPerformance() }) { LabRoute(FakeSettingsStore(), onBack = {}, onRestart = {}) }

    @Test
    @Config(qualifiers = READY_EN)
    fun report() = compose.app("report-en", dark = false, whole = true, content = reportPage(Built.Ready, tmp))

    @Test
    @Config(qualifiers = READY_EN)
    fun reportSample() = compose.sample("report-en") {
        RvReportScreen(RvReportBuild.Ready(RvRedaction()), rvSampleReport, RvRedaction(), onRedaction = {}, onBack = {}, actions = rvReportActions())
    }

    @Test fun interval() = compose.app("settings-interval-en", dark = false) { IntervalRoute(sampleStore(), onBack = {}) }

    @Test fun intervalSample() = compose.sample("settings-interval-en") { RvIntervalScreen(RvData.UPDATE_HOURS, RvData.UPDATE_INTERVALS, onSelect = {}, onBack = {}, onCustom = {}) }

    @Test fun logLevel() = compose.app("settings-log-level-en", dark = false) { LogLevelRoute(InfoLevels, onBack = {}) }

    @Test fun geoInterval() = compose.app("settings-geo-interval-en", dark = false) { GeoIntervalRoute(FakeSettingsStore(), onBack = {}) }

    @Test fun logLevelSample() = compose.sample("settings-log-level-en") { RvLogLevelScreen(RvLogLevel.Info, onSelect = {}, onBack = {}) }

    /** The phone's language as the sample fixes it, 繁體中文, so the page is the sample's. */
    @Test fun language() = compose.app("settings-language-en", dark = false) { LanguageRoute(sampleStore(), onBack = {}, system = RvLanguage.TraditionalChinese) }

    @Test
    fun languageSample() = compose.sample("settings-language-en") {
        RvLanguageScreen(dev.doona.android.feature.model.RvLanguage.TraditionalChinese, dev.doona.android.feature.model.RvLanguage.TraditionalChinese, onSelect = {}, onBack = {})
    }
}

/**
 * Draws [content] in the phone's frame, light or [dark], at [fontScale] ([whole]: its window holds the whole page; [busy]:
 * at animator scale 0, so a busy bar rests half full, as the `proto-rv-` golden), and records `app-rv-<name>-<variant>`.
 */
private fun ComposeContentTestRule.app(
    name: String,
    dark: Boolean = false,
    fontScale: Float = 1f,
    whole: Boolean = false,
    busy: Boolean = false,
    before: ComposeContentTestRule.() -> Unit = {},
    content: @Composable () -> Unit,
) {
    rvAnimatorScale(if (busy) 0f else 1f)
    rvContent(dark, fontScale, content = content)
    waitForIdle()
    before()
    waitForIdle()
    if (whole) assertWhole()
    drawFully()
    val variant = when {
        fontScale == RV_FS115 -> "fs115"
        fontScale == RV_FS200 -> "fs200"
        dark -> "dark"
        else -> "light"
    }
    val golden = "app-rv-$name-$variant"
    rvRowRules(golden)
    onRoot().captureToImage().asAndroidBitmap().captureRoboImage("$SHOTS/$golden.png")
}

/** The sample page in English, for the panels' approved column where no English golden exists. */
private fun ComposeContentTestRule.sample(name: String, content: @Composable () -> Unit) {
    rvContent(dark = false, content = content)
    drawFully()
    savePanel("build/panels/proto-rv-$name-light.png")
}

/** 設定 on its ViewModel over the sample engine and store, the ViewModel made before the page is composed. */
private fun settingsPage(): @Composable () -> Unit {
    val vm = SettingsViewModel(settingsEngine(), sampleStore())
    return { SettingsPage(vm, NoSystem, noNav(), rvSampleVersions.app, onTab = {}) }
}

/**
 * 回報問題 on its ViewModel, with the report [built] as it stands, the ViewModel made before the page is composed;
 * [linkFailed]: 在 GitHub 回報 could not open its link.
 */
private fun reportPage(built: Built, tmp: TemporaryFolder, linkFailed: Boolean = false): @Composable () -> Unit {
    val vm = ReportViewModel(reportEngine(), ShotReports(built, tmp), SampleFacts)
    if (linkFailed) vm.linkFailed()
    return { ReportPage(vm, onBack = {}) }
}

/**
 * The sample's settings: 繁體中文, the sample's interval, and the GeoIP/GeoSite copy the sample ships, so the page is
 * the approved one (owner, 2026-11-26: approved screens are frozen; the app's own date is q-p6a-geodate).
 */
private fun sampleStore() = FakeSettingsStore(
    updateHours = RvData.UPDATE_HOURS,
    shippedGeoDate = RV_SAMPLE_GEO_SHIPPED.toString(),
    languages = "zh-TW",
)

/** The RV sample engine, connected, with the sample's saved per-app policy. */
private fun settingsEngine(): Engine {
    val engine = rvSampleEngine()
    val saved = RvPerAppData.saved
    check(saved.policy == RvAppPolicy.Only)
    runBlocking { engine.setPerApp(PerApp(AppPolicy.OnlyListed, saved.packages)) }
    return engine
}

/** The RV sample engine with the sample report's two crash files and, in its catalog, the sample report's node count. */
private fun reportEngine(): Engine {
    val engine = rvSampleEngine().apply {
        crashes = listOf(CrashFile("1795300000000.txt", 2_048), CrashFile("1795300100000.txt", 1_024))
    }
    val catalog = checkNotNull(engine.catalog.value)
    val nodes = catalog.nodeMembers().take(rvSampleReport.nodes)
    check(nodes.size == rvSampleReport.nodes)
    val sampled = catalog.copy(groups = listOf(catalog.groups.first().copy(members = nodes)))
    return object : Engine by engine {
        override val catalog: StateFlow<Catalog?> = MutableStateFlow(sampled)
    }
}

/** The sample report's app, phone and event count ([rvSampleReport]). */
private object SampleFacts : ReportFacts {
    override val app = rvSampleReport.app
    override val device = rvSampleReport.device

    override suspend fun events() = rvSampleReport.events
}

/** Where the report stands when the page is drawn. */
private enum class Built { Ready, Building, Failed }

/**
 * A [ReportExporter] whose export is [built]: a written report (its share over an export in [tmp] that is never sent),
 * one that never finishes, or one that could not be written.
 */
private class ShotReports(private val built: Built, private val tmp: TemporaryFolder) : ReportExporter {
    override val crashOffered = MutableStateFlow(false)

    override fun crashShown() = Unit

    override fun dismissCrash() = Unit

    override suspend fun export(options: RedactionOptions, failure: ReportFailure?): ReportShare? = when (built) {
        Built.Building -> awaitCancellation()
        Built.Failed -> null
        Built.Ready -> ReportShare(android.content.Intent(), ReportZip(tmp.root.resolve("report.zip"), "t", null, ISSUE), shotExport(tmp))
    }
}

/** An export over [tmp] for a share that is never sent: only its returned and notOpened are reachable. */
private fun shotExport(tmp: TemporaryFolder): ReportExport {
    val engine = rvSampleEngine()
    val scope = CoroutineScope(Dispatchers.Unconfined)
    val files = CrashFiles(tmp.root.resolve("crash"))
    val app = AppInfo("0.1.0", 1, "debug", listOf("arm64-v8a"), "15", 35, "Google", "Pixel 8")
    return ReportExport(
        engine,
        files,
        CrashOffer(files, tmp.root.resolve("seen")),
        { tmp.root.resolve("reports") },
        ExportTokens { tmp.root.resolve("tokens.json") },
        { app },
        scope,
        Dispatchers.Unconfined,
        LogLevelController(engine::setLogLevel, scope) { 0L },
        testFlowEvents(Dispatchers.Unconfined),
        { emptyList() },
    ) { 0L }
}

private const val ISSUE = "https://github.com/Zakkaus/doona-android/issues/new"

/** The core at 資訊, as the sample's 日誌等級 shows it. */
private object InfoLevels : LogLevels {
    override val level = MutableStateFlow(LogLevel.Info)

    override suspend fun set(level: LogLevel): Result<Unit> = Result.success(Unit)
}

/** A core at 資訊 that refuses every other level. */
private class RefusingLevels : LogLevels {
    override val level = MutableStateFlow(LogLevel.Info)

    override suspend fun set(level: LogLevel): Result<Unit> = Result.failure(EngineException(ErrorCode.Internal))
}

/** Android, which a golden never reaches. */
private object NoSystem : SettingsSystem {
    override fun openVpnSettings() = Unit

    override fun openReleases() = Unit

    override fun openRepository() = Unit

    override fun openPrivacy() = Unit

    override fun requestTile(onAnswer: (TileAnswer) -> Unit) = Unit
}

private fun noNav() = SettingsNav({}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {})

/** As tall as each page at each font size, as its approved golden (RvLabShotTest's approved 設定, RvReportShotTest). */
private const val LONG_PAGE = "+h1990dp"

/** 主題 whole: the window the page fills at 1× and 2×. */
private const val THEME_PAGE = "+h975dp"
private const val THEME_PAGE_FS200 = "+h1062dp"

/** The core's servers in [FakeEngine], and 自訂 as it opens on them. */
private val SERVERS = listOf("1.1.1.1:53", "8.8.8.8:53")
private const val TYPED = "1.1.1.1, 8.8.8.8"
private const val LONG_PAGE_FS115 = "+h2015dp"
private const val LONG_PAGE_FS200 = "+h2589dp"

/** The English report is longer: its window holds the whole page. */
private const val READY_EN = "+h1300dp"
private const val READY = "+h1177dp"
private const val READY_FS115 = "+h1213dp"
private const val READY_FS200 = "+h1820dp"
private const val BUILDING = "+h1259dp"
private const val BUILDING_FS115 = "+h1295dp"
private const val BUILDING_FS200 = "+h1959dp"
private const val FAILED = "+h1335dp"
private const val FAILED_FS115 = "+h1376dp"
private const val FAILED_FS200 = "+h2053dp"
private const val LINK_FAILED = "+h1357dp"
private const val LINK_FAILED_FS200 = "+h2062dp"

/** Turns 實驗室's 效能模式, the page's one switch. */
private fun ComposeContentTestRule.turnPerformance() {
    onNode(isToggleable()).performClick()
}
