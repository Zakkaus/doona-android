package dev.doona.android.feature.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.text.TextLayoutResult
import dev.doona.android.app.rvAppearance
import dev.doona.android.app.themeAppearance
import dev.doona.android.engine.fake.FakeEngine
import dev.doona.android.feature.model.RvAppearance
import dev.doona.android.feature.model.RvGeoInterval
import dev.doona.android.feature.model.RvThemeMode
import dev.doona.android.prototype.rv.RV_FS200
import dev.doona.android.prototype.rv.rvContent
import dev.doona.android.prototype.rv.seedSchemes
import dev.doona.android.prototype.rv.settings.RV_SAMPLE_THEME_PREVIEW
import dev.doona.android.vpn.NativeSettings
import dev.doona.android.vpn.SettingsHonk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 檢查更新's newer release opens the releases page, and 日誌等級's row its page. 主題 stores each choice as it is made, in
 * the form the app's theme reads; DNS saves a preset and 自訂's servers, IPv6 among them.
 */
@RunWith(RobolectricTestRunner::class)
// Tall enough that 模式 and 自訂 are on screen without a scroll.
@Config(qualifiers = "zh-rTW-w412dp-h1300dp")
class ThemeDnsRouteTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun aThemeChoiceIsStoredAtOnce() {
        val store = FakeSettingsStore()
        compose.rvContent(dark = false) {
            ThemeRoute(store, seedSchemes(), RV_SAMPLE_THEME_PREVIEW, onBack = {})
        }
        compose.onNodeWithText(DARK).performClick()
        compose.waitForIdle()
        assertEquals(RvThemeMode.Dark, store.appearance.value.mode)
        compose.onNode(isToggleable()).performClick()
        compose.waitForIdle()
        assertTrue("跟隨桌布", store.appearance.value.followWallpaper)
    }

    @Test
    fun theStoredFormKeepsEveryChoice() {
        for (mode in RvThemeMode.entries) {
            for (accent in 0..4) {
                for (wallpaper in listOf(false, true)) {
                    val appearance = RvAppearance(mode, wallpaper, accent)
                    assertEquals(appearance, rvAppearance(themeAppearance(appearance)))
                }
            }
        }
        assertTrue(RvAppearance(RvThemeMode.Dark, false, 0).isDark(systemDark = false))
        assertFalse(RvAppearance(RvThemeMode.Light, false, 0).isDark(systemDark = true))
        assertTrue(RvAppearance(RvThemeMode.System, false, 0).isDark(systemDark = true))
    }

    @Test
    fun aPresetAndTypedServersReachTheCore() {
        val engine = FakeEngine()
        compose.rvContent(dark = false) { DnsRoute(engine, onBack = {}) }
        compose.onNodeWithText(QUAD9).performClick()
        compose.waitForIdle()
        assertEquals(listOf("9.9.9.9:53"), servers(engine))

        compose.onNodeWithText(CUSTOM).performClick()
        compose.waitForIdle()
        compose.onNode(hasSetTextAction()).performTextReplacement("223.5.5.5, 1.1.1.1")
        compose.onNodeWithText(SAVE).performClick()
        compose.waitForIdle()
        assertEquals(listOf("223.5.5.5:53", "1.1.1.1:53"), servers(engine))
    }

    /**
     * The core accepts IPv6 servers, bare or as `[addr]:port`, and refuses link-local ones (doona-core `parse_upstreams`);
     * the sheet's hint and error name both forms (s1126b, 2026-11-26 07:14).
     */
    @Test
    fun ipv6ServersReachTheCoreAndALinkLocalOneSaysWhy() {
        val engine = FakeEngine()
        compose.rvContent(dark = false) { DnsRoute(engine, onBack = {}) }
        compose.onNodeWithText(CUSTOM).performClick()
        compose.waitForIdle()
        compose.onNodeWithText(V6_HINT, useUnmergedTree = true).assertFits()
        compose.onNode(hasSetTextAction()).performTextReplacement("2606:4700:4700::1111, [2001:4860:4860::8888]:5353 1.1.1.1")
        compose.onNodeWithText(SAVE).performClick()
        compose.waitForIdle()
        assertEquals(listOf("[2606:4700:4700::1111]:53", "[2001:4860:4860::8888]:5353", "1.1.1.1:53"), servers(engine))

        compose.onNodeWithText(CUSTOM).performClick()
        compose.waitForIdle()
        // Opened again, the field holds the servers as typed: the default port and its brackets dropped.
        compose.onNodeWithText("2606:4700:4700::1111, [2001:4860:4860::8888]:5353, 1.1.1.1").assertExists()
        compose.onNode(hasSetTextAction()).performTextReplacement("fe80::1")
        compose.onNodeWithText(SAVE).performClick()
        compose.waitForIdle()
        compose.onNodeWithText(V6_INVALID, useUnmergedTree = true).assertFits()
        assertEquals(3, servers(engine).size)
    }

    /** At Android's largest font the hint and the error take more than two lines; the library draws them whole. */
    @Test
    fun atFs200TheHintAndTheErrorAreWhole() {
        // The sheet is a window of its own, which takes the system's font scale rather than the page's density.
        RuntimeEnvironment.setFontScale(RV_FS200)
        compose.rvContent(dark = false, fontScale = RV_FS200) { DnsRoute(FakeEngine(), onBack = {}) }
        compose.onNodeWithText(CUSTOM).performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithText(V6_HINT, useUnmergedTree = true).assertFits()
        compose.onNode(hasSetTextAction()).performTextReplacement("fe80::1")
        compose.onNodeWithText(SAVE).performClick()
        compose.waitForIdle()
        compose.onNodeWithText(V6_INVALID, useUnmergedTree = true).assertFits()
    }

    /**
     * 自動更新 under GeoIP/GeoSite (q-p6a-geointerval, 2026-11-26 07:13) names the stored interval and opens its page,
     * where a choice goes to honk.
     */
    @Test
    fun theGeoIntervalRowNamesTheChoiceAndItsPageStoresOne() {
        var opened = 0
        val honk = SettingsHonk.running()
        val native = NativeSettings(honk)
        val vm = SettingsViewModel(FakeEngine(), native, FakeSettingsStore(), io = Dispatchers.Unconfined)
        val interval = GeoIntervalViewModel(native, Dispatchers.Unconfined)
        val nav = SettingsNav({}, {}, {}, {}, {}, geoInterval = { opened++ }, {}, {}, {}, {}, {})
        var page by mutableStateOf(false)
        compose.rvContent(dark = false) {
            if (page) GeoIntervalPage(interval, onBack = {}) else SettingsPage(vm, NoSystem, nav, "0.1.0", onTab = {})
        }
        compose.onNodeWithText(DAILY).performScrollTo().performClick()
        assertEquals(1, opened)

        page = true
        compose.waitForIdle()
        honk.now("GET", "/api/v1/runtime/settings", 200, SettingsHonk.runtimeSettings(enabled = false))
        compose.onNodeWithText(OFF).performClick()
        compose.waitForIdle()
        assertEquals(RvGeoInterval.Off, interval.selected.value)
        assertEquals("""{"geodata":{"auto_update":{"enabled":false}}}""", honk.calls.last { it.method == "PATCH" }.body)
        compose.onNodeWithText(WEEKLY).performClick()
        compose.waitForIdle()
        assertEquals("""{"geodata":{"auto_update":{"enabled":true,"interval_hours":168}}}""", honk.calls.last { it.method == "PATCH" }.body)
    }

    /** 日誌等級's row under 回報問題 (q-p6a-logrow, 2026-11-26 07:13) names the core's level and opens its page. */
    @Test
    fun theLogLevelRowNamesTheLevelAndOpensItsPage() {
        var opened = 0
        val levels = object : LogLevels {
            override val level: StateFlow<RvLogLevel> = MutableStateFlow(RvLogLevel.Debug)

            override suspend fun set(level: RvLogLevel): Result<Unit> = Result.success(Unit)
        }
        val vm = SettingsViewModel(FakeEngine(), NativeSettings(SettingsHonk.running()), FakeSettingsStore(), levels = levels, io = Dispatchers.Unconfined)
        val nav = SettingsNav({}, {}, {}, {}, logLevel = { opened++ }, {}, {}, {}, {}, {}, {})
        compose.rvContent(dark = false) { SettingsPage(vm, NoSystem, nav, "0.1.0", onTab = {}) }
        compose.onNodeWithText(DEBUG).performScrollTo().assertExists()
        compose.onNodeWithText(LOG_LEVEL).performScrollTo().performClick()
        assertEquals(1, opened)
    }

    /** Decision 148 (2026-11-23 21:35): the app downloads nothing; a newer release's banner opens the releases page. */
    @Test
    fun aNewerReleaseOpensTheReleasesPage() {
        var opened = 0
        val system = object : SettingsSystem {
            override fun openVpnSettings() = Unit

            override fun openReleases() {
                opened++
            }

            override fun openRepository() = Unit

            override fun openPrivacy() = Unit

            override fun requestTile(onAnswer: (TileAnswer) -> Unit) = Unit
        }
        val vm = SettingsViewModel(FakeEngine(), NativeSettings(SettingsHonk.running()), FakeSettingsStore(), releases = { Result.success("v9.0.0") }, io = Dispatchers.Unconfined)
        compose.rvContent(dark = false) { SettingsPage(vm, system, SettingsNav({}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}), "0.1.0", onTab = {}) }
        // 檢查更新 is both the hero's action and the row; either checks.
        compose.onAllNodesWithText(CHECK).onFirst().performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithText(VIEW).performScrollTo().performClick()
        assertEquals(1, opened)
    }

    /** The text is drawn whole: the field's two message lines hold it, with no ellipsis. */
    private fun SemanticsNodeInteraction.assertFits() {
        val layouts = mutableListOf<TextLayoutResult>()
        fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
        assertFalse("cut: ${layouts.first().layoutInput.text}", layouts.first().hasVisualOverflow)
    }

    private fun servers(engine: FakeEngine) = runBlocking { engine.effectiveConfig().getOrThrow().dnsUpstreams }

    private companion object {
        const val DARK = "深色"
        const val QUAD9 = "Quad9"
        const val CUSTOM = "自訂"
        const val SAVE = "儲存"
        const val CHECK = "檢查更新"
        const val VIEW = "在 GitHub 檢視"
        const val LOG_LEVEL = "日誌等級"
        const val OFF = "關閉"
        const val DAILY = "每天"
        const val WEEKLY = "每週"
        const val DEBUG = "除錯"
        const val V6_HINT = "最多 4 個 IPv4 或 IPv6 位址，以逗號分隔，例如 1.1.1.1、2606:4700:4700::1111"
        const val V6_INVALID = "請輸入 1 到 4 個 IP 位址，例如 1.1.1.1 或 [2606:4700:4700::1111]:53"
    }

    private object NoSystem : SettingsSystem {
        override fun openVpnSettings() = Unit

        override fun openReleases() = Unit

        override fun openRepository() = Unit

        override fun openPrivacy() = Unit

        override fun requestTile(onAnswer: (TileAnswer) -> Unit) = Unit
    }
}
