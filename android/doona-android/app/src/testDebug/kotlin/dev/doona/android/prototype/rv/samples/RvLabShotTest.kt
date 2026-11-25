package dev.doona.android.prototype.rv.samples

import androidx.compose.ui.test.junit4.v2.createComposeRule
import dev.doona.android.feature.model.RvGeoData
import dev.doona.android.feature.settings.RvSettingsScreen
import dev.doona.android.prototype.rv.RV_FS200
import dev.doona.android.prototype.rv.Utc
import dev.doona.android.prototype.rv.assertWhole
import dev.doona.android.prototype.rv.rvShot
import dev.doona.android.prototype.rv.settings.RV_SAMPLE_GEO_SHIPPED
import dev.doona.android.prototype.rv.settings.rvSampleSettings
import dev.doona.android.prototype.rv.settings.rvSampleVersions
import dev.doona.android.prototype.rv.settings.rvSettingsRoutes
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The sample of 實驗室, whole pages: 設定 as the page draws it (`proto-rv-samples-lab-settings-approved-<v>`), which
 * since 進階 took 日誌等級 (2026-11-26) is the sample with the new row (`-sample-<v>`) plus that row, and the new
 * page (`proto-rv-samples-lab-page-<v>`), for v light, dark, fs200 and en-light.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rTW-w412dp-h915dp-notnight-xhdpi")
class RvLabShotTest {
    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val utc = Utc()

    @Test
    @Config(qualifiers = "+h1908dp")
    fun settingsApprovedLight() = compose.rvShot("samples-lab-settings-approved", dark = false, before = { assertWhole() }) { Settings() }

    @Test
    @Config(qualifiers = "+h1826dp")
    fun settingsSampleLight() = compose.rvShot("samples-lab-settings-sample", dark = false, before = { assertWhole() }) { RvSettingsLabSample(rvSampleSettings(), {}, {}) }

    @Test
    fun pageLight() = compose.rvShot("samples-lab-page", dark = false, before = { assertWhole() }) { RvLabScreen(performance = false) }

    @Test
    @Config(qualifiers = "+h1908dp")
    fun settingsApprovedDark() = compose.rvShot("samples-lab-settings-approved", dark = true, before = { assertWhole() }) { Settings() }

    @Test
    @Config(qualifiers = "+h1826dp")
    fun settingsSampleDark() = compose.rvShot("samples-lab-settings-sample", dark = true, before = { assertWhole() }) { RvSettingsLabSample(rvSampleSettings(), {}, {}) }

    @Test
    fun pageDark() = compose.rvShot("samples-lab-page", dark = true, before = { assertWhole() }) { RvLabScreen(performance = true) }

    @Test
    @Config(qualifiers = "+h2481dp")
    fun settingsApprovedFs200() = compose.rvShot("samples-lab-settings-approved", dark = false, before = { assertWhole() }, fontScale = RV_FS200) { Settings() }

    @Test
    @Config(qualifiers = "+h2373dp")
    fun settingsSampleFs200() = compose.rvShot("samples-lab-settings-sample", dark = false, before = { assertWhole() }, fontScale = RV_FS200) { RvSettingsLabSample(rvSampleSettings(), {}, {}) }

    @Test
    fun pageFs200() = compose.rvShot("samples-lab-page", dark = false, before = { assertWhole() }, fontScale = RV_FS200) { RvLabScreen(performance = false) }

    @Test
    @Config(qualifiers = "en-w412dp-h1958dp-notnight-xhdpi")
    fun settingsApprovedEn() = compose.rvShot("samples-lab-settings-approved-en", dark = false, before = { assertWhole() }) { Settings() }

    @Test
    @Config(qualifiers = "en-w412dp-h1876dp-notnight-xhdpi")
    fun settingsSampleEn() = compose.rvShot("samples-lab-settings-sample-en", dark = false, before = { assertWhole() }) { RvSettingsLabSample(rvSampleSettings(), {}, {}) }

    @Test
    @Config(qualifiers = "en-w412dp-h915dp-notnight-xhdpi")
    fun pageEn() = compose.rvShot("samples-lab-page-en", dark = false, before = { assertWhole() }) { RvLabScreen(performance = false) }

    @androidx.compose.runtime.Composable
    private fun Settings() = RvSettingsScreen(rvSampleSettings(), rvSampleVersions.app, onChange = {}, onOpenTheme = {}, routes = rvSettingsRoutes(), geoData = RvGeoData(RV_SAMPLE_GEO_SHIPPED))
}
