package dev.doona.android.prototype.rv.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import dev.doona.android.R
import dev.doona.android.designsystem.rv.RvTheme
import dev.doona.android.prototype.rv.RV_FS115
import dev.doona.android.prototype.rv.RV_FS200
import dev.doona.android.prototype.rv.Seed
import dev.doona.android.prototype.rv.assertWhole
import dev.doona.android.prototype.rv.rvAnimatorScale
import dev.doona.android.prototype.rv.rvShot
import dev.doona.android.prototype.rv.rvText
import dev.doona.android.prototype.rv.seedColorScheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 檢查更新 on 設定 (owner-fix2 item 18: it left 關於), the whole page, as 設定's own goldens, each light, dark, fs115
 * and fs200: while it asks GitHub (`proto-rv-settings-update-checking-*`, at animator scale 0, so the busy bars rest
 * half full), with nothing newer (`-update-latest-*`), with a newer release (`-update-new-*`) and with no answer
 * (`-update-failed-*`). Then the row's second line in each state, that the row and the hero take no second check
 * while one runs, what the banners' actions do, and that the hero's action, named by [rvUpdateLabel], is one named button.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rTW-w412dp-h915dp-notnight-xhdpi")
class RvUpdateShotTest {
    @get:Rule
    val compose = createComposeRule()

    @After
    fun restoreScale() = rvAnimatorScale(1f)

    @Test
    @Config(qualifiers = WHOLE)
    fun checkingLight() = shot("checking", RvUpdate.Checking, dark = false)

    @Test
    @Config(qualifiers = WHOLE)
    fun checkingDark() = shot("checking", RvUpdate.Checking, dark = true)

    @Test
    @Config(qualifiers = WHOLE_FS115)
    fun checkingFs115() = shot("checking", RvUpdate.Checking, fontScale = RV_FS115)

    @Test
    @Config(qualifiers = WHOLE_FS200)
    fun checkingFs200() = shot("checking", RvUpdate.Checking, fontScale = RV_FS200)

    @Test
    @Config(qualifiers = WHOLE)
    fun latestLight() = shot("latest", RvUpdate.Latest, dark = false)

    @Test
    @Config(qualifiers = WHOLE)
    fun latestDark() = shot("latest", RvUpdate.Latest, dark = true)

    @Test
    @Config(qualifiers = WHOLE_FS115)
    fun latestFs115() = shot("latest", RvUpdate.Latest, fontScale = RV_FS115)

    @Test
    @Config(qualifiers = WHOLE_FS200)
    fun latestFs200() = shot("latest", RvUpdate.Latest, fontScale = RV_FS200)

    @Test
    @Config(qualifiers = NEW_WHOLE)
    fun newLight() = shot("new", NEW, dark = false)

    @Test
    @Config(qualifiers = NEW_WHOLE)
    fun newDark() = shot("new", NEW, dark = true)

    @Test
    @Config(qualifiers = NEW_WHOLE_FS115)
    fun newFs115() = shot("new", NEW, fontScale = RV_FS115)

    @Test
    @Config(qualifiers = NEW_WHOLE_FS200)
    fun newFs200() = shot("new", NEW, fontScale = RV_FS200)

    @Test
    @Config(qualifiers = FAILED_WHOLE)
    fun failedLight() = shot("failed", RvUpdate.Failed, dark = false)

    @Test
    @Config(qualifiers = FAILED_WHOLE)
    fun failedDark() = shot("failed", RvUpdate.Failed, dark = true)

    @Test
    @Config(qualifiers = FAILED_WHOLE_FS115)
    fun failedFs115() = shot("failed", RvUpdate.Failed, fontScale = RV_FS115)

    @Test
    @Config(qualifiers = FAILED_WHOLE_FS200)
    fun failedFs200() = shot("failed", RvUpdate.Failed, fontScale = RV_FS200)

    /** The row's second line is the version, then where the check stands; nothing more while it asks or before it ran. */
    @Test
    fun eachStateSaysItselfAfterTheVersion() {
        var update by mutableStateOf<RvUpdate>(RvUpdate.Idle)
        compose.setContent { Themed { Row(update) {} } }
        val version = rvText(R.string.rv_settings_version_value)
        for ((state, expected) in listOf(
            RvUpdate.Idle to version,
            RvUpdate.Checking to version,
            RvUpdate.Latest to "$version · ${rvText(R.string.rv_update_latest)}",
            NEW to "$version · ${text(R.string.rv_update_new, RV_SAMPLE_RELEASE)}",
            RvUpdate.Failed to "$version · ${rvText(R.string.rv_update_failed)}",
        )) {
            update = state
            compose.waitForIdle()
            compose.onNode(line(expected), useUnmergedTree = true).assertExists()
        }
    }

    /** The whole row checks, and checks again after a failure; while it asks it takes no tap. */
    @Test
    fun theRowChecksAndTakesNoSecondWhileItAsks() {
        var checks = 0
        var update by mutableStateOf<RvUpdate>(RvUpdate.Failed)
        compose.setContent { Themed { Row(update) { checks++ } } }
        compose.onNodeWithText(rvText(R.string.rv_settings_check_update)).assertHasClickAction().assert(IS_BUTTON).performClick()
        assertEquals(1, checks)
        update = RvUpdate.Checking
        compose.waitForIdle()
        compose.onNodeWithText(rvText(R.string.rv_settings_check_update)).assertHasNoClickAction().performClick()
        assertEquals(1, checks)
    }

    @Test
    fun aNewerReleaseOpensItsPage() {
        var opened = 0
        compose.setContent { Themed { Page(NEW, RvSettingsRoutes(release = { opened++ })) } }
        compose.onNodeWithText(text(R.string.rv_update_new, RV_SAMPLE_RELEASE)).assertExists()
        compose.onNodeWithText(rvText(R.string.rv_update_view)).performClick()
        assertEquals(1, opened)
    }

    @Test
    fun aFailedCheckIsToldAndRetried() {
        var checks = 0
        compose.setContent { Themed { Page(RvUpdate.Failed, RvSettingsRoutes(checkUpdate = { checks++ })) } }
        compose.onNodeWithText(rvText(R.string.rv_update_failed)).assertExists()
        compose.onNodeWithText(rvText(R.string.rv_retry)).performClick()
        assertEquals(1, checks)
    }

    @Test
    fun nothingNewerIsTheRowsSecondLineAndTheHerosWordAndShowsNoBanner() {
        compose.setContent { Themed { Page(RvUpdate.Latest) } }
        compose.onNode(line("${rvText(R.string.rv_settings_version_value)} · ${rvText(R.string.rv_update_latest)}"), useUnmergedTree = true).assertExists()
        compose.onNodeWithText(rvText(R.string.rv_update_latest)).assert(IS_BUTTON)
        compose.onNodeWithText(rvText(R.string.rv_retry)).assertDoesNotExist()
    }

    /** 設定's hero action: one named button, the first 檢查更新 on the page (the row is under it), that runs the check. */
    @Test
    fun theHeroActionIsOneNamedButton() {
        var checks = 0
        compose.setContent { Themed { Page(RvUpdate.Idle, RvSettingsRoutes(checkUpdate = { checks++ })) } }
        val hero = compose.onAllNodesWithText(rvText(R.string.rv_settings_check_update)).onFirst()
        hero.assertHasClickAction().assert(IS_BUTTON).assertIsEnabled()
        // No click node a screen reader finds is left without a name, as the disc alone was.
        val nameless = hasClickAction() and SemanticsMatcher.keyNotDefined(SemanticsProperties.Text) and
            SemanticsMatcher.keyNotDefined(SemanticsProperties.ContentDescription) and SemanticsMatcher.keyNotDefined(SemanticsProperties.HideFromAccessibility)
        compose.onAllNodes(nameless).assertCountEquals(0)
        hero.performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(1, checks)
    }

    /** While it asks, neither the hero nor the row takes a second check. */
    @Test
    fun aCheckUnderWayTakesNoSecondOnThePage() {
        var checks = 0
        compose.setContent { Themed { Page(RvUpdate.Checking, RvSettingsRoutes(checkUpdate = { checks++ })) } }
        val hero = compose.onAllNodesWithText(rvText(R.string.rv_settings_check_update)).onFirst().assertIsNotEnabled()
        hero.performClick()
        hero.performSemanticsAction(SemanticsActions.OnClick)
        compose.onAllNodes(hasText(rvText(R.string.rv_settings_check_update)) and hasClickAction() and isEnabled()).assertCountEquals(0)
        assertEquals(0, checks)
    }

    /** When the check ends, the hero's action is named by its new word from the first frame; the old one fades out unread. */
    @Test
    fun theWordFadingOutIsNotRead() {
        var update by mutableStateOf<RvUpdate>(RvUpdate.Idle)
        compose.mainClock.autoAdvance = false
        compose.setContent { Themed { Page(update) } }
        compose.mainClock.advanceTimeByFrame()
        // The write reaches the composition once the main thread is idle.
        compose.runOnIdle { update = RvUpdate.Latest }
        compose.waitForIdle()
        // Two frames into the fade through, the old word still drawn.
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeByFrame()
        // Only the row's title still says 檢查更新.
        compose.onAllNodes(hasText(rvText(R.string.rv_settings_check_update))).assertCountEquals(1)
        compose.onNodeWithText(rvText(R.string.rv_update_latest)).assert(IS_BUTTON)
    }

    private fun shot(name: String, update: RvUpdate, dark: Boolean = false, fontScale: Float = 1f) {
        if (update == RvUpdate.Checking) rvAnimatorScale(0f)
        compose.rvShot("settings-update-$name", dark, fontScale = fontScale, before = { assertWhole() }) { Page(update) }
    }

    private fun text(id: Int, arg: String) = RuntimeEnvironment.getApplication().getString(id, arg)

    /** A text node showing [text], whatever word joiners the line's keeping put in it. */
    private fun line(text: String) = SemanticsMatcher("shows $text") { node ->
        node.config.getOrNull(SemanticsProperties.Text)?.any { it.text.replace(WORD_JOINER, "").replace(ZERO_WIDTH_SPACE, "").replace(NO_BREAK_SPACE, " ") == text } == true
    }

    @Composable
    private fun Page(update: RvUpdate, routes: RvSettingsRoutes = RvSettingsRoutes()) =
        RvSettingsScreen(rvSampleSettings(), onChange = {}, onOpenTheme = {}, routes = routes, update = update)

    @Composable
    private fun Row(update: RvUpdate, onCheck: () -> Unit) = Column { RvUpdateRow(update, rvText(R.string.rv_settings_version_value), onCheck) }

    @Composable
    private fun Themed(content: @Composable () -> Unit) = RvTheme(seedColorScheme(Seed.Cyan.argb, false), false, content)

    private companion object {
        /**
         * 設定's whole page with the 檢查更新 row ([RvSettingsShotTest]'s frames), and with the banner and its section gap
         * over it: 158.5 / 163.5 / 193.5 dp (a newer release), 157.5 / 162 / 190.5 dp (no answer), so every state ends as
         * 設定's own golden does. A frame is a whole dp, rounded up.
         */
        const val WHOLE = "+h1680dp"
        const val WHOLE_FS115 = "+h1703dp"
        const val WHOLE_FS200 = "+h2130dp"
        const val NEW_WHOLE = "+h1839dp"
        const val NEW_WHOLE_FS115 = "+h1867dp"
        const val NEW_WHOLE_FS200 = "+h2324dp"
        const val FAILED_WHOLE = "+h1838dp"
        const val FAILED_WHOLE_FS115 = "+h1865dp"
        const val FAILED_WHOLE_FS200 = "+h2321dp"

        const val WORD_JOINER = "\u2060"
        const val ZERO_WIDTH_SPACE = "\u200B"
        const val NO_BREAK_SPACE = "\u00A0"

        val NEW = RvUpdate.Available(RV_SAMPLE_RELEASE)
        val IS_BUTTON = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)
    }
}
