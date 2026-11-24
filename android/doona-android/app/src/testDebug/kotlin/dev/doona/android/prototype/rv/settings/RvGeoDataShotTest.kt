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
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.doona.android.R
import dev.doona.android.designsystem.rv.RvFormat
import dev.doona.android.designsystem.rv.RvTheme
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

/**
 * 設定's GeoIP／GeoSite row (owner-fix2 item 16), a sample behind the page's `geoData` flag: the whole 設定 page, as its
 * own goldens and `-config-entry-*` are, so a diff against `proto-rv-settings-*` shows only the row 進階 gains. Each
 * light, dark, fs115 and fs200: the shipped data with 更新 (`proto-rv-settings-geodata-idle-*`), updating
 * (`-updating-*`, at animator scale 0, so the busy bar rests half full), updated to newer data (`-updated-*`), nothing
 * newer (`-latest-*`), failed (`-failed-*`), and waiting for the VPN (`-waiting-*`). Then each state's second line and
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
        val shipped = date(RV_SAMPLE_GEO_SHIPPED_MS)
        expect(shipped, R.string.rv_menu_update, enabled = true)
        compose.runOnIdle { data = UPDATING }
        expect(after(shipped, R.string.rv_geodata_updating), action = null)
        compose.runOnIdle { data = UPDATED }
        expect(after(date(RV_SAMPLE_GEO_NEWER_MS), R.string.rv_geodata_updated), R.string.rv_menu_update, enabled = true)
        compose.runOnIdle { data = LATEST }
        expect(after(shipped, R.string.rv_update_latest), R.string.rv_menu_update, enabled = true)
        compose.runOnIdle { data = FAILED }
        expect(after(shipped, R.string.rv_geodata_failed), R.string.rv_retry, enabled = true)
        compose.runOnIdle { data = WAITING }
        expect(after(shipped, R.string.status_stopped), R.string.rv_menu_update, enabled = false)
    }

    /** M8: a failed update stays on its row, no dialog and no banner, and 重試 updates again. */
    @Test
    @Config(qualifiers = WHOLE)
    fun aFailureStaysOnTheRowAndRetries() {
        var updates = 0
        compose.setContent { Themed { Page(FAILED) { updates++ } } }
        compose.onAllNodes(line(after(date(RV_SAMPLE_GEO_SHIPPED_MS), R.string.rv_geodata_failed))).assertCountEquals(1)
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
        compose.onNodeWithText(rvText(R.string.rv_menu_update)).assertIsNotEnabled().performClick()
        assertEquals(0, updates)
        compose.runOnIdle { data = IDLE }
        compose.onNodeWithText(rvText(R.string.rv_menu_update)).assertIsEnabled().performClick()
        assertEquals(1, updates)
    }

    /** Without the sample flag 設定 is as the owner approved it: no GeoIP／GeoSite row. */
    @Test
    @Config(qualifiers = WHOLE)
    fun theApprovedPageHasNoRow() {
        compose.setContent { Themed { RvSettingsScreen(rvSampleSettings(), onChange = {}, onOpenTheme = {}) } }
        compose.onNodeWithText(rvText(R.string.rv_settings_geodata)).assertDoesNotExist()
    }

    private fun expect(second: String, action: Int?, enabled: Boolean = true) {
        compose.onNode(line(second)).assertExists()
        val actions = listOf(R.string.rv_menu_update, R.string.rv_retry).map { compose.onAllNodes(hasText(rvText(it)) and hasClickAction()) }
        if (action == null) {
            actions.forEach { it.assertCountEquals(0) }
            return
        }
        val button = compose.onNodeWithText(rvText(action))
        if (enabled) button.assertIsEnabled() else button.assertIsNotEnabled()
    }

    private fun shot(name: String, data: RvGeoData, dark: Boolean = false, fontScale: Float = 1f) {
        if (data.update == RvGeoUpdate.Updating) rvAnimatorScale(0f)
        compose.rvShot("settings-geodata-$name", dark, before = { assertWhole() }, fontScale = fontScale) { Page(data) }
    }

    private fun date(epochMs: Long) = RvFormat(RuntimeEnvironment.getApplication().resources.configuration.locales[0]).date(epochMs)

    private fun after(date: String, state: Int) = date + " · " + rvText(state)

    /** A text node showing [text], whatever word joiner or no-break space the line breaking put in it. */
    private fun line(text: String) = SemanticsMatcher("shows $text") { node ->
        node.config.getOrNull(SemanticsProperties.Text)?.any { it.text.replace(WORD_JOINER, "").replace(ZERO_WIDTH_SPACE, "").replace(NO_BREAK_SPACE, " ") == text } == true
    }

    @Composable
    private fun Page(data: RvGeoData, onUpdate: () -> Unit = {}) =
        RvSettingsScreen(rvSampleSettings(), onChange = {}, onOpenTheme = {}, routes = RvSettingsRoutes(geoUpdate = onUpdate), geoData = data)

    @Composable
    private fun Themed(content: @Composable () -> Unit) = RvTheme(seedColorScheme(Seed.Cyan.argb, false), false, content)

    private companion object {
        /**
         * The approved page's window at each font size plus the row, so the page ends as its own golden does. At fs200
         * the title wraps beside 更新, and the second line too where a state follows the date.
         */
        const val WHOLE = "+h1680dp"
        const val WHOLE_FS115 = "+h1703dp"
        const val FS200_IDLE = "+h2175dp"
        const val FS200_UPDATING = "+h2172dp"
        const val FS200_WRAPPED = "+h2216dp"

        const val WORD_JOINER = "⁠"
        const val ZERO_WIDTH_SPACE = "​"
        const val NO_BREAK_SPACE = "\u00A0"

        val IDLE = RvGeoData(RV_SAMPLE_GEO_SHIPPED_MS)
        val UPDATING = IDLE.copy(update = RvGeoUpdate.Updating)
        val UPDATED = RvGeoData(RV_SAMPLE_GEO_NEWER_MS, RvGeoUpdate.Updated)
        val LATEST = IDLE.copy(update = RvGeoUpdate.Latest)
        val FAILED = IDLE.copy(update = RvGeoUpdate.Failed)
        val WAITING = IDLE.copy(connected = false)
    }
}
