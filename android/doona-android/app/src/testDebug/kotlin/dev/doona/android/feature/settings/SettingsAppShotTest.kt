package dev.doona.android.feature.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import com.github.takahirom.roborazzi.captureRoboImage
import dev.doona.android.app.AppSettingsStore
import dev.doona.android.data.settings.LogLevelController
import dev.doona.android.engine.AppPolicy
import dev.doona.android.engine.CrashFile
import dev.doona.android.engine.Engine
import dev.doona.android.engine.EngineException
import dev.doona.android.engine.ErrorCode
import dev.doona.android.engine.LogLevel
import dev.doona.android.engine.PerApp
import dev.doona.android.engine.fake.rvSampleEngine
import dev.doona.android.feature.model.RvAppPolicy
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
import dev.doona.android.report.testFlowEvents
import dev.doona.android.testing.drawFully
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
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

    @Test fun intervalLight() = compose.app("settings-interval", dark = false) { Interval() }

    @Test fun intervalDark() = compose.app("settings-interval", dark = true) { Interval() }

    @Test fun intervalFs115() = compose.app("settings-interval", fontScale = RV_FS115) { Interval() }

    @Test fun intervalFs200() = compose.app("settings-interval", fontScale = RV_FS200) { Interval() }

    @Test fun logLevelLight() = compose.app("settings-log-level", dark = false) { LogLevel() }

    @Test fun logLevelDark() = compose.app("settings-log-level", dark = true) { LogLevel() }

    @Test fun logLevelFs115() = compose.app("settings-log-level", fontScale = RV_FS115) { LogLevel() }

    @Test fun logLevelFs200() = compose.app("settings-log-level", fontScale = RV_FS200) { LogLevel() }

    /** The core refuses 除錯: the banner says so, with 重試. */
    @Test fun logLevelRefusedLight() = compose.app("settings-log-level-refused", dark = false, before = { pickDebug() }) { RefusedLogLevel() }

    @Test fun logLevelRefusedDark() = compose.app("settings-log-level-refused", dark = true, before = { pickDebug() }) { RefusedLogLevel() }

    @Test fun logLevelRefusedFs200() = compose.app("settings-log-level-refused", fontScale = RV_FS200, before = { pickDebug() }) { RefusedLogLevel() }

    @Test fun languageLight() = compose.app("settings-language", dark = false) { Language() }

    @Test fun languageDark() = compose.app("settings-language", dark = true) { Language() }

    @Test fun languageFs115() = compose.app("settings-language", fontScale = RV_FS115) { Language() }

    @Test fun languageFs200() = compose.app("settings-language", fontScale = RV_FS200) { Language() }

    @Test fun labLight() = compose.app("settings-lab", dark = false, whole = true) { LabRoute(onBack = {}) }

    /** The dark sample shows 效能模式 on: the user turns it on. */
    @Test
    fun labDark() = compose.app("settings-lab", dark = true, whole = true, before = { onNode(isToggleable()).performClick() }) { LabRoute(onBack = {}) }

    @Test fun labFs115() = compose.app("settings-lab", fontScale = RV_FS115, whole = true) { LabRoute(onBack = {}) }

    @Test fun labFs200() = compose.app("settings-lab", fontScale = RV_FS200, whole = true) { LabRoute(onBack = {}) }

    @Composable
    private fun Interval() = IntervalRoute(sampleStore(), onCustom = {}, onBack = {})

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
    @Config(qualifiers = "+h1872dp")
    fun settings() = compose.app("settings-en", dark = false, whole = true, content = settingsPage())

    @Test fun lab() = compose.app("settings-lab-en", dark = false, whole = true) { LabRoute(onBack = {}) }

    @Test
    @Config(qualifiers = READY_EN)
    fun report() = compose.app("report-en", dark = false, whole = true, content = reportPage(Built.Ready, tmp))

    @Test
    @Config(qualifiers = READY_EN)
    fun reportSample() = compose.sample("report-en") {
        RvReportScreen(RvReportBuild.Ready(RvRedaction()), rvSampleReport, RvRedaction(), onRedaction = {}, onBack = {}, actions = rvReportActions())
    }

    @Test fun interval() = compose.app("settings-interval-en", dark = false) { IntervalRoute(sampleStore(), onCustom = {}, onBack = {}) }

    @Test fun intervalSample() = compose.sample("settings-interval-en") { RvIntervalScreen(RvData.UPDATE_HOURS, RvData.UPDATE_INTERVALS, onSelect = {}, onBack = {}, onCustom = {}) }

    @Test fun logLevel() = compose.app("settings-log-level-en", dark = false) { LogLevelRoute(InfoLevels, onBack = {}) }

    @Test fun logLevelSample() = compose.sample("settings-log-level-en") { RvLogLevelScreen(RvLogLevel.Info, onSelect = {}, onBack = {}) }

    @Test fun language() = compose.app("settings-language-en", dark = false) { LanguageRoute(sampleStore(), onBack = {}) }

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
    onRoot().captureToImage().asAndroidBitmap().captureRoboImage("build/panels/proto-rv-$name-light.png")
}

/** 設定 on its ViewModel over the sample engine and store, the ViewModel made before the page is composed. */
private fun settingsPage(): @Composable () -> Unit {
    val vm = SettingsViewModel(settingsEngine(), sampleStore())
    return { SettingsPage(vm, NoSystem, noNav(), rvSampleVersions.app, onTab = {}) }
}

/** 回報問題 on its ViewModel, with the report [built] as it stands, the ViewModel made before the page is composed. */
private fun reportPage(built: Built, tmp: TemporaryFolder): @Composable () -> Unit {
    val vm = ReportViewModel(reportEngine(), ShotReports(built, tmp), SampleFacts)
    return { ReportPage(vm, onBack = {}) }
}

/** The sample's settings: 繁體中文, the sample's interval, and the GeoIP/GeoSite copy the app ships. */
private fun sampleStore() = FakeSettingsStore(updateHours = RvData.UPDATE_HOURS, shippedGeoDate = AppSettingsStore.SHIPPED_GEO_DATE, languages = "zh-TW")

/** The RV sample engine, connected, with the sample's saved per-app policy. */
private fun settingsEngine(): Engine {
    val engine = rvSampleEngine()
    val saved = RvPerAppData.saved
    check(saved.policy == RvAppPolicy.Only)
    runBlocking { engine.setPerApp(PerApp(AppPolicy.OnlyListed, saved.packages)) }
    return engine
}

/** The RV sample engine with the sample report's two crash files. */
private fun reportEngine(): Engine = rvSampleEngine().apply {
    crashes = listOf(CrashFile("1795300000000.txt", 2_048), CrashFile("1795300100000.txt", 1_024))
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

    override fun requestTile(onAnswer: (TileAnswer) -> Unit) = Unit
}

private fun noNav() = SettingsNav({}, {}, {}, {}, {}, {}, {}, {}, {})

/** As tall as each page at each font size, as its approved golden (RvLabShotTest's approved 設定, RvReportShotTest). */
private const val LONG_PAGE = "+h1822dp"
private const val LONG_PAGE_FS115 = "+h1847dp"
private const val LONG_PAGE_FS200 = "+h2351dp"

/** The English report is longer: its window holds the whole page. */
private const val READY_EN = "+h1288dp"
private const val READY = "+h1177dp"
private const val READY_FS115 = "+h1209dp"
private const val READY_FS200 = "+h1820dp"
private const val BUILDING = "+h1259dp"
private const val BUILDING_FS115 = "+h1291dp"
private const val BUILDING_FS200 = "+h1959dp"
private const val FAILED = "+h1335dp"
private const val FAILED_FS115 = "+h1371dp"
private const val FAILED_FS200 = "+h2053dp"
