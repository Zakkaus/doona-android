package dev.doona.android.prototype.rv.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.doona.android.R
import dev.doona.android.prototype.rv.RV_FS115
import dev.doona.android.prototype.rv.RV_FS200
import dev.doona.android.prototype.rv.RvTheme
import dev.doona.android.prototype.rv.Seed
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
 * 檢查更新 on the 關於 page, each light, dark, fs115 and fs200: while it asks GitHub (`proto-rv-settings-update-checking-*`,
 * at animator scale 0, so the busy bar rests half full), with nothing newer (`-update-latest-*`), with a newer release
 * (`-update-new-*`) and with no answer (`-update-failed-*`). Then what the banners' actions do.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rTW-w412dp-h915dp-notnight-xhdpi")
class RvUpdateShotTest {
    @get:Rule
    val compose = createComposeRule()

    @After
    fun restoreScale() = rvAnimatorScale(1f)

    @Test fun checkingLight() = shot("checking", RvUpdate.Checking, dark = false)

    @Test fun checkingDark() = shot("checking", RvUpdate.Checking, dark = true)

    @Test fun checkingFs115() = shot("checking", RvUpdate.Checking, fontScale = RV_FS115)

    @Test fun checkingFs200() = shot("checking", RvUpdate.Checking, fontScale = RV_FS200)

    @Test fun latestLight() = shot("latest", RvUpdate.Latest, dark = false)

    @Test fun latestDark() = shot("latest", RvUpdate.Latest, dark = true)

    @Test fun latestFs115() = shot("latest", RvUpdate.Latest, fontScale = RV_FS115)

    @Test fun latestFs200() = shot("latest", RvUpdate.Latest, fontScale = RV_FS200)

    @Test fun newLight() = shot("new", NEW, dark = false)

    @Test fun newDark() = shot("new", NEW, dark = true)

    @Test fun newFs115() = shot("new", NEW, fontScale = RV_FS115)

    @Test fun newFs200() = shot("new", NEW, fontScale = RV_FS200)

    @Test fun failedLight() = shot("failed", RvUpdate.Failed, dark = false)

    @Test fun failedDark() = shot("failed", RvUpdate.Failed, dark = true)

    @Test fun failedFs115() = shot("failed", RvUpdate.Failed, fontScale = RV_FS115)

    @Test fun failedFs200() = shot("failed", RvUpdate.Failed, fontScale = RV_FS200)

    @Test
    fun aNewerReleaseOpensItsPage() {
        var opened = 0
        compose.setContent { Themed { Page(NEW, RvAboutRoutes(release = { opened++ })) } }
        compose.onNodeWithText(text(R.string.rv_update_new, RV_SAMPLE_RELEASE)).assertExists()
        compose.onNodeWithText(rvText(R.string.rv_update_view)).performClick()
        assertEquals(1, opened)
    }

    @Test
    fun aFailedCheckIsToldAndRetried() {
        var checks = 0
        compose.setContent { Themed { Page(RvUpdate.Failed, RvAboutRoutes(checkUpdate = { checks++ })) } }
        compose.onNodeWithText(rvText(R.string.rv_update_failed)).assertExists()
        compose.onNodeWithText(rvText(R.string.rv_retry)).performClick()
        assertEquals(1, checks)
    }

    @Test
    fun nothingNewerRenamesTheActionAndShowsNoBanner() {
        compose.setContent { Themed { Page(RvUpdate.Latest) } }
        compose.onNodeWithText(rvText(R.string.rv_update_latest)).assertExists()
        compose.onNodeWithText(rvText(R.string.rv_settings_check_update)).assertDoesNotExist()
        compose.onNodeWithText(rvText(R.string.rv_retry)).assertDoesNotExist()
    }

    private fun shot(name: String, update: RvUpdate, dark: Boolean = false, fontScale: Float = 1f) {
        if (update == RvUpdate.Checking) rvAnimatorScale(0f)
        compose.rvShot("settings-update-$name", dark, fontScale = fontScale) { Page(update) }
    }

    private fun text(id: Int, arg: String) = RuntimeEnvironment.getApplication().getString(id, arg)

    @Composable
    private fun Page(update: RvUpdate, routes: RvAboutRoutes = RvAboutRoutes()) = RvAboutScreen(rvSampleVersions, onBack = {}, routes = routes, update = update)

    @Composable
    private fun Themed(content: @Composable () -> Unit) = RvTheme(seedColorScheme(Seed.Cyan.argb, false), false, content)

    private companion object {
        val NEW = RvUpdate.Available(RV_SAMPLE_RELEASE)
    }
}
