package dev.doona.android.prototype.rv.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.doona.android.R
import dev.doona.android.designsystem.rv.RvTheme
import dev.doona.android.feature.model.RvLanguage
import dev.doona.android.prototype.rv.RV_FS115
import dev.doona.android.prototype.rv.RV_FS200
import dev.doona.android.prototype.rv.Seed
import dev.doona.android.prototype.rv.rvShot
import dev.doona.android.prototype.rv.rvText
import dev.doona.android.prototype.rv.seedColorScheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 語言 (`proto-rv-settings-language-*`), light, dark, fs115 and fs200, with 繁體中文 chosen and the phone in 繁體中文;
 * then that each language is named in itself and a row chooses it.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rTW-w412dp-h915dp-notnight-xhdpi")
class RvLanguageShotTest {
    @get:Rule
    val compose = createComposeRule()

    @Test fun light() = compose.rvShot("settings-language", dark = false) { Page() }

    @Test fun dark() = compose.rvShot("settings-language", dark = true) { Page() }

    @Test fun fs115() = compose.rvShot("settings-language", dark = false, fontScale = RV_FS115) { Page() }

    @Test fun fs200() = compose.rvShot("settings-language", dark = false, fontScale = RV_FS200) { Page() }

    @Test
    fun eachLanguageIsNamedInItselfAndARowChoosesIt() {
        val picked = mutableListOf<RvLanguage>()
        compose.setContent { RvTheme(seedColorScheme(Seed.Cyan.argb, false), false) { RvLanguageScreen(RvLanguage.TraditionalChinese, RvLanguage.TraditionalChinese, { picked += it }, onBack = {}) } }
        // 繁體中文 twice: its own row, and the language 跟隨系統 gives.
        compose.onAllNodesWithText("繁體中文").assertCountEquals(2)
        compose.onNodeWithText("English").performClick()
        compose.onNodeWithText("简体中文").performClick()
        compose.onNodeWithText(rvText(R.string.rv_settings_theme_system)).performClick()
        assertEquals(listOf(RvLanguage.English, RvLanguage.SimplifiedChinese, RvLanguage.System), picked)
    }

    @Test
    fun theChosenLanguageIsSelected() {
        compose.setContent { RvTheme(seedColorScheme(Seed.Cyan.argb, false), false) { RvLanguageScreen(RvLanguage.English, RvLanguage.TraditionalChinese, {}, onBack = {}) } }
        compose.onNodeWithText("English").assertIsSelected()
    }

    @Test
    fun theRowsAreOneRadioGroup() {
        compose.setContent { RvTheme(seedColorScheme(Seed.Cyan.argb, false), false) { Page() } }
        compose.assertOneRadioGroup(RvLanguage.entries.size)
    }

    @Composable
    private fun Page() = RvLanguageScreen(RvLanguage.TraditionalChinese, RvLanguage.TraditionalChinese, onSelect = {}, onBack = {})
}
