package dev.doona.android.prototype.rv.samples

import androidx.compose.ui.test.junit4.v2.createComposeRule
import dev.doona.android.feature.model.RvGeoData
import dev.doona.android.feature.settings.RvLabScreen
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

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rTW-w412dp-h915dp-notnight-xhdpi")
class RvLabShotTest {
    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val utc = Utc()

    @Test
    @Config(qualifiers = "+h1990dp")
    fun settingsApprovedLight() = compose.rvShot("samples-lab-settings-approved", dark = false, before = { assertWhole() }) { Settings() }

    @Test
    fun pageLight() = compose.rvShot("samples-lab-page", dark = false, before = { assertWhole() }) { RvLabScreen(false, {}, {}) }

    @Test
    @Config(qualifiers = "+h1990dp")
    fun settingsApprovedDark() = compose.rvShot("samples-lab-settings-approved", dark = true, before = { assertWhole() }) { Settings() }

    @Test
    fun pageDark() = compose.rvShot("samples-lab-page", dark = true, before = { assertWhole() }) { RvLabScreen(true, {}, {}) }

    @Test
    @Config(qualifiers = "+h2589dp")
    fun settingsApprovedFs200() = compose.rvShot("samples-lab-settings-approved", dark = false, before = { assertWhole() }, fontScale = RV_FS200) { Settings() }

    @Test
    fun pageFs200() = compose.rvShot("samples-lab-page", dark = false, before = { assertWhole() }, fontScale = RV_FS200) { RvLabScreen(false, {}, {}) }

    @Test
    @Config(qualifiers = "en-w412dp-h2040dp-notnight-xhdpi")
    fun settingsApprovedEn() = compose.rvShot("samples-lab-settings-approved-en", dark = false, before = { assertWhole() }) { Settings() }

    @Test
    @Config(qualifiers = "en-w412dp-h915dp-notnight-xhdpi")
    fun pageEn() = compose.rvShot("samples-lab-page-en", dark = false, before = { assertWhole() }) { RvLabScreen(false, {}, {}) }

    @androidx.compose.runtime.Composable
    private fun Settings() = RvSettingsScreen(rvSampleSettings(), rvSampleVersions.app, onChange = {}, onOpenTheme = {}, routes = rvSettingsRoutes(), geoData = RvGeoData(RV_SAMPLE_GEO_SHIPPED))
}
