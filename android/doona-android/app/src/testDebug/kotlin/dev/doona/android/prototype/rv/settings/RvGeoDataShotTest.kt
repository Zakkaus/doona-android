package dev.doona.android.prototype.rv.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.doona.android.R
import dev.doona.android.designsystem.rv.RvControlSize
import dev.doona.android.designsystem.rv.RvFormat
import dev.doona.android.designsystem.rv.RvPart
import dev.doona.android.designsystem.rv.RvRowPart
import dev.doona.android.designsystem.rv.RvSpace
import dev.doona.android.designsystem.rv.RvTheme
import dev.doona.android.feature.model.RvGeoData
import dev.doona.android.feature.model.RvGeoUpdate
import dev.doona.android.feature.settings.RvSettingsRoutes
import dev.doona.android.feature.settings.RvSettingsScreen
import dev.doona.android.prototype.rv.RV_FS115
import dev.doona.android.prototype.rv.RV_FS200
import dev.doona.android.prototype.rv.Seed
import dev.doona.android.prototype.rv.Utc
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
import java.time.LocalDate

/**
 * 設定's GeoIP/GeoSite row (owner-fix2 item 16; in 更新 under 檢查更新 since owner-fix3 item 27): the whole 設定 page, as
 * its own goldens and `-config-entry-*` are, so a diff against `proto-rv-settings-*` shows only the row's state. Each
 * light, dark, fs115 and fs200: the shipped data with 更新 (`proto-rv-settings-geodata-idle-*`), updating
 * (`-updating-*`, at animator scale 0, so the busy bar rests half full), updated to newer data (`-updated-*`), nothing
 * newer (`-latest-*`), failed (`-failed-*`), waiting for the VPN (`-waiting-*`), and failed while the VPN is down
 * (`-failed-waiting-*`, light and fs200 only). Then each state's second line and
 * action, the failure kept on the row with no dialog, and 更新 disabled until the VPN is up.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rTW-w412dp-h915dp-notnight-xhdpi")
class RvGeoDataShotTest {
    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val utc = Utc()

    @After
    fun restoreScale() = rvAnimatorScale(1f)

    @Test
    @Config(qualifiers = WHOLE)
    fun idleLight() = shot("idle", IDLE, dark = false)

    @Test
    @Config(qualifiers = WHOLE)
    fun idleDark() = shot("idle", IDLE, dark = true)

    @Test
    @Config(qualifiers = WHOLE_FS115)
    fun idleFs115() = shot("idle", IDLE, fontScale = RV_FS115)

    @Test
    @Config(qualifiers = FS200_IDLE)
    fun idleFs200() = shot("idle", IDLE, fontScale = RV_FS200)

    @Test
    @Config(qualifiers = WHOLE)
    fun updatingLight() = shot("updating", UPDATING, dark = false)

    @Test
    @Config(qualifiers = WHOLE)
    fun updatingDark() = shot("updating", UPDATING, dark = true)

    @Test
    @Config(qualifiers = WHOLE_FS115)
    fun updatingFs115() = shot("updating", UPDATING, fontScale = RV_FS115)

    @Test
    @Config(qualifiers = FS200_UPDATING)
    fun updatingFs200() = shot("updating", UPDATING, fontScale = RV_FS200)

    @Test
    @Config(qualifiers = WHOLE)
    fun updatedLight() = shot("updated", UPDATED, dark = false)

    @Test
    @Config(qualifiers = WHOLE)
    fun updatedDark() = shot("updated", UPDATED, dark = true)

    @Test
    @Config(qualifiers = WHOLE_FS115)
    fun updatedFs115() = shot("updated", UPDATED, fontScale = RV_FS115)

    @Test
    @Config(qualifiers = FS200_WRAPPED)
    fun updatedFs200() = shot("updated", UPDATED, fontScale = RV_FS200)

    @Test
    @Config(qualifiers = WHOLE)
    fun latestLight() = shot("latest", LATEST, dark = false)

    @Test
    @Config(qualifiers = WHOLE)
    fun latestDark() = shot("latest", LATEST, dark = true)

    @Test
    @Config(qualifiers = WHOLE_FS115)
    fun latestFs115() = shot("latest", LATEST, fontScale = RV_FS115)

    @Test
    @Config(qualifiers = FS200_WRAPPED)
    fun latestFs200() = shot("latest", LATEST, fontScale = RV_FS200)

    @Test
    @Config(qualifiers = WHOLE)
    fun failedLight() = shot("failed", FAILED, dark = false)

    @Test
    @Config(qualifiers = WHOLE)
    fun failedDark() = shot("failed", FAILED, dark = true)

    @Test
    @Config(qualifiers = WHOLE_FS115)
    fun failedFs115() = shot("failed", FAILED, fontScale = RV_FS115)

    @Test
    @Config(qualifiers = FS200_WRAPPED)
    fun failedFs200() = shot("failed", FAILED, fontScale = RV_FS200)

    @Test
    @Config(qualifiers = FAILED_WAITING_WHOLE)
    fun failedWaitingLight() = shot("failed-waiting", FAILED_WAITING, dark = false)

    @Test
    @Config(qualifiers = FAILED_WAITING_FS200)
    fun failedWaitingFs200() = shot("failed-waiting", FAILED_WAITING, fontScale = RV_FS200)

    @Test
    @Config(qualifiers = WHOLE)
    fun waitingLight() = shot("waiting", WAITING, dark = false)

    @Test
    @Config(qualifiers = WHOLE)
    fun waitingDark() = shot("waiting", WAITING, dark = true)

    @Test
    @Config(qualifiers = WHOLE_FS115)
    fun waitingFs115() = shot("waiting", WAITING, fontScale = RV_FS115)

    @Test
    @Config(qualifiers = FS200_WRAPPED)
    fun waitingFs200() = shot("waiting", WAITING, fontScale = RV_FS200)

    /** Each state's second line (the data's date, then where the update stands) and its action, on one row as it changes. */
    @Test
    @Config(qualifiers = WHOLE)
    fun eachStateSaysItselfAfterTheDateWithItsAction() {
        var data by mutableStateOf(IDLE)
        compose.setContent { Themed { Page(data) } }
        val shipped = date(RV_SAMPLE_GEO_SHIPPED)
        expect(shipped, R.string.rv_menu_update, enabled = true)
        compose.runOnIdle { data = UPDATING }
        expect(after(shipped, R.string.rv_geodata_updating), action = null)
        compose.runOnIdle { data = UPDATED }
        expect(after(date(RV_SAMPLE_GEO_NEWER), R.string.rv_geodata_updated), R.string.rv_menu_update, enabled = true)
        compose.runOnIdle { data = LATEST }
        expect(after(shipped, R.string.rv_update_latest), R.string.rv_menu_update, enabled = true)
        compose.runOnIdle { data = FAILED }
        expect(after(shipped, R.string.rv_geodata_failed), R.string.rv_retry, enabled = true)
        compose.runOnIdle { data = WAITING }
        expect(after(shipped, R.string.status_stopped), R.string.rv_menu_update, enabled = false)
        compose.runOnIdle { data = FAILED_WAITING }
        expect(after(after(shipped, R.string.rv_geodata_failed), R.string.status_stopped), R.string.rv_retry, enabled = false)
    }

    /** Whenever 重試 is disabled, the line says why: a failure before the VPN is up keeps 未連線 after 無法更新. */
    @Test
    @Config(qualifiers = WHOLE)
    fun aDisabledRetrySaysTheVpnIsDown() {
        var updates = 0
        var data by mutableStateOf(FAILED_WAITING)
        compose.setContent { Themed { Page(data) { updates++ } } }
        compose.onNode(line(after(after(date(RV_SAMPLE_GEO_SHIPPED), R.string.rv_geodata_failed), R.string.status_stopped))).assertExists()
        compose.onNodeWithText(rvText(R.string.rv_retry)).assertIsNotEnabled().performClick()
        assertEquals(0, updates)
        compose.runOnIdle { data = FAILED }
        compose.onNode(line(after(date(RV_SAMPLE_GEO_SHIPPED), R.string.rv_geodata_failed))).assertExists()
        compose.onNodeWithText(rvText(R.string.rv_retry)).assertIsEnabled().performClick()
        assertEquals(1, updates)
    }

    /** M8: a failed update stays on its row, no dialog and no banner, and 重試 updates again. */
    @Test
    @Config(qualifiers = WHOLE)
    fun aFailureStaysOnTheRowAndRetries() {
        var updates = 0
        compose.setContent { Themed { Page(FAILED) { updates++ } } }
        compose.onAllNodes(line(after(date(RV_SAMPLE_GEO_SHIPPED), R.string.rv_geodata_failed))).assertCountEquals(1)
        compose.onAllNodes(isDialog()).assertCountEquals(0)
        compose.onNodeWithText(rvText(R.string.rv_retry)).performClick()
        assertEquals(1, updates)
    }

    /** Before the VPN is up 更新 is disabled and takes no tap; once it is up, the same tap updates. */
    @Test
    @Config(qualifiers = WHOLE)
    fun anUpdateWaitsForTheConnection() {
        var updates = 0
        var data by mutableStateOf(WAITING)
        compose.setContent { Themed { Page(data) { updates++ } } }
        compose.onNode(UPDATE_BUTTON).assertIsNotEnabled().performClick()
        assertEquals(0, updates)
        compose.runOnIdle { data = IDLE }
        compose.onNode(UPDATE_BUTTON).assertIsEnabled().performClick()
        assertEquals(1, updates)
    }

    /**
     * The action stands as the row's disc: its top on the title's top (Revolut's settings rows, rowalign-1125), so it
     * stays inside the row's 16 dp text inset (owner-fix3 item 26); it is the library's 40 dp in-card button and ends at
     * the row's inset, where the other rows' chevron boxes end: 更新, then 重試.
     */
    @Test
    @Config(qualifiers = WHOLE)
    fun theActionTopsTheTitleAsTheDiscAndEndsAtTheRowsInset() {
        var shown by mutableStateOf(IDLE)
        compose.setContent { Themed { Page(shown) } }
        val name = rvText(R.string.rv_settings_geodata)
        listOf(IDLE to R.string.rv_menu_update, FAILED to R.string.rv_retry).forEach { (data, label) ->
            compose.runOnIdle { shown = data }
            val row = compose.onNode(hasTestTag(RvPart.ROW) and hasAnyDescendant(hasText(name)), useUnmergedTree = true).getBoundsInRoot()
            val title = compose.onNode(hasText(name) and hasAnyAncestor(hasTestTag(RvPart.ROW)), useUnmergedTree = true).getBoundsInRoot()
            val disc = compose.onNode(hasTestTag(RvRowPart.ICON_DISC) and hasAnyAncestor(hasTestTag(RvPart.ROW) and hasAnyDescendant(hasText(name))), useUnmergedTree = true).getBoundsInRoot()
            val button = compose.onNode(hasText(rvText(label)) and hasClickAction()).getBoundsInRoot()
            assertEquals("$label top", title.top.value, button.top.value, HALF_DP)
            assertEquals("$label top as the disc's", disc.top.value, button.top.value, HALF_DP)
            assertEquals("$label end", (row.right - RvSpace.rowPadding).value, button.right.value, HALF_DP)
            assertEquals("$label height", RvControlSize.secondary.value, (button.bottom - button.top).value, HALF_DP)
        }
    }

    /** 設定 shows the row by default, in 更新 under 檢查更新, the group just before 關於 (owner-fix3 item 27). */
    @Test
    @Config(qualifiers = WHOLE)
    fun theRowIsInUpdatesUnderTheCheckBeforeAbout() {
        compose.setContent { Themed { RvSettingsScreenSample(rvSampleSettings(), onChange = {}, onOpenTheme = {}) } }
        // 更新 is both the group's title and the row's button, 檢查更新 both the hero's action and a row: a title is read
        // inside its section part, a row's title inside its row.
        val section = listOf(R.string.rv_settings_group_advanced, R.string.rv_settings_group_updates, R.string.rv_settings_group_about)
        val top = { id: Int ->
            val inSection = if (id in section) hasAnyAncestor(hasTestTag(RvPart.SECTION)) else hasAnyAncestor(hasTestTag(RvPart.ROW))
            compose.onNode(hasText(rvText(id)) and inSection, useUnmergedTree = true).getBoundsInRoot().top.value
        }
        val order = listOf(R.string.rv_settings_group_advanced, R.string.rv_settings_group_updates, R.string.rv_settings_check_update, R.string.rv_settings_geodata, R.string.rv_settings_group_about)
        assertEquals(order.map(::rvText), order.sortedBy(top).map(::rvText))
    }

    private fun expect(second: String, action: Int?, enabled: Boolean = true) {
        compose.onNode(line(second)).assertExists()
        val actions = listOf(R.string.rv_menu_update, R.string.rv_retry).map { compose.onAllNodes(hasText(rvText(it)) and hasClickAction()) }
        if (action == null) {
            actions.forEach { it.assertCountEquals(0) }
            return
        }
        val button = compose.onNode(hasText(rvText(action)) and !isHeading())
        if (enabled) button.assertIsEnabled() else button.assertIsNotEnabled()
    }

    private fun shot(name: String, data: RvGeoData, dark: Boolean = false, fontScale: Float = 1f) {
        if (data.update == RvGeoUpdate.Updating) rvAnimatorScale(0f)
        compose.rvShot("settings-geodata-$name", dark, before = { assertWhole() }, fontScale = fontScale) { Page(data) }
    }

    private fun date(day: LocalDate) = RvFormat(RuntimeEnvironment.getApplication().resources.configuration.locales[0]).date(day)

    private fun after(date: String, state: Int) = date + rvText(R.string.rv_comma) + rvText(state)

    /** A text node showing [text], whatever word joiner or no-break space the line breaking put in it. */
    private fun line(text: String) = SemanticsMatcher("shows $text") { node ->
        node.config.getOrNull(SemanticsProperties.Text)?.any { it.text.replace(WORD_JOINER, "").replace(ZERO_WIDTH_SPACE, "").replace(NO_BREAK_SPACE, " ") == text } == true
    }

    @Composable
    private fun Page(data: RvGeoData, onUpdate: () -> Unit = {}) =
        RvSettingsScreen(rvSampleSettings(), rvSampleVersions.app, onChange = {}, onOpenTheme = {}, routes = rvSettingsRoutes(geoUpdate = onUpdate), geoData = data)

    @Composable
    private fun Themed(content: @Composable () -> Unit) = RvTheme(seedColorScheme(Seed.Cyan.argb, false), false, content)

    private companion object {
        /**
         * 設定's whole page at each font size ([RvSettingsShotTest]'s frames, the row is on it by default), a whole dp,
         * rounded up. At fs200 the title wraps beside 更新, and the second line too where a state follows the date.
         */
        const val WHOLE = "+h2072dp"
        const val WHOLE_FS115 = "+h2097dp"
        const val FS200_IDLE = "+h2696dp"
        const val FS200_UPDATING = "+h2693dp"
        const val FS200_WRAPPED = "+h2737dp"

        /** The failure and offline labels take a second line beside 重試 at fs1, a third at fs200. */
        const val FAILED_WAITING_WHOLE = "+h2092dp"
        const val FAILED_WAITING_FS200 = "+h2778dp"

        const val HALF_DP = 0.5f

        /** The row's 更新 button, enabled or not; the 更新 group's title is a heading. */
        val UPDATE_BUTTON get() = hasText(rvText(R.string.rv_menu_update)) and !isHeading()

        const val WORD_JOINER = "⁠"
        const val ZERO_WIDTH_SPACE = "​"
        const val NO_BREAK_SPACE = "\u00A0"

        val IDLE = RvGeoData(RV_SAMPLE_GEO_SHIPPED)
        val UPDATING = IDLE.copy(update = RvGeoUpdate.Updating)
        val UPDATED = RvGeoData(RV_SAMPLE_GEO_NEWER, RvGeoUpdate.Updated)
        val LATEST = IDLE.copy(update = RvGeoUpdate.Latest)
        val FAILED = IDLE.copy(update = RvGeoUpdate.Failed)
        val WAITING = IDLE.copy(connected = false)
        val FAILED_WAITING = FAILED.copy(connected = false)
    }
}
