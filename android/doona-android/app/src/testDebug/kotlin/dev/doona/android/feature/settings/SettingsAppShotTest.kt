package dev.doona.android.feature.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import com.github.takahirom.roborazzi.captureRoboImage
import dev.doona.android.R
import dev.doona.android.app.APP_THEME_PREVIEW
import dev.doona.android.data.settings.UpdateInterval
import dev.doona.android.designsystem.rv.RvSheetLayout
import dev.doona.android.engine.AppPolicy
import dev.doona.android.engine.Engine
import dev.doona.android.engine.EngineException
import dev.doona.android.engine.ErrorCode
import dev.doona.android.engine.PerApp
import dev.doona.android.engine.fake.FakeEngine
import dev.doona.android.engine.fake.rvSampleEngine
import dev.doona.android.feature.model.RvAppPolicy
import dev.doona.android.feature.model.RvLanguage
import dev.doona.android.feature.policies.RuleEditRoute
import dev.doona.android.honk.HonkFixtures
import dev.doona.android.honk.NativeSettings
import dev.doona.android.honk.PolicyHonkFixtures
import dev.doona.android.honk.SettingsHonk
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
import dev.doona.android.prototype.rv.settings.rvSampleVersions
import dev.doona.android.testing.drawFully
import dev.doona.android.testing.savePanel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.ZoneId

private val SHOTS = System.getProperty("doona.shots") ?: "src/test/screenshots"

/**
 * 設定 and the pages 設定 opens as the app builds them (`app-rv-*`): the RV sample engine and a store holding
 * the sample's settings, through each page's ViewModel and route, into the page. Each is the state of its `proto-rv-*`
 * golden (same name after `app-rv-`), so a pair differs only where the app cannot carry the sample's value.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rTW-w412dp-h915dp-notnight-xhdpi")
class SettingsAppShotTest {
    @get:Rule
    val compose = createComposeRule()

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

    @Test fun intervalLight() = compose.app("settings-interval", dark = false) { Interval() }

    @Test fun intervalDark() = compose.app("settings-interval", dark = true) { Interval() }

    @Test fun intervalFs115() = compose.app("settings-interval", fontScale = RV_FS115) { Interval() }

    @Test fun intervalFs200() = compose.app("settings-interval", fontScale = RV_FS200) { Interval() }

    @Test fun logLevelLight() = compose.app("settings-log-level", dark = false) { LogLevel() }

    @Test fun logLevelDark() = compose.app("settings-log-level", dark = true) { LogLevel() }

    @Test fun logLevelFs115() = compose.app("settings-log-level", fontScale = RV_FS115) { LogLevel() }

    @Test fun logLevelFs200() = compose.app("settings-log-level", fontScale = RV_FS200) { LogLevel() }

    /** GeoIP/GeoSite's 自動更新 (sample, q-p6a-geointerval): honk's 每週, 預設 under 每天 (q-k4-geodefault). */
    @Test fun geoIntervalLight() = compose.app("settings-geo-interval", dark = false) { GeoIntervalPage(remember { sampleInterval() }, onBack = {}) }

    @Test fun geoIntervalDark() = compose.app("settings-geo-interval", dark = true) { GeoIntervalPage(remember { sampleInterval() }, onBack = {}) }

    @Test fun geoIntervalFs115() = compose.app("settings-geo-interval", fontScale = RV_FS115) { GeoIntervalPage(remember { sampleInterval() }, onBack = {}) }

    @Test fun geoIntervalFs200() = compose.app("settings-geo-interval", fontScale = RV_FS200) { GeoIntervalPage(remember { sampleInterval() }, onBack = {}) }

    /** 自動更新 with the VPN off (q-k4-geooffline): honk does not answer, so the rows are disabled and the footer reads 未連線. */
    @Test fun geoIntervalOfflineLight() = compose.app("settings-geo-interval-offline", dark = false) { GeoIntervalPage(remember { offlineInterval() }, onBack = {}) }

    @Test fun geoIntervalOfflineDark() = compose.app("settings-geo-interval-offline", dark = true) { GeoIntervalPage(remember { offlineInterval() }, onBack = {}) }

    @Test fun geoIntervalOfflineFs200() = compose.app("settings-geo-interval-offline", fontScale = RV_FS200) { GeoIntervalPage(remember { offlineInterval() }, onBack = {}) }

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

    /** 主題 as the app draws it: its own preview, on [dev.doona.android.app.ThemePreviewSample]. */
    @Test
    @Config(qualifiers = THEME_PAGE)
    fun themeAppPreview() = compose.app("settings-theme-app", dark = false, whole = true) {
        ThemeRoute(FakeSettingsStore(), seedSchemes(), APP_THEME_PREVIEW, onBack = {})
    }

    @Test fun dnsLight() = compose.app("settings-dns", dark = false) { DnsRoute(PolicyHonkFixtures.rvSample(), onBack = {}, onServers = {}, onRules = {}) }

    @Test fun dnsDark() = compose.app("settings-dns", dark = true) { DnsRoute(PolicyHonkFixtures.rvSample(), onBack = {}, onServers = {}, onRules = {}) }

    @Test fun dnsFs200() = compose.app("settings-dns", fontScale = RV_FS200) { DnsRoute(PolicyHonkFixtures.rvSample(), onBack = {}, onServers = {}, onRules = {}) }

    /** 自訂 open on the core's two servers, over the page. */
    @Test fun dnsServersLight() = compose.app("settings-dns-servers", dark = false, before = { shows("cloudflare") }) { DnsServersRoute(PolicyHonkFixtures.rvSample(), onBack = {}, onRules = {}) }

    @Test fun dnsServersDark() = compose.app("settings-dns-servers", dark = true, before = { shows("cloudflare") }) { DnsServersRoute(PolicyHonkFixtures.rvSample(), onBack = {}, onRules = {}) }

    @Test
    fun dnsServersCanRetryAfterTheFirstReadFails() {
        val honk = PolicyHonkFixtures.rvSample()
        honk.on("GET", "/api/v1/config", 503, HonkFixtures.body("errors/snapshot-unavailable"))
        compose.rvContent(dark = false) { DnsServersRoute(honk, onBack = {}, onRules = {}) }
        compose.shows("重試")
        honk.on("GET", "/api/v1/config", 200, HonkFixtures.body("config/rv-sample"))
        compose.onNodeWithText("重試").performClick()
        compose.shows("cloudflare")
    }

    @Test fun dnsRulesLight() = compose.app("settings-dns-rules", dark = false, before = { shows("qname(geosite:cn)") }) { DnsRulesRoute(PolicyHonkFixtures.rvSample(), onBack = {}) }

    @Test fun dnsRulesDark() = compose.app("settings-dns-rules", dark = true, before = { shows("qname(geosite:cn)") }) { DnsRulesRoute(PolicyHonkFixtures.rvSample(), onBack = {}) }

    /** A DNS statement the form cannot hold (a GeoSite match): its line in 依據, then 動作 and its servers. */
    @Test fun dnsRuleEditLight() = compose.app("settings-dns-rule-edit", dark = false, before = { shows("alidns") }) {
        DnsRuleEditRoute(PolicyHonkFixtures.rvSample(), query = true, ruleId = "dns_request:1", onBack = {}, onOpenSource = { _, _ -> })
    }

    /** 全部規則 → github.com, on the app's honk (the sample): a suffix rule to Proxy, sixth of thirteen. */
    @Test fun ruleEditLight() = compose.app("rule-edit", dark = false, before = { shows("github.com") }) { RuleEditRoute("traffic:5", onBack = {}, onOpenSource = { _, _ -> }) }

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
            RvDnsScreen(rvDnsUpstream(state.servers), onSelect = {}, onBack = {}, onCustom = {}, onServers = {})
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

    @Test
    @Config(qualifiers = "+h2122dp")
    fun settings() = compose.app("settings-en", dark = false, whole = true, content = settingsPage())

    @Test fun lab() = compose.app("settings-lab-en", dark = false, whole = true) { LabRoute(FakeSettingsStore(), onBack = {}, onRestart = {}) }

    @Test
    fun labRestart() =
        compose.app("settings-lab-restart-en", dark = false, whole = true, before = { turnPerformance() }) { LabRoute(FakeSettingsStore(), onBack = {}, onRestart = {}) }

    @Test fun interval() = compose.app("settings-interval-en", dark = false) { IntervalRoute(sampleStore(), onBack = {}) }

    @Test fun intervalSample() = compose.sample("settings-interval-en") { RvIntervalScreen(RvData.UPDATE_HOURS, RvData.UPDATE_INTERVALS, onSelect = {}, onBack = {}, onCustom = {}) }

    @Test fun logLevel() = compose.app("settings-log-level-en", dark = false) { LogLevelRoute(InfoLevels, onBack = {}) }

    @Test fun geoInterval() = compose.app("settings-geo-interval-en", dark = false) { GeoIntervalPage(remember { sampleInterval() }, onBack = {}) }

    @Test fun geoIntervalOffline() = compose.app("settings-geo-interval-offline-en", dark = false) { GeoIntervalPage(remember { offlineInterval() }, onBack = {}) }

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
internal fun ComposeContentTestRule.app(
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

/** Waits for [text], which honk's read puts on the page: the read runs on Dispatchers.IO, which compose does not track. */
private fun ComposeContentTestRule.shows(text: String) = waitUntil(READ_MS) { onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }

private const val READ_MS = 5_000L

/** The sample page in English, for the panels' approved column where no English golden exists. */
internal fun ComposeContentTestRule.sample(name: String, content: @Composable () -> Unit) {
    rvContent(dark = false, content = content)
    drawFully()
    savePanel("build/panels/proto-rv-$name-light.png")
}

/** 設定 on its ViewModel over the sample engine and store, the ViewModel made before the page is composed. */
private fun settingsPage(): @Composable () -> Unit {
    val vm = SettingsViewModel(settingsEngine(), sampleHonk(), sampleStore(), io = Dispatchers.Unconfined)
    return { SettingsPage(vm, NoSystem, noNav(), rvSampleVersions.app, onTab = {}) }
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

/** honk with the sample's GeoIP/GeoSite data (the copy the sample ships) and its 每週 schedule, the day read in UTC. */
private fun sampleHonk() = NativeSettings(SettingsHonk.sample()) { ZoneId.of("UTC") }

/** 自動更新 over [sampleHonk]. */
private fun sampleInterval() = GeoIntervalViewModel(sampleHonk(), Dispatchers.Unconfined)

/** 自動更新 over a honk that is not running. */
private fun offlineInterval() = GeoIntervalViewModel(NativeSettings(SettingsHonk()) { ZoneId.of("UTC") }, Dispatchers.Unconfined)

/** The RV sample engine, connected, with the sample's saved per-app policy. */
private fun settingsEngine(): Engine {
    val engine = rvSampleEngine()
    val saved = RvPerAppData.saved
    check(saved.policy == RvAppPolicy.Only)
    runBlocking { engine.setPerApp(PerApp(AppPolicy.OnlyListed, saved.packages)) }
    return engine
}

/** The core at 資訊, as the sample's 日誌等級 shows it. */
private object InfoLevels : LogLevels {
    override val level = MutableStateFlow(RvLogLevel.Info)

    override suspend fun set(level: RvLogLevel): Result<Unit> = Result.success(Unit)
}

/** A core at 資訊 that refuses every other level. */
private class RefusingLevels : LogLevels {
    override val level = MutableStateFlow(RvLogLevel.Info)

    override suspend fun set(level: RvLogLevel): Result<Unit> = Result.failure(EngineException(ErrorCode.Internal))
}

/** Android, which a golden never reaches. */
private object NoSystem : SettingsSystem {
    override fun openVpnSettings() = Unit

    override fun openReleases() = Unit

    override fun openRepository() = Unit

    override fun openPrivacy() = Unit

    override fun requestTile(onAnswer: (TileAnswer) -> Unit) = Unit
}

private fun noNav() = SettingsNav({}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, config = {})

/** As tall as each page at each font size, as its approved golden (RvLabShotTest's approved 設定, RvReportShotTest). */
private const val LONG_PAGE = "+h2072dp"

/** 主題 whole: the window the page fills at 1× and 2×. */
private const val THEME_PAGE = "+h975dp"
private const val THEME_PAGE_FS200 = "+h1062dp"

/** The core's servers in [FakeEngine], and 自訂 as it opens on them. */
private val SERVERS = listOf("1.1.1.1:53", "8.8.8.8:53")
private const val TYPED = "1.1.1.1, 8.8.8.8"
private const val LONG_PAGE_FS115 = "+h2097dp"
private const val LONG_PAGE_FS200 = "+h2696dp"

/** Turns 實驗室's 效能模式, the page's one switch. */
private fun ComposeContentTestRule.turnPerformance() {
    onNode(isToggleable()).performClick()
}
