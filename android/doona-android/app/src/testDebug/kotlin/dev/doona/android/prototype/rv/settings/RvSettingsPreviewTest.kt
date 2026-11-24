package dev.doona.android.prototype.rv.settings

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import dev.doona.android.R
import dev.doona.android.prototype.rv.RvMotionOptions
import dev.doona.android.prototype.rv.RvPreviewApp
import dev.doona.android.prototype.rv.RvSampleStart
import dev.doona.android.prototype.rv.SETTINGS
import dev.doona.android.prototype.rv.rvAnimatorScale
import dev.doona.android.prototype.rv.rvText
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

    @Test
    fun aboutsCheckRunsThenEndsAsItsRouteSays() {
        app("${RvSettingsPreviewRoutes.ABOUT}:new")
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText(rvText(R.string.rv_settings_check_update)).performClick()
        compose.mainClock.advanceTimeBy(FRAMES_MS)
        compose.onNodeWithText(rvText(R.string.rv_settings_check_update)).assertHasNoClickAction()
        compose.mainClock.advanceTimeBy(RV_CHECK_MS + FRAMES_MS)
        compose.onNodeWithText(RuntimeEnvironment.getApplication().getString(R.string.rv_update_new, RV_SAMPLE_RELEASE)).assertExists()
    }

    @Test
    fun languageChoosesAndGoesBack() = choosesAndGoesBack(RvSettingsPreviewRoutes.LANGUAGE, R.string.rv_settings_language, R.string.rv_language_english)

    @Test
    fun intervalChoosesAndGoesBack() {
        val hours = RV_INTERVAL_HOURS.first()
        val row = RuntimeEnvironment.getApplication().resources.getQuantityString(R.plurals.rv_interval_hours, hours, hours)
        choosesAndGoesBack(RvSettingsPreviewRoutes.INTERVAL, R.string.rv_settings_update_interval, row)
    }

    @Test
    fun logLevelChoosesAndGoesBack() = choosesAndGoesBack(RvSettingsPreviewRoutes.LOG_LEVEL, R.string.rv_settings_log_level, R.string.rv_log_debug)

    @Test
    fun theReportIsWrittenAgainAfterASwitchTurns() {
        // The clock stands still from the start, or waiting for the first frame would run the build to its end.
        compose.mainClock.autoAdvance = false
        app(RvSettingsPreviewRoutes.REPORT_GITHUB)
        val github = compose.onNodeWithText(rvText(R.string.rv_report_github))
        github.assertIsNotEnabled()
        compose.mainClock.advanceTimeBy(RV_BUILD_MS + FRAMES_MS)
        github.assertIsEnabled()
        // The row is under the floating pair, so it is turned as a screen reader turns it; the write reaches the
        // composition once the main thread is idle.
        compose.onNodeWithText(rvText(R.string.rv_redact_apps)).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(FRAMES_MS)
        compose.onNodeWithText(rvText(R.string.rv_redact_apps)).assertIsOn()
        compose.onNodeWithText(rvText(R.string.rv_report_building)).assertExists()
        github.assertIsNotEnabled()
        compose.mainClock.advanceTimeBy(RV_BUILD_MS + FRAMES_MS)
        github.assertIsEnabled()
    }

    @Test
    fun aSwitchTurnedAfterAFailureWritesTheReportAgain() {
        compose.mainClock.autoAdvance = false
        app("${RvSettingsPreviewRoutes.REPORT_GITHUB}:failed")
        compose.mainClock.advanceTimeBy(FRAMES_MS)
        val github = compose.onNodeWithText(rvText(R.string.rv_report_github)).assertIsNotEnabled()
        turnAppsSwitch()
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
        app(RvSettingsPreviewRoutes.REPORT_GITHUB)
        compose.mainClock.advanceTimeBy(RV_BUILD_MS - RV_BUILD_MS % FRAME_MS)
        val github = compose.onNodeWithText(rvText(R.string.rv_report_github)).assertIsNotEnabled()
        turnAppsSwitch()
        compose.mainClock.advanceTimeBy(FRAME_MS)
        github.assertIsNotEnabled()
        compose.onNodeWithText(rvText(R.string.rv_report_building)).assertExists()
        compose.mainClock.advanceTimeBy(RV_BUILD_MS + FRAMES_MS)
        github.assertIsEnabled()
    }

    @Test
    fun debugCountsDownToInfo() {
        compose.mainClock.autoAdvance = false
        app(RvSettingsPreviewRoutes.LOG_LEVEL)
        compose.mainClock.advanceTimeBy(FRAMES_MS)
        compose.onNodeWithText(rvText(R.string.rv_log_debug)).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(FRAMES_MS)
        val full = RV_DEBUG_HOURS * MINUTES
        compose.onNodeWithText(left(full)).assertExists()
        compose.mainClock.advanceTimeBy(MINUTE_MS)
        compose.onNodeWithText(left(full - 1)).assertExists()
        compose.mainClock.advanceTimeBy((full - 1) * MINUTE_MS)
        compose.onNodeWithText(rvText(R.string.rv_log_info)).assertIsSelected()
        compose.onNodeWithText(rvText(R.string.rv_log_debug)).assertIsNotSelected()
        (0..full).forEach { compose.onNodeWithText(left(it)).assertDoesNotExist() }
    }

    private fun left(minutes: Int) = RuntimeEnvironment.getApplication().resources.getQuantityString(R.plurals.rv_log_debug_left, minutes, minutes)

    /** The row is under the floating pair, so it is turned as a screen reader turns it. */
    private fun turnAppsSwitch() {
        compose.onNodeWithText(rvText(R.string.rv_redact_apps)).performSemanticsAction(SemanticsActions.OnClick)
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

        const val MINUTES = 60
        const val MINUTE_MS = 60_000L
    }
}
