package dev.doona.android.prototype.rv.settings

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.doona.android.R
import dev.doona.android.designsystem.rv.RvTheme
import dev.doona.android.feature.settings.RvLabScreen
import dev.doona.android.prototype.rv.RV_EN
import dev.doona.android.prototype.rv.RV_FS115
import dev.doona.android.prototype.rv.RV_FS200
import dev.doona.android.prototype.rv.Seed
import dev.doona.android.prototype.rv.assertWhole
import dev.doona.android.prototype.rv.rvShot
import dev.doona.android.prototype.rv.rvText
import dev.doona.android.prototype.rv.seedColorScheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 實驗室 as the app draws it (`proto-rv-settings-lab-<v>`, v light, dark, fs115, fs200 and en-light), to be the same
 * image as the approved sample s1125-lab-page (`proto-rv-samples-lab-page-<v>`); dark shows 效能模式 on, as the
 * sample's does. A tap on the row turns the switch through the caller.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rTW-w412dp-h915dp-notnight-xhdpi")
class RvLabPageShotTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun labLight() = compose.rvShot("settings-lab", dark = false, before = { assertWhole() }) { RvLabScreen(false, {}, onBack = {}) }

    @Test
    fun labDark() = compose.rvShot("settings-lab", dark = true, before = { assertWhole() }) { RvLabScreen(true, {}, onBack = {}) }

    @Test
    fun labFs115() = compose.rvShot("settings-lab", dark = false, before = { assertWhole() }, fontScale = RV_FS115) { RvLabScreen(false, {}, onBack = {}) }

    @Test
    fun labFs200() = compose.rvShot("settings-lab", dark = false, before = { assertWhole() }, fontScale = RV_FS200) { RvLabScreen(false, {}, onBack = {}) }

    @Test
    @Config(qualifiers = RV_EN)
    fun labEn() = compose.rvShot("settings-lab-en", dark = false, before = { assertWhole() }) { RvLabScreen(false, {}, onBack = {}) }

    @Test
    fun rowTurnsPerformance() {
        val turned = mutableListOf<Boolean>()
        compose.setContent { RvTheme(seedColorScheme(Seed.Cyan.argb, false), false) { RvLabScreen(false, { turned += it }, onBack = {}) } }
        compose.onNodeWithText(rvText(R.string.rv_lab_performance)).performClick()
        assertEquals(listOf(true), turned)
    }
}
