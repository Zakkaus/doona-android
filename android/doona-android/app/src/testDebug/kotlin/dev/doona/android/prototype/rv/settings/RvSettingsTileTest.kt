package dev.doona.android.prototype.rv.settings

import android.app.StatusBarManager.TILE_ADD_REQUEST_ERROR_APP_NOT_IN_FOREGROUND
import android.app.StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED
import android.app.StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_NOT_ADDED
import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import dev.doona.android.R
import dev.doona.android.designsystem.rv.RvInfoContent
import dev.doona.android.designsystem.rv.RvSheetLayout
import dev.doona.android.prototype.rv.RV_FS115
import dev.doona.android.prototype.rv.RV_FS200
import dev.doona.android.prototype.rv.assertWhole
import dev.doona.android.prototype.rv.rvContent
import dev.doona.android.prototype.rv.rvShot
import dev.doona.android.prototype.rv.rvText
import dev.doona.android.testing.drawFully
import dev.doona.android.vpn.quickTileResult
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 設定 while the quick settings tile is not added: 連線 offers 加到快速設定 (goldens `proto-rv-settings-tile-light`,
 * `-dark`, `-fs115`, `-fs200`), and the sheet that tells how to add it by hand open over the page (`-tile-manual-*`).
 * The system's answers: a dismissed dialog leaves the page pixel for pixel as it was, an added tile leaves it pixel for
 * pixel as 設定 without the row (`proto-rv-settings-*`), a failed request opens the sheet.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rTW-w412dp-h915dp-notnight-xhdpi")
class RvSettingsTileTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    @Config(qualifiers = TILE_PAGE)
    fun tileLight() = compose.rvShot("settings-tile", dark = false, before = { assertWhole() }) { Settings() }

    @Test
    @Config(qualifiers = TILE_PAGE)
    fun tileDark() = compose.rvShot("settings-tile", dark = true, before = { assertWhole() }) { Settings() }

    @Test
    @Config(qualifiers = TILE_PAGE_FS115)
    fun tileFs115() = compose.rvShot("settings-tile", dark = false, before = { assertWhole() }, fontScale = RV_FS115) { Settings() }

    @Test
    @Config(qualifiers = TILE_PAGE_FS200)
    fun tileFs200() = compose.rvShot("settings-tile", dark = false, before = { assertWhole() }, fontScale = RV_FS200) { Settings() }

    @Test
    fun manualLight() = compose.rvShot("settings-tile-manual", dark = false) { Manual() }

    @Test
    fun manualDark() = compose.rvShot("settings-tile-manual", dark = true) { Manual() }

    @Test
    fun manualFs115() = compose.rvShot("settings-tile-manual", dark = false, fontScale = RV_FS115) { Manual() }

    @Test
    fun manualFs200() = compose.rvShot("settings-tile-manual", dark = false, fontScale = RV_FS200) { Manual() }

    @Test
    fun theRowShowsOnlyUntilTheTileIsAdded() {
        var state by mutableStateOf(rvSampleSettings().copy(tileAdded = false))
        compose.rvContent(dark = false) { RvSettingsScreen(state, onChange = {}, onOpenTheme = {}) }
        compose.onNodeWithText(rvText(R.string.rv_settings_quick_tile)).assertExists()
        state = state.copy(tileAdded = true)
        compose.onNodeWithText(rvText(R.string.rv_settings_quick_tile)).assertDoesNotExist()
    }

    /** The store answers later than the first frame: 連線 is not drawn before, so no row is inserted into a card that shows. */
    @Test
    fun theConnectionCardWaitsForTheStoreInsteadOfGainingARow() {
        var state by mutableStateOf(rvSampleSettings().copy(tileAdded = true, tileKnown = false))
        compose.rvContent(dark = false) { RvSettingsScreen(state, onChange = {}, onOpenTheme = {}) }
        compose.onNodeWithText(rvText(R.string.rv_settings_always_on)).assertDoesNotExist()
        state = state.copy(tileAdded = false, tileKnown = true)
        compose.onNodeWithText(rvText(R.string.rv_settings_always_on)).assertExists()
        compose.onNodeWithText(rvText(R.string.rv_settings_quick_tile)).assertExists()
    }

    /** What `-tile` records, before and after the user dismissed the system's dialog. */
    @Test
    @Config(qualifiers = TILE_PAGE)
    fun aDismissedDialogKeepsThePage() {
        answering(TILE_ADD_REQUEST_RESULT_TILE_NOT_ADDED)
        val before = capture()
        compose.onNodeWithText(rvText(R.string.rv_settings_quick_tile)).performClick()
        assertTrue("the page changed", same(before, capture()))
    }

    /** What `proto-rv-settings-light` records (the row hidden), and the page once the system added the tile. */
    @Test
    @Config(qualifiers = LONG_PAGE)
    fun anAddedTileLeavesTheApprovedPage() {
        val host = answering(TILE_ADD_REQUEST_RESULT_TILE_ADDED, rvSampleSettings())
        val approved = capture()
        host.state = rvSampleSettings().copy(tileAdded = false)
        compose.onNodeWithText(rvText(R.string.rv_settings_quick_tile)).performClick()
        compose.onNodeWithText(rvText(R.string.rv_settings_quick_tile)).assertDoesNotExist()
        assertTrue("not the approved page", same(approved, capture()))
    }

    @Test
    fun aFailedRequestOpensTheSheetThatTellsHow() {
        answering(TILE_ADD_REQUEST_ERROR_APP_NOT_IN_FOREGROUND)
        compose.onNodeWithText(rvText(R.string.rv_done)).assertDoesNotExist()
        compose.onNodeWithText(rvText(R.string.rv_settings_quick_tile)).performClick()
        compose.onNodeWithText(rvText(R.string.rv_done)).performClick()
        compose.onNodeWithText(rvText(R.string.rv_done)).assertDoesNotExist()
        compose.onNodeWithText(rvText(R.string.rv_settings_quick_tile)).assertExists()
    }

    /** 設定 from [start], its state held here; 加到快速設定 gets the system's [code], as `rvQuickTile` passes it on. */
    private fun answering(code: Int, start: RvSettingsState = rvSampleSettings().copy(tileAdded = false)): HostState {
        val host = HostState(start)
        compose.rvContent(dark = false) {
            val routes = RvSettingsRoutes(addTile = { host.state = host.state.afterTileRequest(quickTileResult(code)) })
            RvSettingsScreen(host.state, onChange = { host.state = it }, onOpenTheme = {}, routes = routes)
        }
        return host
    }

    private class HostState(start: RvSettingsState) {
        var state by mutableStateOf(start)
    }

    private fun capture(): Bitmap {
        compose.drawFully()
        return compose.onRoot().captureToImage().asAndroidBitmap()
    }

    private fun same(a: Bitmap, b: Bitmap): Boolean {
        if (a.width != b.width || a.height != b.height) return false
        val pixels = { bitmap: Bitmap -> IntArray(bitmap.width * bitmap.height).also { bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height) } }
        return pixels(a).contentEquals(pixels(b))
    }

    @Composable
    private fun Settings() = RvSettingsScreen(rvSampleSettings().copy(tileAdded = false), onChange = {}, onOpenTheme = {})

    /** The sheet as [RvSettingsScreen] opens it, drawn over the page at rest (a dialog's window is not in the capture). */
    @Composable
    private fun Manual() {
        Box(Modifier.fillMaxSize()) {
            Settings()
            val title = stringResource(R.string.rv_settings_quick_tile)
            RvSheetLayout({ 1f }, {}, title) {
                RvInfoContent(title, stringResource(R.string.rv_settings_quick_tile_manual), stringResource(R.string.rv_done), onDone = {})
            }
        }
    }

    private companion object {
        /**
         * 設定's whole page with the row, at each font size, as tall as the page, a whole dp rounded up, so the last card
         * ends 28 dp above the nav (28.5 where the page ends on a half dp).
         */
        const val LONG_PAGE = "+h1768dp"
        const val TILE_PAGE = "+h1850dp"
        const val TILE_PAGE_FS115 = "+h1905dp"
        const val TILE_PAGE_FS200 = "+h2451dp"
    }
}
