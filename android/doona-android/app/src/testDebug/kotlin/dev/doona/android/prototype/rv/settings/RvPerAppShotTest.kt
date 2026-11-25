package dev.doona.android.prototype.rv.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.doona.android.R
import dev.doona.android.designsystem.rv.RvTheme
import dev.doona.android.feature.model.RvAppPolicy
import dev.doona.android.feature.perapp.RvPerAppActions
import dev.doona.android.feature.perapp.RvPerAppScreen
import dev.doona.android.prototype.rv.RV_FS115
import dev.doona.android.prototype.rv.RV_FS200
import dev.doona.android.prototype.rv.Seed
import dev.doona.android.prototype.rv.rvAnimatorScale
import dev.doona.android.prototype.rv.rvShot
import dev.doona.android.prototype.rv.rvText
import dev.doona.android.prototype.rv.seedColorScheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 依 App (`proto-rv-perapp-*`), each state light, dark, fs115 and fs200, in the phone's first screen (the list runs on
 * under the floating 儲存): every app (`perapp`), only the checked ones as 設定 has it (`-only`), all but two, an edit
 * not yet saved (`-except`), a search two apps match (`-search`) and one none does (`-no-result`), the apps not yet
 * read (`-loading`), 只有勾選的 with nothing checked (`-none-selected`), a save under way (`-saving`), a failed one
 * (`-save-failed`), and one stored whose tunnel would not come back (`-rebuild-failed`). The busy states are recorded at
 * animator scale 0, so the bar rests half full. Then what the page does: a policy row picks it, an app row toggles its
 * app, 儲存 takes only an edit that can be saved, the list and its search show only under a list policy, and the
 * notices' 重試 saves or reconnects again.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rTW-w412dp-h915dp-notnight-xhdpi")
class RvPerAppShotTest {
    @get:Rule
    val compose = createComposeRule()

    @After
    fun restoreScale() = rvAnimatorScale(1f)

    @Test fun allLight() = shot("all", "perapp", dark = false)

    @Test fun allDark() = shot("all", "perapp", dark = true)

    @Test fun allFs115() = shot("all", "perapp", fontScale = RV_FS115)

    @Test fun allFs200() = shot("all", "perapp", fontScale = RV_FS200)

    @Test fun onlyLight() = shot("only", dark = false)

    @Test fun onlyDark() = shot("only", dark = true)

    @Test fun onlyFs115() = shot("only", fontScale = RV_FS115)

    @Test fun onlyFs200() = shot("only", fontScale = RV_FS200)

    @Test fun exceptLight() = shot("except", dark = false)

    @Test fun exceptDark() = shot("except", dark = true)

    @Test fun exceptFs115() = shot("except", fontScale = RV_FS115)

    @Test fun exceptFs200() = shot("except", fontScale = RV_FS200)

    @Test fun searchLight() = shot("search", dark = false)

    @Test fun searchDark() = shot("search", dark = true)

    @Test fun searchFs115() = shot("search", fontScale = RV_FS115)

    @Test fun searchFs200() = shot("search", fontScale = RV_FS200)

    @Test fun noResultLight() = shot("no-result", dark = false)

    @Test fun noResultDark() = shot("no-result", dark = true)

    @Test fun noResultFs115() = shot("no-result", fontScale = RV_FS115)

    @Test fun noResultFs200() = shot("no-result", fontScale = RV_FS200)

    @Test fun loadingLight() = shot("loading", dark = false)

    @Test fun loadingDark() = shot("loading", dark = true)

    @Test fun loadingFs115() = shot("loading", fontScale = RV_FS115)

    @Test fun loadingFs200() = shot("loading", fontScale = RV_FS200)

    @Test fun noneSelectedLight() = shot("none-selected", dark = false)

    @Test fun noneSelectedDark() = shot("none-selected", dark = true)

    @Test fun noneSelectedFs115() = shot("none-selected", fontScale = RV_FS115)

    @Test fun noneSelectedFs200() = shot("none-selected", fontScale = RV_FS200)

    @Test fun savingLight() = shot("saving", dark = false)

    @Test fun savingDark() = shot("saving", dark = true)

    @Test fun savingFs115() = shot("saving", fontScale = RV_FS115)

    @Test fun savingFs200() = shot("saving", fontScale = RV_FS200)

    @Test fun saveFailedLight() = shot("save-failed", dark = false)

    @Test fun saveFailedDark() = shot("save-failed", dark = true)

    @Test fun saveFailedFs115() = shot("save-failed", fontScale = RV_FS115)

    @Test fun saveFailedFs200() = shot("save-failed", fontScale = RV_FS200)

    @Test fun rebuildFailedLight() = shot("rebuild-failed", dark = false)

    @Test fun rebuildFailedDark() = shot("rebuild-failed", dark = true)

    @Test fun rebuildFailedFs115() = shot("rebuild-failed", fontScale = RV_FS115)

    @Test fun rebuildFailedFs200() = shot("rebuild-failed", fontScale = RV_FS200)

    @Test
    fun aPolicyRowPicksThePolicy() {
        var picked: RvAppPolicy? = null
        compose.setContent { Themed { RvPerAppScreen(rvPerAppSample(""), RvPerAppActions(policy = { picked = it }), onBack = {}) } }
        compose.onNodeWithText(rvText(R.string.perapp_policy_except)).performClick()
        assertEquals(RvAppPolicy.Except, picked)
    }

    @Test
    fun anAppRowTogglesItsApp() {
        var toggled: Pair<String, Boolean>? = null
        compose.setContent { Themed { RvPerAppScreen(rvPerAppSample(""), RvPerAppActions(toggle = { pkg, on -> toggled = pkg to on }), onBack = {}) } }
        // Chat is checked in 設定's sample, so a tap unchecks it.
        compose.onNodeWithText("Chat").performClick()
        assertEquals("com.example.chat" to false, toggled)
    }

    @Test
    fun saveTakesOnlyAnEditThatCanBeSaved() {
        val save = rvText(R.string.perapp_save)
        for ((name, enabled) in listOf("" to false, "except" to true, "none-selected" to false, "loading" to false, "saving" to false)) {
            val state = rvPerAppSample(name)
            assertEquals("$name can be saved", enabled, state.canSave)
        }
        compose.setContent { Themed { RvPerAppScreen(rvPerAppSample("except"), RvPerAppActions(), onBack = {}) } }
        compose.onNodeWithText(save).assertIsEnabled()
    }

    @Test
    fun nothingCheckedUnderOnlyCannotBeSaved() {
        compose.setContent { Themed { RvPerAppScreen(rvPerAppSample("none-selected"), RvPerAppActions(), onBack = {}) } }
        compose.onNodeWithText(rvText(R.string.rv_perapp_needs_app)).assertExists()
        compose.onNodeWithText(rvText(R.string.perapp_save)).assertIsNotEnabled()
    }

    @Test
    fun theListAndItsSearchShowOnlyUnderAListPolicy() {
        compose.setContent { Themed { RvPerAppScreen(rvPerAppSample("all"), RvPerAppActions(), onBack = {}) } }
        compose.onAllNodes(hasContentDescription(rvText(R.string.perapp_search))).assertCountEquals(0)
        compose.onAllNodesWithText("Browser").assertCountEquals(0)
    }

    @Test
    fun theSearchStandsOnThePageBetweenThePoliciesAndTheApps() {
        compose.setContent { Themed { RvPerAppScreen(rvPerAppSample("only"), RvPerAppActions(), onBack = {}) } }
        val search = compose.onNode(hasContentDescription(rvText(R.string.perapp_search))).getUnclippedBoundsInRoot()
        val policies = compose.onNodeWithText(rvText(R.string.perapp_policy_except)).getUnclippedBoundsInRoot()
        val apps = compose.onNodeWithText("Browser").getUnclippedBoundsInRoot()
        assertTrue("the search is under the policies", search.top > policies.bottom)
        assertTrue("the search is over the apps", search.bottom < apps.top)
    }

    @Test
    fun theSearchKeepsTheAppsItMatches() {
        val state = rvPerAppSample("search")
        assertEquals(listOf("Mail", "Maps"), state.shown.map { it.label })
        compose.setContent { Themed { RvPerAppScreen(state, RvPerAppActions(), onBack = {}) } }
        compose.onAllNodes(hasContentDescription(rvText(R.string.perapp_search))).assertCountEquals(1)
        compose.onAllNodes(hasText("Browser")).assertCountEquals(0)
    }

    @Test
    fun aSaveUnderWayTakesNoEdit() {
        val saving = rvPerAppSample("saving")
        assertEquals(saving, saving.withPolicy(RvAppPolicy.All))
        assertEquals(saving, saving.withApp("com.example.chat", true))
    }

    @Test
    fun theNoticesRetry() {
        var saves = 0
        var reconnects = 0
        val actions = RvPerAppActions(save = { saves++ }, reconnect = { reconnects++ })
        compose.setContent { Themed { RvPerAppScreen(rvPerAppSample("save-failed"), actions, onBack = {}) } }
        compose.onNodeWithText(rvText(R.string.rv_perapp_save_failed)).assertExists()
        compose.onNodeWithText(rvText(R.string.rv_retry)).performClick()
        assertEquals(1, saves)
        assertEquals(0, reconnects)
    }

    @Test
    fun aRebuildFailureReconnects() {
        var reconnects = 0
        compose.setContent { Themed { RvPerAppScreen(rvPerAppSample("rebuild-failed"), RvPerAppActions(reconnect = { reconnects++ }), onBack = {}) } }
        compose.onNodeWithText(rvText(R.string.rv_retry)).performClick()
        assertEquals(1, reconnects)
    }

    private fun shot(state: String, name: String = "perapp-$state", dark: Boolean = false, fontScale: Float = 1f) {
        if (state == "saving" || state == "loading") rvAnimatorScale(0f)
        compose.rvShot(name, dark, fontScale = fontScale) { RvPerAppScreen(rvPerAppSample(state), RvPerAppActions(), onBack = {}) }
    }

    @Composable
    private fun Themed(content: @Composable () -> Unit) = RvTheme(seedColorScheme(Seed.Cyan.argb, false), false, content)
}
