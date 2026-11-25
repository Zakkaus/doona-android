package dev.doona.android.prototype.rv.settings

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import dev.doona.android.R
import dev.doona.android.designsystem.rv.RvTheme
import dev.doona.android.feature.settings.RV_DEBUG_HOURS
import dev.doona.android.feature.settings.RvSettingsRoutes
import dev.doona.android.prototype.rv.REPORT
import dev.doona.android.prototype.rv.RvMotionOptions
import dev.doona.android.prototype.rv.RvPreviewApp
import dev.doona.android.prototype.rv.RvSampleStart
import dev.doona.android.prototype.rv.SETTINGS
import dev.doona.android.prototype.rv.Seed
import dev.doona.android.prototype.rv.data.RvData
import dev.doona.android.prototype.rv.rvAnimatorScale
import dev.doona.android.prototype.rv.rvText
import dev.doona.android.prototype.rv.seedColorScheme
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The 設定 sub-page samples in the preview app, each opened by its route over 設定 ([RvSettingsPreviewRoutes]): what it
 * opens or chooses, and that the back disc returns to 設定. At animator scale 0, so a push or a pop is over at once.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rTW-w412dp-h915dp-notnight-xhdpi")
class RvSettingsPreviewTest {
    @get:Rule
    val compose = createComposeRule()

    @Before
    fun instant() = rvAnimatorScale(0f)

    @After
    fun restoreScale() = rvAnimatorScale(1f)

    @Test
    fun aboutOpensItsReportAndBothGoBack() {
        app(RvSettingsPreviewRoutes.ABOUT)
        heading(R.string.rv_about_title)
        compose.onNodeWithText(rvText(R.string.rv_report_title)).performClick()
        heading(R.string.rv_report_title)
        back()
        heading(R.string.rv_about_title)
        back()
        heading(R.string.hub_settings)
    }

    /**
     * 設定's check as the preview runs it ([rvUpdateSample], `settings:new`): from the hero's action (關於's row runs the
     * same), it asks, taking no second, then ends as its outcome says.
     */
    @Test
    fun settingsCheckRunsThenEndsAsItsOutcomeSays() {
        compose.setContent {
            val sample = rvUpdateSample("new")
            RvTheme(seedColorScheme(Seed.Cyan.argb, false), false) {
                RvSettingsScreenSample(rvSampleSettings(), onChange = {}, onOpenTheme = {}, routes = rvSettingsRoutes(checkUpdate = sample.check), update = sample.update)
            }
        }
        compose.mainClock.autoAdvance = false
        compose.onAllNodesWithText(rvText(R.string.rv_settings_check_update)).onFirst().performClick()
        compose.mainClock.advanceTimeBy(FRAMES_MS)
        compose.onAllNodes(hasText(rvText(R.string.rv_settings_check_update)) and hasClickAction() and isEnabled()).assertCountEquals(0)
        compose.mainClock.advanceTimeBy(RV_CHECK_MS + FRAMES_MS)
        compose.onNodeWithText(RuntimeEnvironment.getApplication().getString(R.string.rv_update_new, RV_SAMPLE_RELEASE)).assertExists()
    }

    @Test
    fun languageChoosesAndGoesBack() = choosesAndGoesBack(RvSettingsPreviewRoutes.LANGUAGE, R.string.rv_settings_language, R.string.rv_language_english)

    /** 設定's 語言 row opens the page; the language chosen there is the row's value back on 設定. */
    @Test
    fun theLanguageRowOpensThePageAndShowsTheChoice() {
        app(SETTINGS)
        compose.onNodeWithText(rvText(R.string.rv_settings_language)).performScrollTo().performClick()
        compose.waitForIdle()
        heading(R.string.rv_settings_language)
        compose.onNodeWithText(rvText(R.string.rv_language_english)).performClick().assertIsSelected()
        back()
        heading(R.string.hub_settings)
        compose.onNode(hasText(rvText(R.string.rv_settings_language)) and hasText(rvText(R.string.rv_language_english))).assertExists()
    }

    @Test
    fun intervalChoosesAndGoesBack() {
        val hours = RvData.UPDATE_INTERVALS.first()
        val row = RuntimeEnvironment.getApplication().resources.getQuantityString(R.plurals.rv_interval_hours, hours, hours)
        choosesAndGoesBack(RvSettingsPreviewRoutes.INTERVAL, R.string.rv_settings_update_interval, row)
    }

    @Test
    fun logLevelChoosesAndGoesBack() = choosesAndGoesBack(RvSettingsPreviewRoutes.LOG_LEVEL, R.string.rv_settings_log_level, R.string.rv_log_debug)

    @Test
    fun theReportIsWrittenAgainAfterASwitchTurns() {
        // The clock stands still from the start, or waiting for the first frame would run the build to its end.
        compose.mainClock.autoAdvance = false
        app(REPORT)
        val github = compose.onNodeWithText(rvText(R.string.rv_report_github))
        github.assertIsNotEnabled()
        compose.mainClock.advanceTimeBy(RV_BUILD_MS + FRAMES_MS)
        github.assertIsEnabled()
        // The row is under the floating pair, so it is turned as a screen reader turns it; the write reaches the
        // composition once the main thread is idle.
        compose.onNodeWithText(rvText(R.string.rv_redact_ip)).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(FRAMES_MS)
        compose.onNodeWithText(rvText(R.string.rv_redact_ip)).assertIsOff()
        compose.onNodeWithText(rvText(R.string.rv_report_building)).assertExists()
        github.assertIsNotEnabled()
        compose.mainClock.advanceTimeBy(RV_BUILD_MS + FRAMES_MS)
        github.assertIsEnabled()
    }

    @Test
    fun aSwitchTurnedAfterAFailureWritesTheReportAgain() {
        compose.mainClock.autoAdvance = false
        app("$REPORT:failed")
        compose.mainClock.advanceTimeBy(FRAMES_MS)
        val github = compose.onNodeWithText(rvText(R.string.rv_report_github)).assertIsNotEnabled()
        turnIpSwitch()
        compose.mainClock.advanceTimeBy(FRAMES_MS)
        compose.onNodeWithText(rvText(R.string.rv_report_building)).assertExists()
        compose.mainClock.advanceTimeBy(RV_BUILD_MS + FRAMES_MS)
        github.assertIsEnabled()
    }

    /**
     * A switch turned in the last frame of a write: the write ends before the page composes again, and must not take the
     * new switches as the ones it hid. The build starts at 0 and ends at [RV_BUILD_MS], between two frames.
     */
    @Test
    fun aSwitchTurnedAsTheWriteEndsWritesItAgain() {
        compose.mainClock.autoAdvance = false
        app(REPORT)
        compose.mainClock.advanceTimeBy(RV_BUILD_MS - RV_BUILD_MS % FRAME_MS)
        val github = compose.onNodeWithText(rvText(R.string.rv_report_github)).assertIsNotEnabled()
        turnIpSwitch()
        compose.mainClock.advanceTimeBy(FRAME_MS)
        github.assertIsNotEnabled()
        compose.onNodeWithText(rvText(R.string.rv_report_building)).assertExists()
        compose.mainClock.advanceTimeBy(RV_BUILD_MS + FRAMES_MS)
        github.assertIsEnabled()
    }

    @Test
    fun debugReturnsToInfoAfterTheHour() {
        compose.mainClock.autoAdvance = false
        app(RvSettingsPreviewRoutes.LOG_LEVEL)
        compose.mainClock.advanceTimeBy(FRAMES_MS)
        compose.onNodeWithText(rvText(R.string.rv_log_debug)).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(RV_DEBUG_HOURS * HOUR_MS - MINUTE_MS)
        compose.onNodeWithText(rvText(R.string.rv_log_debug)).assertIsSelected()
        compose.mainClock.advanceTimeBy(MINUTE_MS + FRAMES_MS)
        compose.onNodeWithText(rvText(R.string.rv_log_info)).assertIsSelected()
        compose.onNodeWithText(rvText(R.string.rv_log_debug)).assertIsNotSelected()
    }

    /** The row is under the floating pair, so it is turned as a screen reader turns it. */
    private fun turnIpSwitch() {
        compose.onNodeWithText(rvText(R.string.rv_redact_ip)).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
    }

    private fun choosesAndGoesBack(route: String, title: Int, row: Int) = choosesAndGoesBack(route, title, rvText(row))

    private fun choosesAndGoesBack(route: String, title: Int, row: String) {
        app(route)
        heading(title)
        compose.onNodeWithText(row).performClick().assertIsSelected()
        back()
        heading(R.string.hub_settings)
    }

    private fun app(route: String) = compose.setContent { RvPreviewApp(RvMotionOptions(), RvSampleStart(routes = listOf(SETTINGS, route))) }

    private fun heading(id: Int) = compose.onAllNodes(hasText(rvText(id)) and isHeading()).assertCountEquals(1)

    private fun back() {
        compose.onNodeWithContentDescription(rvText(R.string.ui_back)).performClick()
        compose.waitForIdle()
    }

    private companion object {
        /** A few frames, for a state to show. */
        const val FRAMES_MS = 64L

        /** One frame of the test clock. */
        const val FRAME_MS = 16L

        const val HOUR_MS = 3_600_000L
        const val MINUTE_MS = 60_000L
    }
}
