package dev.doona.android.feature.settings

import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import dev.doona.android.app.rvAppearance
import dev.doona.android.app.themeAppearance
import dev.doona.android.engine.fake.FakeEngine
import dev.doona.android.feature.model.RvAppearance
import dev.doona.android.feature.model.RvThemeMode
import dev.doona.android.prototype.rv.Seed
import dev.doona.android.prototype.rv.rvContent
import dev.doona.android.prototype.rv.seedColorScheme
import dev.doona.android.prototype.rv.settings.RV_SAMPLE_THEME_PREVIEW
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 主題 stores each choice as it is made, in the form the app's theme reads; DNS saves a preset and 自訂's servers. */
@RunWith(RobolectricTestRunner::class)
/** Tall enough that 模式 and 自訂 are on screen without a scroll. */
@Config(qualifiers = "zh-rTW-w412dp-h1300dp")
class ThemeDnsRouteTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun aThemeChoiceIsStoredAtOnce() {
        val store = FakeSettingsStore()
        compose.rvContent(dark = false) {
            ThemeRoute(store, seedColorScheme(Seed.Cyan.argb, false), seedColorScheme(Seed.Cyan.argb, true), RV_SAMPLE_THEME_PREVIEW, onBack = {})
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

    private fun servers(engine: FakeEngine) = runBlocking { engine.effectiveConfig().getOrThrow().dnsUpstreams }

    private companion object {
        const val DARK = "深色"
        const val QUAD9 = "Quad9"
        const val CUSTOM = "自訂"
        const val SAVE = "儲存"
    }
}
