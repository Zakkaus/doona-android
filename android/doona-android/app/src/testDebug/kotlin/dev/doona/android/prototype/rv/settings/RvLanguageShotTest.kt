package dev.doona.android.prototype.rv.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import dev.doona.android.R
import dev.doona.android.designsystem.rv.RvTheme
import dev.doona.android.feature.model.RvLanguage
import dev.doona.android.feature.settings.RvLanguageScreen
import dev.doona.android.prototype.rv.RV_FS115
import dev.doona.android.prototype.rv.RV_FS200
import dev.doona.android.prototype.rv.Seed
import dev.doona.android.prototype.rv.rvContent
import dev.doona.android.prototype.rv.rvShot
import dev.doona.android.prototype.rv.rvText
import dev.doona.android.prototype.rv.seedColorScheme
import dev.doona.android.testing.GestureBar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    @Test
    @Config(qualifiers = "zh-rTW-w915dp-h412dp-land-notnight-xhdpi")
    fun lastLanguageCanBeTappedInLandscapeAtLargeType() {
        var picked: RvLanguage? = null
        compose.rvContent(dark = false, fontScale = RV_FS200) {
            RvLanguageScreen(RvLanguage.English, RvLanguage.TraditionalChinese, { picked = it }, onBack = {})
        }
        val last = RvLanguage.entries.last()
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollBy)).performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, Float.MAX_VALUE) }
        val row = compose.onNodeWithText(rvText(last.label))
        val bounds = row.getUnclippedBoundsInRoot()
        val bottom = compose.onRoot().getUnclippedBoundsInRoot().bottom - GestureBar
        assertTrue("last language $bounds must end above $bottom", bounds.bottom <= bottom)
        row.performTouchInput { click() }
        assertEquals(last, picked)
        val layouts = mutableListOf<TextLayoutResult>()
        val action = SemanticsActions.GetTextLayoutResult
        val inPage = hasAnyAncestor(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollBy))
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(action) and inPage, useUnmergedTree = true).fetchSemanticsNodes().forEach {
            it.config[action].action?.invoke(layouts)
        }
        assertTrue("missing final label in ${layouts.map { it.layoutInput.text }}", layouts.any { it.layoutInput.text.text.replace("\u2060", "") == rvText(last.label) })
        layouts.forEach { layout ->
            assertTrue(layout.layoutInput.text.text, !layout.hasVisualOverflow || (0 until layout.lineCount).any(layout::isLineEllipsized))
        }
    }

    @Composable
    private fun Page() = RvLanguageScreen(RvLanguage.TraditionalChinese, RvLanguage.TraditionalChinese, onSelect = {}, onBack = {})
}
